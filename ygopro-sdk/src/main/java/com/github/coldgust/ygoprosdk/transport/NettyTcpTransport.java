package com.github.coldgust.ygoprosdk.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Netty（NIO）的 TCP 传输实现。
 * 管线只做字节转发——协议帧的按长度切分由会话层完成（编解码单源，规范 §10.2）。
 */
public final class NettyTcpTransport implements Transport {

    private final String host;
    private final int port;
    private final EventLoopGroup group = new NioEventLoopGroup(1, runnable -> {
        Thread thread = new Thread(runnable, "ygo-netty");
        thread.setDaemon(true); // 会话关闭后不阻止 JVM 退出（CLI runner 需要）
        return thread;
    });
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile Channel channel;
    private volatile Listener listener;

    public NettyTcpTransport(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public void connect(Listener listener) throws Exception {
        this.listener = listener;
        CountDownLatch established = new CountDownLatch(1);
        AtomicBoolean failure = new AtomicBoolean(false);

        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                connected.set(true);
                                established.countDown();
                            }

                            @Override
                            public void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                                Listener l = listener;
                                if (l == null || !msg.isReadable()) {
                                    return;
                                }
                                byte[] data = new byte[msg.readableBytes()];
                                msg.readBytes(data);
                                l.onBytes(data, data.length);
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) {
                                connected.set(false);
                                Listener l = listener;
                                if (l != null) {
                                    l.onClosed();
                                }
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                Listener l = listener;
                                if (l != null) {
                                    l.onError(cause);
                                }
                            }
                        });
                    }
                });

        channel = bootstrap.connect(host, port)
                .addListener(future -> {
                    if (!future.isSuccess()) {
                        failure.set(true);
                        established.countDown();
                    }
                })
                .channel();

        if (!established.await(6, TimeUnit.SECONDS) || failure.get()) {
            close();
            throw new IllegalStateException("连接 %s:%d 失败".formatted(host, port));
        }
    }

    @Override
    public void send(byte[] frame) throws Exception {
        Channel ch = channel;
        if (ch == null || !ch.isActive()) {
            throw new IllegalStateException("连接未建立");
        }
        ch.writeAndFlush(Unpooled.wrappedBuffer(frame)).sync();
    }

    @Override
    public boolean isConnected() {
        return connected.get() && channel != null && channel.isActive();
    }

    @Override
    public void close() {
        connected.set(false);
        if (channel != null) {
            channel.close();
            channel = null;
        }
        group.shutdownGracefully(0, 500, TimeUnit.MILLISECONDS);
    }
}

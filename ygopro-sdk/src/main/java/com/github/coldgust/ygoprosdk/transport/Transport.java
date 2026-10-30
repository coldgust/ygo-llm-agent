package com.github.coldgust.ygoprosdk.transport;

/**
 * 传输 SPI（规范 §10.1）。SDK 内置 Netty TCP 实现（{@link NettyTcpTransport}），
 * 可替换为 WebSocket 桥或测试桩。
 *
 * <p>事件模型：传输层把收到的原始字节回调给 {@link Listener}；
 * 帧切分（§2.2）由会话层完成，保持"编解码单源"。
 */
public interface Transport extends AutoCloseable {

    /** 建立连接（阻塞，直至连接建立或抛出异常）。 */
    void connect(Listener listener) throws Exception;

    /** 发送一帧原始字节（帧由 {@code FrameCodec.encode} 构造）。 */
    void send(byte[] frame) throws Exception;

    /** 是否已建立连接。 */
    boolean isConnected();

    @Override
    void close();

    /** 传输事件监听。 */
    interface Listener {

        /** 收到原始字节（可能不足一帧，也可能多帧）。 */
        void onBytes(byte[] data, int length);

        /** 连接被对端关闭。 */
        void onClosed();

        /** 传输层异常。 */
        void onError(Throwable t);
    }
}

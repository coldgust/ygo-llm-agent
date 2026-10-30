package com.github.coldgust.ygoprosdk.session;

/** 协议层异常（规范 §5.6 / §10）。 */
public class ProtocolException extends RuntimeException {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}

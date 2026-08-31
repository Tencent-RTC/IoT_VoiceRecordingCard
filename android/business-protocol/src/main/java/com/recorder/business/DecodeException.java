package com.recorder.business;

/**
 * 业务报文解码失败。
 *
 * <p>解码失败属于「一刀切」协议错误，App 侧应断开连接。
 */
public final class DecodeException extends Exception {
    public DecodeException(String message) {
        super(message);
    }

    public DecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}

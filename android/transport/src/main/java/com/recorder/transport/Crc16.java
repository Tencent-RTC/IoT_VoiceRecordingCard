package com.recorder.transport;

/**
 * CRC-16/CCITT-FALSE：多项式 0x1021，初值 0xFFFF，不反转，无异或输出。
 *
 * <p>协议要求对「整条重组后业务消息」计算，且必须支持随分片增量累加，
 * 因此这里提供 {@link #update} 形式而非一次性接口。
 *
 * <p>它防的不是链路错误（BLE 链路层已有 CRC-24），而是我方分片/重组代码的 bug。
 */
public final class Crc16 {

    public static final int INIT = 0xFFFF;

    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int crc = i << 8;
            for (int j = 0; j < 8; j++) {
                crc = ((crc & 0x8000) != 0) ? ((crc << 1) ^ 0x1021) : (crc << 1);
            }
            TABLE[i] = crc & 0xFFFF;
        }
    }

    private Crc16() {
    }

    public static int update(int crc, byte[] data, int offset, int length) {
        int c = crc & 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            c = ((c << 8) ^ TABLE[((c >>> 8) ^ (data[i] & 0xFF)) & 0xFF]) & 0xFFFF;
        }
        return c;
    }

    public static int of(byte[] data, int offset, int length) {
        return update(INIT, data, offset, length);
    }

    public static int of(byte[] data) {
        return of(data, 0, data.length);
    }
}

package com.recorder.transport;

/**
 * 帧编解码（协议文档 §4）。无状态，纯函数式。
 *
 * <p>DATA 帧只有两种布局：
 * <pre>
 * EOM = 0:  typeAndFlags(1) seq(2) payload(N>=1)                 → 开销 3 B
 * EOM = 1:  typeAndFlags(1) seq(2) payload(N>=1) msgCrc16(2)     → 开销 5 B
 * </pre>
 *
 * <p>ACK 帧 4 B：typeAndFlags(1) ackSeq(2) credit(1)
 * <p>LINK_SETUP / LINK_SETUP_ACK 2 B：typeAndFlags(1) version(1)
 *
 * <p>所有多字节整数为大端（BE）。
 */
public final class FrameCodec {

    public static final int DATA_HEADER = 3;
    public static final int DATA_CRC = 2;
    public static final int ACK_LEN = 4;
    public static final int LINK_SETUP_LEN = 2;

    private FrameCodec() {
    }

    // ---------------- MTU / 载荷计算（§5） ----------------

    /** attPayload = mtu - 3 */
    public static int attPayloadOf(int mtu) {
        return Math.max(0, mtu - 3);
    }

    /** 中间帧（EOM=0）可用载荷。 */
    public static int midFramePayloadMax(int attPayload) {
        return Math.max(0, attPayload - DATA_HEADER);
    }

    /** 首帧 / 末帧 / 单帧（EOM=1 或需为 CRC 预留）可用载荷。 */
    public static int edgeFramePayloadMax(int attPayload) {
        return Math.max(0, attPayload - DATA_HEADER - DATA_CRC);
    }

    /**
     * 分片算法（协议文档 §5.1）。返回本帧应取的 payload 字节数。
     *
     * <p>需为末帧预留 2 B CRC 空间。最坏情况多产生一帧，
     * 换取逻辑无分支歧义。
     */
    public static int nextChunkSize(int remaining, int attPayload) {
        int mid = midFramePayloadMax(attPayload);
        int edge = edgeFramePayloadMax(attPayload);
        if (remaining <= edge) {
            return remaining;      // 本帧即末帧，EOM = 1
        }
        if (remaining <= mid) {
            return edge;           // 差一点装不下 CRC，剩余进下一帧
        }
        return mid;                // 中间帧，EOM = 0
    }

    // ---------------- 编码 ----------------

    public static byte[] encodeData(int seq, int flags, byte[] src, int off, int len, int msgCrc16) {
        boolean eom = (flags & DataFlags.EOM) != 0;
        int total = DATA_HEADER + len + (eom ? DATA_CRC : 0);
        byte[] out = new byte[total];
        out[0] = (byte) ((FrameType.DATA.value << 4) | (flags & 0x0F));
        out[1] = (byte) ((seq >>> 8) & 0xFF);
        out[2] = (byte) (seq & 0xFF);
        System.arraycopy(src, off, out, DATA_HEADER, len);
        if (eom) {
            out[DATA_HEADER + len] = (byte) ((msgCrc16 >>> 8) & 0xFF);
            out[DATA_HEADER + len + 1] = (byte) (msgCrc16 & 0xFF);
        }
        return out;
    }

    public static byte[] encodeAck(int ackSeq, int credit) {
        byte[] out = new byte[ACK_LEN];
        out[0] = (byte) (FrameType.ACK.value << 4);
        out[1] = (byte) ((ackSeq >>> 8) & 0xFF);
        out[2] = (byte) (ackSeq & 0xFF);
        out[3] = (byte) (credit & 0xFF);
        return out;
    }

    public static byte[] encodeLinkSetup(boolean isAck, int version) {
        byte[] out = new byte[LINK_SETUP_LEN];
        FrameType t = isAck ? FrameType.LINK_SETUP_ACK : FrameType.LINK_SETUP;
        out[0] = (byte) (t.value << 4);
        out[1] = (byte) (version & 0xFF);
        return out;
    }

    // ---------------- 解码 ----------------

    /** 解码结果。type == null 表示非法帧（调用方须按 PROTOCOL_VIOLATION 拆链）。 */
    public static final class Frame {
        public FrameType type;
        public int flags;
        public int seq;
        public int msgCrc16;
        public byte[] payload;
        public int ackSeq;
        public int credit;
        public int version;
        public String error;

        boolean fail(String msg) {
            this.type = null;
            this.error = msg;
            return false;
        }

        public boolean isSom() {
            return (flags & DataFlags.SOM) != 0;
        }

        public boolean isEom() {
            return (flags & DataFlags.EOM) != 0;
        }
    }

    /**
     * 严格解码。任何长度不符、保留位非 0、未知类型均判为非法。
     * 不接受尾随字节。
     */
    public static Frame decode(byte[] data) {
        Frame f = new Frame();
        if (data == null || data.length < 2) {
            f.fail("帧长度不足 2 字节");
            return f;
        }

        int typeValue = (data[0] >>> 4) & 0x0F;
        int flags = data[0] & 0x0F;
        FrameType type = FrameType.fromValue(typeValue);
        if (type == null) {
            // 含 0x4 NACK / 0x5 PING / 0x6 PONG / 0x8 LINK_RESET：
            // 本版保留不实现，收到即视为版本错配，立即暴露。
            f.fail("未知或保留帧类型 0x" + Integer.toHexString(typeValue));
            return f;
        }

        switch (type) {
            case DATA:
                return decodeData(data, flags, f);

            case ACK:
                if (flags != 0) {
                    f.fail("ACK 帧 flags 必须为 0");
                    return f;
                }
                if (data.length != ACK_LEN) {
                    f.fail("ACK 帧长度必须为 " + ACK_LEN + "，实际 " + data.length);
                    return f;
                }
                f.type = FrameType.ACK;
                f.ackSeq = ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
                f.credit = data[3] & 0xFF;
                return f;

            case LINK_SETUP:
            case LINK_SETUP_ACK:
                if (flags != 0) {
                    f.fail("LINK_SETUP 帧 flags 必须为 0");
                    return f;
                }
                if (data.length != LINK_SETUP_LEN) {
                    f.fail("LINK_SETUP 帧长度必须为 " + LINK_SETUP_LEN + "，实际 " + data.length);
                    return f;
                }
                f.type = type;
                f.version = data[1] & 0xFF;
                return f;

            default:
                f.fail("不可达");
                return f;
        }
    }

    private static Frame decodeData(byte[] data, int flags, Frame f) {
        if ((flags & DataFlags.RESERVED_MASK) != 0) {
            f.fail("DATA 帧保留位必须为 0");
            return f;
        }
        boolean eom = (flags & DataFlags.EOM) != 0;
        int minLen = eom ? (DATA_HEADER + DATA_CRC + 1) : (DATA_HEADER + 1);
        if (data.length < minLen) {
            f.fail("DATA 帧长度不足，EOM=" + (eom ? 1 : 0) + " 时最小 " + minLen
                    + "，实际 " + data.length);
            return f;
        }

        int payloadLen = data.length - DATA_HEADER - (eom ? DATA_CRC : 0);
        if (payloadLen < 1) {
            f.fail("不允许空载荷 DATA 帧");
            return f;
        }

        f.type = FrameType.DATA;
        f.flags = flags;
        f.seq = ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
        f.payload = new byte[payloadLen];
        System.arraycopy(data, DATA_HEADER, f.payload, 0, payloadLen);
        if (eom) {
            int p = DATA_HEADER + payloadLen;
            f.msgCrc16 = ((data[p] & 0xFF) << 8) | (data[p + 1] & 0xFF);
        }
        return f;
    }
}

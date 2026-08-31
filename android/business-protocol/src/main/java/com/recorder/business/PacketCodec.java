package com.recorder.business;

import com.google.protobuf.InvalidProtocolBufferException;
import com.recorder.business.proto.PacketType;
import com.recorder.business.proto.RecorderPacket;

/**
 * {@link RecorderPacket} ↔ {@code byte[]} 编解码。
 *
 * <p>序列化 / 反序列化由 Google 官方 protobuf（javalite）完成，
 * 本类只补充协议要求的结构化校验：
 * <ul>
 *   <li>反序列化失败 → {@link DecodeException}（一刀切协议错误，App设计.md §2.3）；</li>
 *   <li>顶层 {@code type} 缺失（UNSPECIFIED 或未识别值）→ {@link DecodeException}；</li>
 *   <li>{@code type} 与 {@code oneof body} 不匹配 → {@link DecodeException}（§2.4）；</li>
 *   <li>{@code oneof body} 缺失 → {@link DecodeException}。</li>
 * </ul>
 * 业务级必填字段校验（如 id != 0）由两侧 Session 按「一刀切」清单执行。
 */
public final class PacketCodec {

    private PacketCodec() {
    }

    public static byte[] encode(RecorderPacket packet) {
        return packet.toByteArray();
    }

    public static RecorderPacket decode(byte[] data) throws DecodeException {
        final RecorderPacket packet;
        try {
            packet = RecorderPacket.parseFrom(data);
        } catch (InvalidProtocolBufferException e) {
            throw new DecodeException("RecorderPacket 无法完成 Protobuf 反序列化", e);
        }

        int expectedType = expectedTypeForBody(packet.getBodyCase());
        if (expectedType < 0) {
            throw new DecodeException("oneof body 缺失");
        }
        int typeValue = packet.getTypeValue();
        if (typeValue == PacketType.PACKET_TYPE_UNSPECIFIED.getNumber()
                || packet.getType() == PacketType.UNRECOGNIZED) {
            throw new DecodeException("顶层 type 缺失或为未识别值: " + typeValue);
        }
        if (typeValue != expectedType) {
            throw new DecodeException("type(" + packet.getType() + ") 与 oneof body("
                    + packet.getBodyCase() + ") 不匹配");
        }
        return packet;
    }

    /** oneof body → 协议规定的顶层 PacketType 值；body 缺失返回 -1。 */
    private static int expectedTypeForBody(RecorderPacket.BodyCase bodyCase) {
        switch (bodyCase) {
            case DEVICE_STATUS_REQUEST:
                return PacketType.DEVICE_STATUS_REQUEST.getNumber();
            case RECORDING_START_REQUEST:
                return PacketType.REC_RECORDING_START_REQUEST.getNumber();
            case RECORDING_ATTACH_REQUEST:
                return PacketType.REC_RECORDING_ATTACH_REQUEST.getNumber();
            case RECORDING_STOP_REQUEST:
                return PacketType.REC_RECORDING_STOP_REQUEST.getNumber();
            case FILE_LIST_REQUEST:
                return PacketType.REC_FILE_LIST_REQUEST.getNumber();
            case FILE_DOWNLOAD_START_REQUEST:
                return PacketType.REC_FILE_DOWNLOAD_START_REQUEST.getNumber();
            case FILE_DOWNLOAD_PAUSE_REQUEST:
                return PacketType.REC_FILE_DOWNLOAD_PAUSE_REQUEST.getNumber();
            case FILE_DOWNLOAD_COMPLETE_REQUEST:
                return PacketType.REC_FILE_DOWNLOAD_COMPLETE_REQUEST.getNumber();
            case DEVICE_STATUS_RESULT:
                return PacketType.DEVICE_STATUS_RESULT.getNumber();
            case RECORDING_START_RESULT:
                return PacketType.REC_RECORDING_START_RESULT.getNumber();
            case RECORDING_ATTACH_RESULT:
                return PacketType.REC_RECORDING_ATTACH_RESULT.getNumber();
            case RECORDING_STOP_RESULT:
                return PacketType.REC_RECORDING_STOP_RESULT.getNumber();
            case FILE_LIST_RESULT:
                return PacketType.REC_FILE_LIST_RESULT.getNumber();
            case FILE_DOWNLOAD_START_RESULT:
                return PacketType.REC_FILE_DOWNLOAD_START_RESULT.getNumber();
            case FILE_DOWNLOAD_PAUSE_RESULT:
                return PacketType.REC_FILE_DOWNLOAD_PAUSE_RESULT.getNumber();
            case FILE_DOWNLOAD_COMPLETE_RESULT:
                return PacketType.REC_FILE_DOWNLOAD_COMPLETE_RESULT.getNumber();
            case RECORDING_STARTED_EVENT:
                return PacketType.REC_RECORDING_STARTED_EVENT.getNumber();
            case RECORDING_STOPPED_EVENT:
                return PacketType.REC_RECORDING_STOPPED_EVENT.getNumber();
            case AUDIO_FRAME:
                return PacketType.REC_AUDIO_FRAME.getNumber();
            case FILE_CHUNK:
                return PacketType.REC_FILE_CHUNK.getNumber();
            case BODY_NOT_SET:
            default:
                return -1;
        }
    }
}

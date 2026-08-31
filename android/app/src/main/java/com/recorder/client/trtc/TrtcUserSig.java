package com.recorder.client.trtc;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.Deflater;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TRTC UserSig 测试签名生成，使用 HMAC-SHA256、zlib 与 base64url 字符替换。
 *
 * <p>与 SDK 文档版唯一的工程差异：用 Java 8 标准库的 {@link java.util.Base64}
 * 替代 android.util.Base64，使同一份代码可在本地纯 Java 环境与
 * Android 8.0（API 26，java.util.Base64 自该版本起可用）以上直接复用。
 *
 * <p>注意：该方案仅适合 demo 联调。正式产品必须把 UserSig 计算与密钥迁移到
 * 业务服务器，客户端按需拉取，避免密钥随安装包泄露。
 */
public final class TrtcUserSig {

    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    private TrtcUserSig() {
    }

    /**
     * 计算 UserSig。
     *
     * @param sdkAppId      TRTC 应用 SDKAppId
     * @param userId        用户标识；云端 ASR 场景下与请求 URL 中的 RequestId 一致
     * @param expireSeconds 签名有效期（秒）
     * @param secretKey     与 sdkAppId 对应的密钥
     * @return 可直接放入 X-TRTC-UserSig 请求头的签名；失败返回空串
     */
    public static String genTestUserSig(long sdkAppId, String userId, long expireSeconds,
                                        String secretKey) {
        if (userId == null || userId.isEmpty() || secretKey == null || secretKey.isEmpty()) {
            return "";
        }
        long currTime = System.currentTimeMillis() / 1000L;
        String sig = hmacsha256(sdkAppId, userId, currTime, expireSeconds, secretKey);
        if (sig.isEmpty()) {
            return "";
        }
        JsonObject doc = new JsonObject();
        doc.addProperty("TLS.ver", "2.0");
        doc.addProperty("TLS.identifier", userId);
        doc.addProperty("TLS.sdkappid", sdkAppId);
        doc.addProperty("TLS.expire", expireSeconds);
        doc.addProperty("TLS.time", currTime);
        doc.addProperty("TLS.sig", sig);
        String sigDoc = new Gson().toJson(doc);
        Deflater compressor = new Deflater();
        compressor.setInput(sigDoc.getBytes(UTF_8));
        compressor.finish();
        byte[] compressed = new byte[2048];
        int compressedLength = compressor.deflate(compressed);
        compressor.end();
        return base64EncodeUrl(Arrays.copyOfRange(compressed, 0, compressedLength));
    }

    private static String hmacsha256(long sdkAppId, String userId, long currTime,
                                     long expireSeconds, String secretKey) {
        String contentToBeSigned = "TLS.identifier:" + userId + "\n"
                + "TLS.sdkappid:" + sdkAppId + "\n"
                + "TLS.time:" + currTime + "\n"
                + "TLS.expire:" + expireSeconds + "\n";
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secretKey.getBytes(UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    hmac.doFinal(contentToBeSigned.getBytes(UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private static String base64EncodeUrl(byte[] input) {
        byte[] base64 = Base64.getEncoder().encodeToString(input).getBytes(UTF_8);
        for (int i = 0; i < base64.length; i++) {
            switch (base64[i]) {
                case '+':
                    base64[i] = '*';
                    break;
                case '/':
                    base64[i] = '-';
                    break;
                case '=':
                    base64[i] = '_';
                    break;
                default:
                    break;
            }
        }
        return new String(base64, UTF_8);
    }
}

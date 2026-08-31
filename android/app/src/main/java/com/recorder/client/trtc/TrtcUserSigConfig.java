package com.recorder.client.trtc;

/**
 * Public-source placeholder configuration.
 *
 * <p>No long-lived secret is shipped with this project. Production deployments must obtain
 * short-lived UserSig values from a trusted backend rather than embedding a secret in the app.
 */
public final class TrtcUserSigConfig {
    private static final int SDK_APP_ID = 0;
    private static final String SECRET_KEY = "";

    private TrtcUserSigConfig() {
    }

    public static int getSdkAppId() {
        return SDK_APP_ID;
    }

    public static String getSecretKey() {
        return SECRET_KEY;
    }
}

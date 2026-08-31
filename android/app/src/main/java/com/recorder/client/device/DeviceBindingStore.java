package com.recorder.client.device;

import android.content.Context;
import android.content.SharedPreferences;

import com.recorder.client.ble.DiscoveredDevice;

/**
 * 设备绑定信息的本地持久化（SharedPreferences）。
 *
 * <p>「绑定」是纯 App 侧业务概念，设备侧不感知。同一时刻最多绑定一台设备。
 * 绑定的匹配键是设备序列号（广播名「产品名-序列号」的后段），蓝牙地址仅作
 * 记录，不参与身份判断（真实硬件地址可能轮换）。
 */
public final class DeviceBindingStore {

    /** 一条已绑定设备记录。 */
    public static final class Binding {
        public final String productName;
        public final String serial;
        public final String address;
        public final long boundAtMs;

        Binding(String productName, String serial, String address, long boundAtMs) {
            this.productName = productName;
            this.serial = serial;
            this.address = address;
            this.boundAtMs = boundAtMs;
        }
    }

    private static final String PREFS_NAME = "device_binding";
    private static final String KEY_PRODUCT_NAME = "product_name";
    private static final String KEY_SERIAL = "serial";
    private static final String KEY_ADDRESS = "address";
    private static final String KEY_BOUND_AT = "bound_at_ms";

    private final SharedPreferences prefs;

    public DeviceBindingStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean hasBinding() {
        return prefs.contains(KEY_SERIAL);
    }

    /** 当前绑定；未绑定时返回 null。 */
    public Binding get() {
        String serial = prefs.getString(KEY_SERIAL, null);
        if (serial == null) {
            return null;
        }
        return new Binding(
                prefs.getString(KEY_PRODUCT_NAME, ""),
                serial,
                prefs.getString(KEY_ADDRESS, ""),
                prefs.getLong(KEY_BOUND_AT, 0L));
    }

    /** 记录一次绑定（覆盖旧绑定）。 */
    public void save(DiscoveredDevice device) {
        prefs.edit()
                .putString(KEY_PRODUCT_NAME, device.productName())
                .putString(KEY_SERIAL, device.serial())
                .putString(KEY_ADDRESS, device.address())
                .putLong(KEY_BOUND_AT, System.currentTimeMillis())
                .apply();
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}

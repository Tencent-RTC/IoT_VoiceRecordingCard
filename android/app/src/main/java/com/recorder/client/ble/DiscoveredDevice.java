package com.recorder.client.ble;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanResult;
import android.content.Context;

/**
 * 扫描发现的一台录音笔设备。
 *
 * <p>设备广播名约定为「产品名-序列号」（如 {@code TencentRecorder-36:23:f2:dc:38:c0}）。
 * 序列号是区分量产设备的业务身份，也是设备绑定后的匹配键；蓝牙地址仅作记录，
 * 不参与身份判断（真实硬件地址可能轮换）。
 *
 * <p>解析以广播名中最后一个 '-' 分割：前段为产品名、后段为序列号。对不含 '-'
 * 的广播名（如未按约定命名的 mock 设备），产品名取整个广播名、序列号回退为
 * 蓝牙地址，保证 UI 始终有可展示内容。
 */
public final class DiscoveredDevice {

    private final BluetoothDevice bluetoothDevice;
    private final String advertisedName;
    private final String productName;
    private final String serial;
    private final String address;

    private DiscoveredDevice(BluetoothDevice bluetoothDevice, String advertisedName,
                             String productName, String serial, String address) {
        this.bluetoothDevice = bluetoothDevice;
        this.advertisedName = advertisedName;
        this.productName = productName;
        this.serial = serial;
        this.address = address;
    }

    /** 从一次扫描结果构造；广播名缺失时回退为蓝牙地址，保证序列号非空。 */
    public static DiscoveredDevice from(ScanResult result) {
        BluetoothDevice device = result.getDevice();
        String name = result.getScanRecord() == null
                ? null : result.getScanRecord().getDeviceName();
        String address = device.getAddress();
        return from(device, name, address);
    }

    /** 以已知广播名与地址构造（供广播名缺失时从 {@link BluetoothDevice} 兜底）。 */
    public static DiscoveredDevice from(BluetoothDevice device, String advertisedName,
                                        String address) {
        String product;
        String serial;
        if (advertisedName == null || advertisedName.isEmpty()) {
            product = "(未知设备)";
            serial = address;
        } else {
            int split = advertisedName.lastIndexOf('-');
            if (split > 0 && split < advertisedName.length() - 1) {
                product = advertisedName.substring(0, split);
                serial = advertisedName.substring(split + 1);
            } else {
                // 广播名不符合「产品名-序列号」约定：整体作为产品名，地址兜底序列号。
                product = advertisedName;
                serial = address;
            }
        }
        return new DiscoveredDevice(device, advertisedName, product, serial, address);
    }

    public BluetoothDevice bluetoothDevice() {
        return bluetoothDevice;
    }

    public String advertisedName() {
        return advertisedName;
    }

    public String productName() {
        return productName;
    }

    /** 设备序列号：绑定后的身份匹配键。 */
    public String serial() {
        return serial;
    }

    public String address() {
        return address;
    }

    /** 序列号相等（忽略大小写）即视为同一台设备。 */
    public boolean sameDevice(DiscoveredDevice other) {
        return other != null && serial != null && serial.equalsIgnoreCase(other.serial);
    }

    /** 包装为可连接的 {@link BleDevice}（构造器包私有，经本工厂方法对外暴露）。 */
    public BleDevice toBleDevice(Context context) {
        return new BleDevice(context, bluetoothDevice);
    }
}

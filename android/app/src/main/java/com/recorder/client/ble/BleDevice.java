package com.recorder.client.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.Context;

import com.recorder.transport.FrameLink;

/**
 * 一台被发现的蓝牙录音笔设备。
 *
 * <p>由 {@link BleDeviceScanner} 在扫描命中后创建。调用 {@link #connect()}
 * 即可与该设备建立 GATT 连接；连接的内部状态（{@link ClientFrameLink}）
 * 保存在本类内部，对业务层代码不可见。
 *
 * <p>本类实现 {@link FrameLink} 并委托给内部链路，因此启动
 * {@code ReliableTransport} 时可将本实例直接作为参数传入，
 * 表示与「该蓝牙设备」进行可靠传输。连接尚未建立时，
 * {@link #isLinkConnected()} 为 false、{@link #writeFrame(byte[])} 返回 false，
 * 与传输层「先 start、后等待链路就绪」的时序天然兼容。
 */
public final class BleDevice implements FrameLink {

    /** 连接级事件：GATT 建链、MTU 协商、断连。 */
    public interface Events {
        void onLog(String message);

        void onConnected();

        void onMtuChanged(int mtu);

        void onDisconnected(String reason);
    }

    private final BluetoothDevice device;
    private final ClientFrameLink link;
    private final String name;
    private final String address;

    private volatile Events events;

    @SuppressLint("MissingPermission")
    BleDevice(Context context, BluetoothDevice device) {
        this.device = device;
        String n;
        try {
            n = device.getName();
        } catch (SecurityException e) {
            n = null;
        }
        this.name = n == null ? "(未知设备)" : n;
        this.address = device.getAddress();
        this.link = new ClientFrameLink(context, new ClientFrameLink.Events() {
            @Override
            public void onLog(String message) {
                Events e = events;
                if (e != null) {
                    e.onLog(message);
                }
            }

            @Override
            public void onConnected() {
                Events e = events;
                if (e != null) {
                    e.onConnected();
                }
            }

            @Override
            public void onMtuChanged(int mtu) {
                Events e = events;
                if (e != null) {
                    e.onMtuChanged(mtu);
                }
            }

            @Override
            public void onDisconnected(String reason) {
                Events e = events;
                if (e != null) {
                    e.onDisconnected(reason);
                }
            }
        });
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    /** 安装连接级事件回调；必须在 {@link #connect()} 之前调用。 */
    public void setEvents(Events events) {
        this.events = events;
    }

    /** 与该设备建立 GATT 连接（服务发现、MTU 协商、Notify 订阅自动进行）。 */
    public void connect() {
        link.connect(device);
    }

    // ==================== FrameLink（委托给内部连接状态） ====================

    @Override
    public void setCallback(Callback callback) {
        link.setCallback(callback);
    }

    @Override
    public boolean writeFrame(byte[] frame) {
        return link.writeFrame(frame);
    }

    @Override
    public boolean canAcceptWrite() {
        return link.canAcceptWrite();
    }

    @Override
    public int getAttPayloadSize() {
        return link.getAttPayloadSize();
    }

    @Override
    public boolean isLinkConnected() {
        return link.isLinkConnected();
    }

    @Override
    public void disconnect() {
        link.disconnect();
    }
}

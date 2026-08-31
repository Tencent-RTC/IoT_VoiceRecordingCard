package com.recorder.transport;

import java.util.UUID;

/** BLE 参数。不同链路实现共用，避免两端写错。 */
public final class BleUuids {

    public static final UUID SERVICE =
            UUID.fromString("0000ffe0-65d0-4e20-b56a-e493541ba4e2");

    /** recorder_tx：设备 → App，notify。 */
    public static final UUID CHAR_TX =
            UUID.fromString("0000ffe8-65d0-4e20-b56a-e493541ba4e2");

    /** recorder_rx：App → 设备，write without response。 */
    public static final UUID CHAR_RX =
            UUID.fromString("0000ffe9-65d0-4e20-b56a-e493541ba4e2");

    public static final UUID CCCD =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BleUuids() {
    }
}

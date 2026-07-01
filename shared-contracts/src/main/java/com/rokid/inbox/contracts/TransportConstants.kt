package com.rokid.inbox.contracts

object TransportConstants {
    const val BLUETOOTH_SERVICE_NAME = "RokidInboxBT"
    // Fresh UUIDs so the Inbox link never collides with other Rokid apps on air.
    const val SPP_UUID = "b1e9a3c2-2f7a-4a1e-9c3d-8a6f2e5d7c10"
    const val BLE_SERVICE_UUID = "b1e9a3d0-2f7a-4a1e-9c3d-8a6f2e5d7c10"
    const val BLE_RX_CHARACTERISTIC_UUID = "b1e9a3d1-2f7a-4a1e-9c3d-8a6f2e5d7c10"
    const val BLE_TX_CHARACTERISTIC_UUID = "b1e9a3d2-2f7a-4a1e-9c3d-8a6f2e5d7c10"
    // Rokid CXR service channel names (defined by the CXR bridge, kept as-is).
    const val CXR_PHONE_TO_GLASSES_COMMAND = "rk_custom_client"
    const val CXR_GLASSES_TO_PHONE_COMMAND = "rk_custom_key"
    const val PROTOCOL_VERSION = 3
}

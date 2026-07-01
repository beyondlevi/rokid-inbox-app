package com.rokid.inbox.glasses.transport

import android.content.Context
import com.rokid.inbox.contracts.ConnectionState
import com.rokid.inbox.contracts.GlassesToPhoneMessage
import com.rokid.inbox.contracts.PhoneToGlassesMessage
import com.rokid.inbox.glasses.bluetooth.BleCentralBridge
import com.rokid.inbox.glasses.bluetooth.SppBridge
import com.rokid.inbox.glasses.cxr.CxrBridge
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Fans a single logical link out to the three transports (CXR-L, BLE GATT
 * central, Bluetooth SPP) and prefers whichever finishes the protocol handshake
 * first. Audio streaming prefers CXR/SPP over BLE.
 */
class HybridBridge(
    context: Context,
) : PhoneBridge {
    private val cxrBridge = CxrBridge(context)
    private val bleCentralBridge = BleCentralBridge(context)
    private val sppBridge = SppBridge(context)
    private val listeners = CopyOnWriteArraySet<(PhoneToGlassesMessage) -> Unit>()

    @Volatile private var active = false
    @Volatile private var preferBleCentral = false
    @Volatile private var preferCxr = false

    init {
        cxrBridge.subscribe(::handleCxrMessage)
        bleCentralBridge.subscribe(::handleBleCentralMessage)
        sppBridge.subscribe(::handleSppMessage)
    }

    fun resume() {
        active = true
        cxrBridge.resume()
        bleCentralBridge.resume()
        sppBridge.resume()
    }

    fun pause() {
        active = false
        cxrBridge.pause()
        bleCentralBridge.pause()
        sppBridge.pause()
    }

    fun hibernate() {
        active = false
        cxrBridge.pause()
        bleCentralBridge.hibernate()
        sppBridge.hibernate()
    }

    fun close() {
        active = false
        cxrBridge.close()
        bleCentralBridge.close()
        sppBridge.close()
    }

    override fun send(message: GlassesToPhoneMessage) {
        if (preferCxr && cxrBridge.isProtocolReady) {
            cxrBridge.send(message)
        } else if (bleCentralBridge.isProtocolReady) {
            bleCentralBridge.send(message)
        } else {
            sppBridge.send(message)
        }
    }

    override fun subscribe(listener: (PhoneToGlassesMessage) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private fun handleCxrMessage(message: PhoneToGlassesMessage) {
        if (preferBleCentral && bleCentralBridge.isProtocolReady) return
        val state = (message as? PhoneToGlassesMessage.Status)?.status?.connectionState
        if (state == ConnectionState.CONNECTED) {
            preferCxr = true
        } else if (state == ConnectionState.DISCONNECTED || state == ConnectionState.CONNECTING) {
            if (preferCxr && !cxrBridge.isProtocolReady) {
                preferCxr = false
                if (active) sppBridge.resume()
            }
        }
        listeners.forEach { it(message) }
    }

    private fun handleBleCentralMessage(message: PhoneToGlassesMessage) {
        val state = (message as? PhoneToGlassesMessage.Status)?.status?.connectionState
        if (state == ConnectionState.CONNECTED) {
            preferBleCentral = true
            preferCxr = false
            cxrBridge.pause()
            sppBridge.pause()
        } else if (state == ConnectionState.DISCONNECTED || state == ConnectionState.CONNECTING) {
            if (preferBleCentral && !bleCentralBridge.isProtocolReady) {
                preferBleCentral = false
                if (active) {
                    cxrBridge.resume()
                    sppBridge.resume()
                }
            }
        }
        listeners.forEach { it(message) }
    }

    private fun handleSppMessage(message: PhoneToGlassesMessage) {
        if ((preferBleCentral || preferCxr) && message is PhoneToGlassesMessage.Status) return
        listeners.forEach { it(message) }
    }
}

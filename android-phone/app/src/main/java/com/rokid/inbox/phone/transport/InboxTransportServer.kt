package com.rokid.inbox.phone.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.rokid.inbox.contracts.ConnectionState
import com.rokid.inbox.contracts.GlassesToPhoneMessage
import com.rokid.inbox.contracts.LocaleManager
import com.rokid.inbox.contracts.PhoneToGlassesMessage
import com.rokid.inbox.contracts.ProtocolHelloAck
import com.rokid.inbox.contracts.TransportConstants
import com.rokid.inbox.contracts.WireProtocol
import com.rokid.inbox.phone.InboxPhoneStateStore
import com.rokid.inbox.phone.R
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bluetooth Classic SPP server. Accepts the glasses link, does the versioned
 * handshake, forwards inbox messages to the controller, and broadcasts phone
 * replies.
 */
class InboxTransportServer(
    private val appContext: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val appVersion: String,
    private val stateStore: InboxPhoneStateStore,
    private val onMessage: (GlassesToPhoneMessage) -> Unit,
    private val onClientReady: () -> Unit,
) {
    private val clients = CopyOnWriteArrayList<ClientSession>()
    @Volatile private var running = false
    @Volatile private var serverSocket: BluetoothServerSocket? = null

    /** User-facing status string in the currently selected language. */
    private fun str(resId: Int, vararg args: Any): String =
        LocaleManager.wrap(appContext).getString(resId, *args)

    private class ClientSession(
        val socket: BluetoothSocket,
        val writer: BufferedWriter,
        @Volatile var handshakeComplete: Boolean = false,
    )

    @Synchronized
    fun startServing() {
        if (running) return
        running = true
        updateStatus(str(R.string.status_server_starting))
        startServerLoop()
    }

    @Synchronized
    fun stopServing() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.forEach { runCatching { it.socket.close() } }
        clients.clear()
        updateStatus(str(R.string.status_server_stopped))
    }

    fun broadcast(message: PhoneToGlassesMessage) {
        val dead = mutableListOf<ClientSession>()
        clients.forEach { session ->
            if (!session.handshakeComplete) return@forEach
            if (!send(session.writer, message)) dead += session
        }
        dead.forEach { disconnect(it) }
        if (dead.isNotEmpty()) updateStatus(str(R.string.status_glasses_disconnected_wait))
    }

    @SuppressLint("MissingPermission")
    private fun startServerLoop() {
        Thread {
            val adapter = bluetoothAdapter
            if (adapter == null) {
                running = false
                val msg = str(R.string.status_adapter_unavailable)
                updateStatus(msg, msg)
                return@Thread
            }
            val uuid = UUID.fromString(TransportConstants.SPP_UUID)
            while (running) {
                try {
                    serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(
                        TransportConstants.BLUETOOTH_SERVICE_NAME,
                        uuid,
                    )
                    updateStatus(str(R.string.status_server_ready))
                    while (running) {
                        val client = serverSocket?.accept() ?: break
                        val writer = BufferedWriter(OutputStreamWriter(client.outputStream, Charsets.UTF_8))
                        val session = ClientSession(client, writer)
                        clients += session
                        updateStatus(str(R.string.status_glasses_connecting))
                        startReader(session)
                    }
                } catch (t: Throwable) {
                    if (running) updateStatus(str(R.string.status_server_error, t.message ?: str(R.string.error_unknown)), t.message)
                } finally {
                    runCatching { serverSocket?.close() }
                    serverSocket = null
                }
                if (running) Thread.sleep(RESTART_DELAY_MS)
            }
        }.start()
    }

    private fun startReader(session: ClientSession) {
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(session.socket.inputStream, Charsets.UTF_8))
                while (running) {
                    val line = reader.readLine() ?: break
                    val message = WireProtocol.decodeGlassesMessageOrNull(line)
                    if (message == null) {
                        disconnect(session, str(R.string.status_glasses_invalid))
                        return@Thread
                    }
                    if (message is GlassesToPhoneMessage.Hello) {
                        if (!completeHandshake(session, message.hello.protocolVersion)) return@Thread
                        continue
                    }
                    if (!session.handshakeComplete) {
                        disconnect(session, str(R.string.status_incompatible_protocol))
                        return@Thread
                    }
                    onMessage(message)
                }
            } catch (_: Exception) {
            } finally {
                disconnect(session, str(R.string.status_glasses_disconnected_bt))
            }
        }.start()
    }

    private fun completeHandshake(session: ClientSession, remoteVersion: Int): Boolean {
        if (remoteVersion != TransportConstants.PROTOCOL_VERSION) {
            send(session.writer, PhoneToGlassesMessage.Error(str(R.string.status_incompatible_version)))
            disconnect(session, str(R.string.status_incompatible_version))
            return false
        }
        session.handshakeComplete = true
        val ack = PhoneToGlassesMessage.HelloAck(
            ProtocolHelloAck(
                protocolVersion = TransportConstants.PROTOCOL_VERSION,
                appVersion = appVersion,
                capabilities = PHONE_CAPABILITIES,
            ),
        )
        if (!send(session.writer, ack)) {
            disconnect(session, str(R.string.status_handshake_failed))
            return false
        }
        updateStatus(str(R.string.status_glasses_connected_bt))
        onClientReady()
        return true
    }

    private fun send(writer: BufferedWriter, message: PhoneToGlassesMessage): Boolean = try {
        writer.write(WireProtocol.encodePhoneMessage(message))
        writer.newLine()
        writer.flush()
        true
    } catch (_: Exception) {
        false
    }

    private fun disconnect(session: ClientSession, statusMessage: String? = null, lastError: String? = null) {
        val removed = clients.remove(session)
        runCatching { session.socket.close() }
        if (removed && statusMessage != null) updateStatus(statusMessage, lastError)
    }

    private fun connectedCount(): Int = clients.count { it.handshakeComplete }

    private fun updateStatus(label: String, lastError: String? = null) {
        val connected = connectedCount()
        stateStore.updateStatus { current ->
            current.copy(
                connectionState = when {
                    connected > 0 -> ConnectionState.CONNECTED
                    running -> ConnectionState.CONNECTING
                    else -> ConnectionState.DISCONNECTED
                },
                bluetoothClientCount = connected,
                statusLabel = label,
                lastError = lastError,
            )
        }
    }

    private companion object {
        private const val RESTART_DELAY_MS = 2500L
        private val PHONE_CAPABILITIES = listOf("inbox", "voice_reply", "voice_search", "send_text")
    }
}

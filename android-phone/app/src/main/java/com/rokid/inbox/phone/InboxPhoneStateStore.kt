package com.rokid.inbox.phone

import android.os.Handler
import android.os.Looper
import com.rokid.inbox.contracts.ChannelKind
import com.rokid.inbox.contracts.ConnectionState
import com.rokid.inbox.contracts.DeviceStatus

data class BoxSummary(
    val id: String,
    val kind: ChannelKind,
    val name: String,
    val label: String,
)

data class InboxPhoneViewState(
    val deviceStatus: DeviceStatus = DeviceStatus(
        connectionState = ConnectionState.CONNECTING,
        statusLabel = "Bluetooth server starting...",
    ),
    val boxes: List<BoxSummary> = emptyList(),
    val openAiConfigured: Boolean = false,
)

class InboxPhoneStateStore {
    private val lock = Any()
    private val listeners = linkedSetOf<(InboxPhoneViewState) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var state = InboxPhoneViewState()

    fun current(): InboxPhoneViewState = state

    fun subscribe(listener: (InboxPhoneViewState) -> Unit): () -> Unit {
        val snapshot = synchronized(lock) {
            listeners += listener
            state
        }
        dispatch(listener, snapshot)
        return { synchronized(lock) { listeners -= listener } }
    }

    fun updateStatus(transform: (DeviceStatus) -> DeviceStatus) {
        update { it.copy(deviceStatus = transform(it.deviceStatus)) }
    }

    fun updateBoxes(boxes: List<BoxSummary>, openAiConfigured: Boolean) {
        update { it.copy(boxes = boxes, openAiConfigured = openAiConfigured) }
    }

    private fun update(transform: (InboxPhoneViewState) -> InboxPhoneViewState) {
        var next: InboxPhoneViewState? = null
        val snapshot = synchronized(lock) {
            val n = transform(state)
            if (n == state) return
            state = n
            next = n
            listeners.toList()
        }
        val dispatched = next ?: return
        snapshot.forEach { dispatch(it, dispatched) }
    }

    private fun dispatch(listener: (InboxPhoneViewState) -> Unit, state: InboxPhoneViewState) {
        if (Looper.myLooper() == Looper.getMainLooper()) listener(state)
        else mainHandler.post { listener(state) }
    }
}

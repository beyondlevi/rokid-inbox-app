package com.rokid.inbox.phone

import android.bluetooth.BluetoothManager
import android.content.Context
import com.rokid.inbox.contracts.ChannelKind
import com.rokid.inbox.phone.channels.ChannelService
import com.rokid.inbox.phone.channels.GitHubService
import com.rokid.inbox.phone.channels.GmailService
import com.rokid.inbox.phone.channels.TelegramService
import com.rokid.inbox.phone.channels.WhatsAppService
import com.rokid.inbox.phone.transport.InboxTransportServer
import com.rokid.inbox.phone.voice.WhisperClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Wires the phone runtime: config, channels, Whisper, controller, transport. */
object InboxGraph {
    val stateStore = InboxPhoneStateStore()

    @Volatile private var initialized = false
    private val scope = CoroutineScope(SupervisorJob())

    lateinit var configStore: InboxConfigStore
        private set
    private lateinit var appContext: Context
    private lateinit var controller: InboxController
    private lateinit var server: InboxTransportServer

    @Volatile private var services: List<ChannelService> = emptyList()
    @Volatile private var whisper: WhisperClient = WhisperClient("")

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        configStore = InboxConfigStore(appContext)
        reloadFromConfig()

        controller = InboxController(
            servicesProvider = { services },
            whisperProvider = { whisper },
            quickProvider = { configStore.getQuickMessages() },
            scope = scope,
        )
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        server = InboxTransportServer(
            bluetoothAdapter = adapter,
            appVersion = appVersionName(appContext),
            stateStore = stateStore,
            onMessage = controller::handle,
            onClientReady = controller::onClientConnected,
        )
        controller.sender = server::broadcast
        initialized = true
    }

    @Synchronized
    fun start(context: Context) {
        initialize(context)
        server.startServing()
        refreshBoxesState()
    }

    @Synchronized
    fun destroy() {
        if (!initialized) return
        runCatching { server.stopServing() }
        initialized = false
    }

    /** Rebuild channel services + Whisper after the settings screen changes config. */
    @Synchronized
    fun reloadFromConfig() {
        val previous = services
        whisper = WhisperClient(configStore.getOpenAiKey())
        services = instantiateServices()
        if (previous.isNotEmpty()) {
            scope.launch(Dispatchers.IO) { previous.forEach { runCatching { it.disconnect() } } }
        }
        refreshBoxesState()
    }

    private fun instantiateServices(): List<ChannelService> =
        configStore.getBoxes().mapNotNull { box ->
            runCatching {
                when (box.kind) {
                    ChannelKind.WHATSAPP -> WhatsAppService(
                        boxId = box.id,
                        serverUrl = box.config["serverUrl"].orEmpty(),
                        instance = box.config["instance"].orEmpty(),
                        apiKey = box.config["apiKey"].orEmpty(),
                    )
                    ChannelKind.GITHUB -> GitHubService(
                        boxId = box.id,
                        token = box.config["token"].orEmpty(),
                        query = box.config["query"].orEmpty(),
                    )
                    ChannelKind.TELEGRAM -> TelegramService(
                        boxId = box.id,
                        serverUrl = box.config["serverUrl"].orEmpty(),
                        apiKey = box.config["apiKey"].orEmpty(),
                    )
                    ChannelKind.GMAIL -> GmailService(
                        boxId = box.id,
                        clientId = box.config["clientId"].orEmpty(),
                        clientSecret = box.config["clientSecret"].orEmpty(),
                        refreshToken = box.config["refreshToken"].orEmpty(),
                    )
                }
            }.getOrNull()
        }

    private fun refreshBoxesState() {
        val labels = InboxController.computeBoxLabels(services)
        val summaries = configStore.getBoxes().map { box ->
            BoxSummary(
                id = box.id,
                kind = box.kind,
                name = box.name,
                label = labels[box.id] ?: "[${InboxController.glyph(box.kind)}]",
            )
        }
        stateStore.updateBoxes(summaries, configStore.getOpenAiKey().isNotBlank())
    }

    private fun appVersionName(context: Context): String =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "unknown" }
}

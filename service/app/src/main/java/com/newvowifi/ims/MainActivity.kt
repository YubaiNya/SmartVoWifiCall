package com.newvowifi.ims

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.newvowifi.ims.policy.PolicyEngine
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Phone
import top.yukonga.miuix.kmp.icon.extended.WorldClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {
    private lateinit var rt: RuntimeController
    private var uiState by mutableStateOf(AppUiState())
    private var overviewFeedback by mutableStateOf("")
    private var clashFeedback by mutableStateOf("")
    private var connectionsFeedback by mutableStateOf("")
    private var connectionsSnapshot by mutableStateOf(RuntimeController.ConnectionsSnapshot.EMPTY)

    private val audioPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            rt.setBillable(granted)
            overviewFeedback = if (granted) "已启用通话与短信功能" else "未授予麦克风权限，保持验证模式"
            syncStateFromController()
        }

    private val importYamlLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                importYamlFromUri(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        rt = RuntimeController.get(this)
        startService(Intent(this, PolicyService::class.java))
        syncStateFromController()

        setContent {
            NewVoWiFiTheme {
                MainShell(
                    state = uiState,
                    overviewFeedback = overviewFeedback,
                    clashFeedback = clashFeedback,
                    connectionsFeedback = connectionsFeedback,
                    onPollController = { syncStateFromController() },
                    onPollConnections = { pollConnections() },
                    onToggleEnabled = { on ->
                        rt.setEnabled(on)
                        overviewFeedback = if (on) "正在切换至 Wi-Fi Calling 模式…" else "正在恢复蜂窝模式…"
                        syncStateFromController()
                    },
                    onRequestBillableToggle = { on -> handleBillableToggle(on) },
                    onSelectSimInterface = { simKey, iface ->
                        rt.select(simKey, iface)
                        overviewFeedback = "已将出口切换为 $iface"
                        syncStateFromController()
                    },
                    onUseBuiltinTun = {
                        rt.useBuiltinTunForForeignSims()
                        overviewFeedback = "已将海外卡出口设为 ${com.newvowifi.ims.clash.ClashConfig.TUN_DEVICE}"
                        clashFeedback = "已将海外卡出口设为 ${com.newvowifi.ims.clash.ClashConfig.TUN_DEVICE}"
                        syncStateFromController()
                    },
                    onRefreshAll = {
                        rt.kick()
                        rt.io.execute {
                            rt.refreshClashState()
                            runOnUiThread { syncStateFromController() }
                        }
                    },
                    onToggleClash = { on ->
                        rt.setClashEnabled(on)
                        clashFeedback = if (on) "正在启动内置 Clash…" else "正在停止内置 Clash…"
                        syncStateFromController()
                    },
                    onFetchSubscription = { url ->
                        if (url.isEmpty()) {
                            clashFeedback = "请先输入订阅链接"
                        } else {
                            clashFeedback = "正在拉取订阅…"
                            rt.clashFetchSubscription(url) { msg ->
                                runOnUiThread {
                                    clashFeedback = msg
                                    syncStateFromController()
                                }
                            }
                        }
                    },
                    onImportYaml = {
                        importYamlLauncher.launch(arrayOf("*/*"))
                    },
                    onReloadLocal = {
                        clashFeedback = "正在重载本地 profile.yaml…"
                        rt.clashReloadLocal { msg ->
                            runOnUiThread {
                                clashFeedback = msg
                                syncStateFromController()
                            }
                        }
                    },
                    onSelectNode = { node ->
                        clashFeedback = "正在切换出口节点：$node…"
                        rt.clashSelectNode(node) { msg ->
                            runOnUiThread {
                                clashFeedback = msg
                                syncStateFromController()
                            }
                        }
                    },
                    onTestDelay = {
                        clashFeedback = "正在测速全部节点…"
                        rt.clashTestDelay { msg ->
                            runOnUiThread {
                                clashFeedback = msg
                                syncStateFromController()
                            }
                        }
                    },
                    onRestartCore = {
                        clashFeedback = "正在重启内置 Clash 内核…"
                        rt.clashRestartCore { msg ->
                            runOnUiThread {
                                clashFeedback = msg
                                syncStateFromController()
                            }
                        }
                    },
                    onCloseConnection = { id ->
                        rt.clashCloseConnection(id) { msg ->
                            runOnUiThread {
                                connectionsFeedback = msg
                                pollConnections()
                            }
                        }
                    },
                    onCloseAllConnections = {
                        rt.clashCloseAllConnections { msg ->
                            runOnUiThread {
                                connectionsFeedback = msg
                                pollConnections()
                            }
                        }
                    },
                    onUpdateController = { lan, port ->
                        connectionsFeedback = "正在应用外部控制器配置…"
                        rt.clashSetController(lan, port) { msg ->
                            runOnUiThread {
                                connectionsFeedback = msg
                                syncStateFromController()
                            }
                        }
                    },
                    onRotateSecret = {
                        connectionsFeedback = "正在重置控制器密钥…"
                        rt.clashRotateSecret { msg ->
                            runOnUiThread {
                                connectionsFeedback = msg
                                syncStateFromController()
                            }
                        }
                    },
                    onCopyText = { label, value ->
                        val cm = getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText(label, value))
                        connectionsFeedback = "已复制$label"
                    }
                )
            }
        }
    }

    private fun handleBillableToggle(on: Boolean) {
        if (!on) {
            rt.setBillable(false)
            overviewFeedback = "已切换为验证模式（通话与短信发送已关闭）"
            syncStateFromController()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("启用通话与短信")
            .setMessage("开启后，系统拨号器和短信应用可通过此服务使用运营商业务，费用按套餐收取。开发验证期间建议保持关闭。")
            .setPositiveButton("启用") { _, _ ->
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    rt.setBillable(true)
                    overviewFeedback = "已启用通话与短信功能"
                    syncStateFromController()
                }
            }
            .setNegativeButton("保持关闭") { _, _ ->
                syncStateFromController()
            }
            .setOnCancelListener {
                syncStateFromController()
            }
            .show()
    }

    private fun importYamlFromUri(uri: Uri) {
        clashFeedback = "正在读取 YAML 文件…"
        rt.io.execute {
            try {
                contentResolver.openInputStream(uri).use { input ->
                    if (input == null) throw IOException("OPEN_FAILED")
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = input.read(chunk)
                        if (n == -1) break
                        if (buf.size() + n > 4 * 1024 * 1024) throw IOException("CLASH_INPUT_TOO_LARGE")
                        buf.write(chunk, 0, n)
                    }
                    val yaml = String(buf.toByteArray(), StandardCharsets.UTF_8)
                    rt.clashSaveProfile(yaml) { msg ->
                        runOnUiThread {
                            clashFeedback = msg
                            syncStateFromController()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    clashFeedback = "读取文件失败：${RuntimeController.safeCode(e)}"
                }
            }
        }
    }

    private fun pollConnections() {
        rt.clashFetchConnections { snap ->
            runOnUiThread {
                connectionsSnapshot = snap
                syncStateFromController()
            }
        }
    }

    private fun syncStateFromController() {
        val snap = rt.snapshot
        val inv = rt.interfaces
        val cs = rt.clash
        val conns = connectionsSnapshot

        val simList = mutableListOf<SimCardUi>()
        if (snap != null) {
            for (s in snap.sims) {
                val elig = PolicyEngine.eligibility(s.policy) == PolicyEngine.Reason.ELIGIBLE
                val mainland = s.policy.homePlmn.startsWith("460") || s.policy.homePlmn.startsWith("461")
                val b = rt.bindings[s.policy.slotId]
                simList.add(
                    SimCardUi(
                        slotId = s.policy.slotId,
                        subId = s.policy.subId,
                        simKey = s.policy.simKey,
                        homePlmn = s.policy.homePlmn,
                        ready = s.policy.ready,
                        foreignEligible = elig,
                        mainland = mainland,
                        selectedInterface = rt.selection(s.policy.simKey),
                        radioOff = java.lang.Boolean.TRUE == rt.radioOff[s.policy.slotId],
                        featureAttached = rt.features.containsKey(s.policy.slotId),
                        providerBound = b?.providerBound == true,
                        imsState = b?.ims ?: "IDLE",
                        registered = b?.registered == true,
                        tunnelState = b?.tunnel ?: "IDLE",
                        voiceServiceOverride = java.lang.Boolean.TRUE == b?.voiceServiceOverride
                    )
                )
            }
        }

        val ifaceList = mutableListOf<InterfaceRowUi>()
        if (inv != null) {
            for (e in inv.entries.values) {
                ifaceList.add(
                    InterfaceRowUi(
                        name = e.name,
                        type = e.type(),
                        up = e.up,
                        available = e.available(),
                        voWifiTun = e.voWifiTun(),
                        addresses = e.addresses.joinToString(", "),
                        label = e.label()
                    )
                )
            }
        }

        val nodeList = cs.nodes.map { n ->
            NodeRowUi(
                name = n.name,
                type = n.type,
                delay = n.delay,
                selected = n.name == cs.currentNode
            )
        }

        val connList = conns.connections.map { c ->
            val chainText = if (c.chains.isEmpty()) "WFC-PROXY"
            else c.chains.asReversed().joinToString(" → ")
            ConnectionRowUi(
                id = c.id,
                network = c.network,
                type = c.type,
                source = c.source,
                destination = c.destination,
                rule = c.rule,
                chainSummary = chainText,
                upload = c.upload,
                download = c.download,
                start = c.start
            )
        }

        uiState = AppUiState(
            enabled = rt.enabled(),
            billable = rt.billable(),
            statusText = rt.status,
            clashEnabled = rt.clashEnabled(),
            clashHasProfile = cs.hasProfile,
            clashRunning = cs.running,
            clashTunUp = cs.tunUp,
            clashCurrentNode = cs.currentNode,
            clashSavedNode = cs.savedNode,
            clashNetnsId = cs.netnsId,
            clashSubscriptionUrl = cs.subscriptionUrl,
            clashExternalLan = cs.externalLan,
            clashControllerPort = cs.controllerPort,
            clashControllerSecret = cs.controllerSecret,
            clashLanIpv4 = cs.lanIpv4,
            clashLastError = cs.lastError,
            preferredVpnInterface = inv?.preferredVpnInterface ?: "",
            sims = simList,
            interfaces = ifaceList,
            nodes = nodeList,
            connectionsRunning = conns.running,
            downloadTotal = conns.downloadTotal,
            uploadTotal = conns.uploadTotal,
            memoryBytes = conns.memory,
            connections = connList,
            connectionsError = conns.error
        )
    }
}

@Composable
private fun MainShell(
    state: AppUiState,
    overviewFeedback: String,
    clashFeedback: String,
    connectionsFeedback: String,
    onPollController: () -> Unit,
    onPollConnections: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onRequestBillableToggle: (Boolean) -> Unit,
    onSelectSimInterface: (String, String) -> Unit,
    onUseBuiltinTun: () -> Unit,
    onRefreshAll: () -> Unit,
    onToggleClash: (Boolean) -> Unit,
    onFetchSubscription: (String) -> Unit,
    onImportYaml: () -> Unit,
    onReloadLocal: () -> Unit,
    onSelectNode: (String) -> Unit,
    onTestDelay: () -> Unit,
    onRestartCore: () -> Unit,
    onCloseConnection: (String) -> Unit,
    onCloseAllConnections: () -> Unit,
    onUpdateController: (Boolean, Int) -> Unit,
    onRotateSecret: () -> Unit,
    onCopyText: (String, String) -> Unit
) {
    val p = LocalApplePalette.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var connectionsPaused by rememberSaveable { mutableStateOf(false) }

    val tabs = remember {
        listOf(
            GlassTabItem("概览", MiuixIcons.Phone),
            GlassTabItem("代理", MiuixIcons.WorldClock),
            GlassTabItem("连接", MiuixIcons.Link),
            GlassTabItem("网络", MiuixIcons.Layers)
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            onPollController()
            delay(2000L)
        }
    }

    LaunchedEffect(selectedTab, connectionsPaused) {
        if (selectedTab == 2 && !connectionsPaused) {
            while (true) {
                onPollConnections()
                delay(1500L)
            }
        }
    }

    val pageBackdrop = rememberLayerBackdrop {
        drawRect(p.canvas)
        drawContent()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.canvas)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(pageBackdrop)
        ) {
            Crossfade(
                targetState = selectedTab,
                animationSpec = tween(durationMillis = 220),
                label = "page-crossfade"
            ) { page ->
                when (page) {
                    0 -> OverviewPage(
                        state = state,
                        actionFeedback = overviewFeedback,
                        onToggleEnabled = onToggleEnabled,
                        onRequestBillableToggle = onRequestBillableToggle,
                        onSelectSimInterface = onSelectSimInterface,
                        onUseBuiltinTunForForeign = onUseBuiltinTun,
                        onRefreshAll = onRefreshAll
                    )
                    1 -> ProxyPage(
                        state = state,
                        clashFeedback = clashFeedback,
                        onToggleClash = onToggleClash,
                        onFetchSubscription = onFetchSubscription,
                        onImportYaml = onImportYaml,
                        onReloadLocal = onReloadLocal,
                        onSelectNode = onSelectNode,
                        onTestDelay = onTestDelay,
                        onRestartCore = onRestartCore,
                        onUseBuiltinTun = onUseBuiltinTun
                    )
                    2 -> ConnectionsPage(
                        state = state,
                        paused = connectionsPaused,
                        feedback = connectionsFeedback,
                        onTogglePause = { connectionsPaused = !connectionsPaused },
                        onRefreshNow = onPollConnections,
                        onCloseConnection = onCloseConnection,
                        onCloseAllConnections = onCloseAllConnections,
                        onUpdateController = onUpdateController,
                        onRotateSecret = onRotateSecret,
                        onCopyText = onCopyText
                    )
                    else -> NetworkPage(
                        state = state,
                        onRefreshAll = onRefreshAll
                    )
                }
            }
        }

        LiquidGlassBottomBar(
            tabs = tabs,
            selectedIndex = selectedTab,
            onSelect = { selectedTab = it },
            pageBackdrop = pageBackdrop,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

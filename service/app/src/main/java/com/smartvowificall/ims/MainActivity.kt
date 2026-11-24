package com.smartvowificall.ims

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.smartvowificall.ims.policy.PolicyEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Phone
import top.yukonga.miuix.kmp.icon.extended.WorldClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private companion object {
        // Keep pending requests exclusive across Activity recreation.
        val connectionRequestInFlight = AtomicBoolean(false)
    }

    private lateinit var rt: RuntimeController
    private var installedVersionName: String = ""
    private var uiState by mutableStateOf(AppUiState())
    private var overviewFeedback by mutableStateOf("")
    private var clashFeedback by mutableStateOf("")
    private var connectionsFeedback by mutableStateOf("")
    private var logFeedback by mutableStateOf("")
    private var pendingExportSanitized: Boolean = true
    private var connectionsSnapshot by mutableStateOf(RuntimeController.ConnectionsSnapshot.EMPTY)

    private val audioPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            rt.setBillable(granted)
            overviewFeedback = if (granted) "已启用通话与短信" else "未授予麦克风权限，通话与短信发送未启用"
            syncStateFromController()
        }

    private val importYamlLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                importYamlFromUri(uri)
            }
        }

    private val exportLogsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
            if (uri != null) {
                exportLogsToUri(uri, pendingExportSanitized)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        rt = RuntimeController.get(this)
        installedVersionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        startService(Intent(this, PolicyService::class.java))
        syncStateFromController()

        setContent {
            SmartVoWifiCallTheme {
                var selectedTab by rememberSaveable { mutableIntStateOf(0) }
                var connectionsPaused by rememberSaveable { mutableStateOf(false) }
                val refreshConnections = {
                    pollConnections { selectedTab == 2 && !connectionsPaused }
                }
                MainShell(
                    state = uiState,
                    lifecycle = lifecycle,
                    selectedTab = selectedTab,
                    connectionsPaused = connectionsPaused,
                    onSelectTab = { selectedTab = it },
                    onToggleConnectionsPause = { connectionsPaused = !connectionsPaused },
                    overviewFeedback = overviewFeedback,
                    clashFeedback = clashFeedback,
                    connectionsFeedback = connectionsFeedback,
                    logFeedback = logFeedback,
                    onPollController = {
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            syncStateFromController()
                        }
                    },
                    onPollConnections = refreshConnections,
                    onToggleEnabled = { on ->
                        rt.setEnabled(on)
                        overviewFeedback = if (on) "正在切换至网络通话…" else "正在恢复蜂窝网络…"
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
                        overviewFeedback = "海外卡已选择内置代理"
                        clashFeedback = "海外卡已选择内置代理"
                        syncStateFromController()
                    },
                    onRefreshAll = {
                        rt.retryNow()
                        rt.io.execute {
                            rt.refreshClashState()
                            runOnUiThread { syncStateFromController() }
                        }
                    },
                    onRunCompatCheck = {
                        logFeedback = "正在运行系统接口兼容性自检…"
                        rt.runCompatCheckAsync { report ->
                            runOnUiThread {
                                logFeedback = report.summary
                                syncStateFromController()
                            }
                        }
                    },
                    onToggleClash = { on ->
                        rt.setClashEnabled(on)
                        clashFeedback = if (on) "已开启自动代理" else "已关闭自动代理"
                        syncStateFromController()
                    },
                    onFetchSubscription = { url ->
                        if (url.isEmpty()) {
                            clashFeedback = "请先输入订阅链接"
                        } else {
                            clashFeedback = "正在更新订阅…"
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
                        clashFeedback = "正在加载本地配置…"
                        rt.clashReloadLocal { msg ->
                            runOnUiThread {
                                clashFeedback = msg
                                syncStateFromController()
                            }
                        }
                    },
                    onSelectNode = { node ->
                        clashFeedback = "正在切换节点：$node…"
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
                        clashFeedback = "正在重启代理…"
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
                                refreshConnections()
                            }
                        }
                    },
                    onCloseAllConnections = {
                        rt.clashCloseAllConnections { msg ->
                            runOnUiThread {
                                connectionsFeedback = msg
                                refreshConnections()
                            }
                        }
                    },
                    onUpdateController = { lan, port ->
                        connectionsFeedback = "正在保存控制设置…"
                        rt.clashSetController(lan, port) { msg ->
                            runOnUiThread {
                                connectionsFeedback = msg
                                syncStateFromController()
                            }
                        }
                    },
                    onRotateSecret = {
                        connectionsFeedback = "正在重置访问密钥…"
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
                    },
                    onToggleTelemetry = { on ->
                        rt.setTelemetryEnabled(on)
                        logFeedback = if (on) "已开启脱敏遥测上传" else "已关闭遥测上传"
                        syncStateFromController()
                    },
                    onSelectTelemetryRoute = { route ->
                        rt.setTelemetryRoute(route)
                        logFeedback = if (route == RuntimeController.TELEMETRY_ROUTE_SYSTEM) {
                            "已切换遥测上传链路：系统路由"
                        } else {
                            "已切换遥测上传链路：Wi-Fi Calling 链路"
                        }
                        syncStateFromController()
                    },
                    onSaveTelemetryEndpoint = { url ->
                        rt.setTelemetryEndpoint(url) { msg ->
                            runOnUiThread {
                                logFeedback = msg
                                syncStateFromController()
                            }
                        }
                    },
                    onUploadTelemetryNow = {
                        logFeedback = "正在上传脱敏遥测日志…"
                        rt.uploadTelemetryNow("manual") { msg ->
                            runOnUiThread {
                                logFeedback = msg
                                syncStateFromController()
                            }
                        }
                    },
                    onPreviewTelemetry = {
                        showTelemetryPreviewDialog()
                    },
                    onCopyLogId = { id ->
                        val cm = getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("日志 ID", id))
                        logFeedback = "已复制日志 ID：$id（提供给开发者即可查询）"
                    },
                    onExportSanitizedLogs = {
                        pendingExportSanitized = true
                        exportLogsLauncher.launch("smartvowificall-logs-sanitized.zip")
                    },
                    onExportRawLogs = {
                        confirmRawLogExport()
                    }
                )
            }
        }
    }

    private fun handleBillableToggle(on: Boolean) {
        if (!on) {
            rt.setBillable(false)
            overviewFeedback = "已关闭通话与短信发送，仅检查连接"
            syncStateFromController()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("启用通话与短信")
            .setMessage("启用后可使用系统电话和短信，费用按运营商套餐收取。仅测试连接时请保持关闭。")
            .setPositiveButton("启用") { _, _ ->
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    rt.setBillable(true)
                    overviewFeedback = "已启用通话与短信"
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

    private fun showTelemetryPreviewDialog() {
        logFeedback = "正在收集脱敏日志预览…"
        rt.buildSanitizedTelemetryPreviewAsync { pretty ->
            runOnUiThread {
                logFeedback = ""
                val display = if (pretty.length > 48000) {
                    pretty.substring(0, 48000) + "\n\n...(预览仅展示前 48KB，共 ${pretty.length / 1024} KB；点“复制”可复制完整内容)..."
                } else {
                    pretty
                }
                AlertDialog.Builder(this)
                    .setTitle("脱敏遥测内容预览（[Hidden] 为已隐藏隐私）")
                    .setMessage(display)
                    .setPositiveButton("关闭", null)
                    .setNeutralButton("复制") { _, _ ->
                        val cm = getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("遥测预览", pretty))
                        logFeedback = "已复制完整脱敏遥测 JSON"
                    }
                    .show()
            }
        }
    }

    private fun confirmRawLogExport() {
        AlertDialog.Builder(this)
            .setTitle("导出原始全部日志")
            .setMessage("原始日志可能包含 SIM 信息、网络地址与运行细节，仅限本地排查问题，请勿直接公开分享。确定导出吗？")
            .setPositiveButton("导出原始日志") { _, _ ->
                pendingExportSanitized = false
                exportLogsLauncher.launch("smartvowificall-logs-raw.zip")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportLogsToUri(uri: Uri, sanitizedOnly: Boolean) {
        logFeedback = if (sanitizedOnly) "正在打包脱敏日志…" else "正在打包全部原始日志…"
        val stream = try {
            contentResolver.openOutputStream(uri)
        } catch (e: Exception) {
            logFeedback = "无法创建导出文件：${RuntimeController.safeCode(e)}"
            return
        }
        if (stream == null) {
            logFeedback = "无法打开导出文件"
            return
        }
        rt.exportFullLogs(sanitizedOnly, stream) { msg ->
            runOnUiThread {
                logFeedback = msg
                syncStateFromController()
            }
        }
    }

    private fun importYamlFromUri(uri: Uri) {
        clashFeedback = "正在读取配置文件…"
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

    private fun pollConnections(isPageActive: () -> Boolean) {
        val canRefresh = {
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                isPageActive() && rt.clash.running
        }
        if (!canRefresh() || !connectionRequestInFlight.compareAndSet(false, true)) return
        try {
            rt.clashFetchConnections { snap ->
                runOnUiThread {
                    try {
                        if (canRefresh()) {
                            connectionsSnapshot = snap
                            syncStateFromController()
                        }
                    } finally {
                        connectionRequestInFlight.set(false)
                    }
                }
            }
        } catch (e: Exception) {
            connectionRequestInFlight.set(false)
            connectionsFeedback = "读取连接失败：${RuntimeController.safeCode(e)}"
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
                val rawBinding = rt.bindings[s.policy.slotId]
                val b = if (rt.bindingMatches(rawBinding, s)) rawBinding else null
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
                        featureAttached = rt.featureMatches(s),
                        providerBound = b?.providerBound == true,
                        imsState = b?.ims ?: "IDLE",
                        registered = b?.registered == true,
                        tunnelState = b?.tunnel ?: "IDLE",
                        voiceServiceOverride = b?.voiceServiceOverride?.lastApplied() == true
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

        val timeFmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val historyList = rt.telemetryUploadHistory().map { rec ->
            val timeLabel = if (rec.timestampMs > 0L) timeFmt.format(java.util.Date(rec.timestampMs)) else ""
            val triggerLabel = when (rec.trigger) {
                "periodic" -> "定时"
                "state_change" -> "状态变化"
                else -> "手动"
            }
            val sizeLabel = when {
                rec.bytes <= 0 -> ""
                rec.bytes < 1024 -> "${rec.bytes} B"
                else -> "${(rec.bytes + 512) / 1024} KB"
            }
            TelemetryUploadRecordUi(
                logId = rec.logId,
                timeLabel = timeLabel,
                triggerLabel = triggerLabel,
                ok = rec.ok,
                code = rec.code,
                sizeLabel = sizeLabel
            )
        }

        val compat = rt.compatReport
        val compatItems = compat?.items?.map {
            CompatItemUi(key = it.key, label = it.label, status = it.status, detail = it.detail)
        } ?: emptyList()
        val statusLower = rt.status.lowercase()
        val noRootDetected = rt.rootUnavailable ||
            (snap == null && (statusLower.contains("root") || statusLower.contains("permission") || statusLower.contains("su")))

        uiState = AppUiState(
            appVersion = installedVersionName,
            enabled = rt.enabled(),
            billable = rt.billable(),
            statusText = rt.status,
            readOnlyNoRoot = noRootDetected,
            compatMode = compat?.mode ?: "",
            compatSummary = compat?.summary ?: "",
            compatItems = compatItems,
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
            connectionsError = conns.error,
            telemetryEnabled = rt.telemetryEnabled(),
            telemetryRoute = rt.telemetryRoute(),
            telemetryEndpoint = rt.telemetryEndpoint(),
            telemetryLastStatus = rt.telemetryLastStatus(),
            telemetryHistory = historyList
        )
    }
}

@Composable
private fun VisiblePollingEffect(
    lifecycle: Lifecycle,
    intervalMillis: Long,
    enabled: Boolean = true,
    onPoll: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val poll by rememberUpdatedState(onPoll)
    DisposableEffect(lifecycle, enabled, intervalMillis) {
        var job: Job? = null
        fun updatePolling() {
            if (enabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                if (job?.isActive != true) {
                    job = scope.launch {
                        while (isActive) {
                            poll()
                            delay(intervalMillis)
                        }
                    }
                }
            } else {
                job?.cancel()
                job = null
            }
        }
        val observer = LifecycleEventObserver { _, _ -> updatePolling() }
        lifecycle.addObserver(observer)
        updatePolling()
        onDispose {
            lifecycle.removeObserver(observer)
            job?.cancel()
        }
    }
}

@Composable
private fun MainShell(
    state: AppUiState,
    lifecycle: Lifecycle,
    selectedTab: Int,
    connectionsPaused: Boolean,
    onSelectTab: (Int) -> Unit,
    onToggleConnectionsPause: () -> Unit,
    overviewFeedback: String,
    clashFeedback: String,
    connectionsFeedback: String,
    logFeedback: String,
    onPollController: () -> Unit,
    onPollConnections: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onRequestBillableToggle: (Boolean) -> Unit,
    onSelectSimInterface: (String, String) -> Unit,
    onUseBuiltinTun: () -> Unit,
    onRefreshAll: () -> Unit,
    onRunCompatCheck: () -> Unit,
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
    onCopyText: (String, String) -> Unit,
    onToggleTelemetry: (Boolean) -> Unit,
    onSelectTelemetryRoute: (String) -> Unit,
    onSaveTelemetryEndpoint: (String) -> Unit,
    onUploadTelemetryNow: () -> Unit,
    onPreviewTelemetry: () -> Unit,
    onCopyLogId: (String) -> Unit,
    onExportSanitizedLogs: () -> Unit,
    onExportRawLogs: () -> Unit
) {
    val p = LocalApplePalette.current

    val tabs = remember {
        listOf(
            GlassTabItem("概览", MiuixIcons.Phone),
            GlassTabItem("代理", MiuixIcons.WorldClock),
            GlassTabItem("连接", MiuixIcons.Link),
            GlassTabItem("网络", MiuixIcons.Layers)
        )
    }

    VisiblePollingEffect(
        lifecycle = lifecycle,
        intervalMillis = 2000L,
        onPoll = onPollController
    )
    VisiblePollingEffect(
        lifecycle = lifecycle,
        intervalMillis = 3000L,
        enabled = selectedTab == 2 && !connectionsPaused && state.clashRunning,
        onPoll = onPollConnections
    )

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
                        onTogglePause = onToggleConnectionsPause,
                        onRefreshNow = onPollConnections,
                        onCloseConnection = onCloseConnection,
                        onCloseAllConnections = onCloseAllConnections,
                        onUpdateController = onUpdateController,
                        onRotateSecret = onRotateSecret,
                        onCopyText = onCopyText
                    )
                    else -> NetworkPage(
                        state = state,
                        logFeedback = logFeedback,
                        onRefreshAll = onRefreshAll,
                        onRunCompatCheck = onRunCompatCheck,
                        onToggleTelemetry = onToggleTelemetry,
                        onSelectTelemetryRoute = onSelectTelemetryRoute,
                        onSaveTelemetryEndpoint = onSaveTelemetryEndpoint,
                        onUploadTelemetryNow = onUploadTelemetryNow,
                        onPreviewTelemetry = onPreviewTelemetry,
                        onCopyLogId = onCopyLogId,
                        onExportSanitizedLogs = onExportSanitizedLogs,
                        onExportRawLogs = onExportRawLogs
                    )
                }
            }
        }

        LiquidGlassBottomBar(
            tabs = tabs,
            selectedIndex = selectedTab,
            onSelect = onSelectTab,
            pageBackdrop = pageBackdrop,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

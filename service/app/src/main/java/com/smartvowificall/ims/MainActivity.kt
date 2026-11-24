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
import com.smartvowificall.ims.clash.ClashConfig
import com.smartvowificall.ims.policy.FallbackPolicy
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
    private var pendingBillableSlot: Int = 0
    private var pendingImportClashId: Int = 0
    private var pendingScanClashId: Int = 0
    private var connectionsCoreId by mutableIntStateOf(0)
    private var connectionsSnapshot by mutableStateOf(RuntimeController.ConnectionsSnapshot.EMPTY)

    private val audioPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val slot = pendingBillableSlot
            rt.setSlotBillable(slot, granted)
            overviewFeedback = if (granted) "已启用卡槽 ${slot + 1} 通话与短信" else "未授予麦克风权限，卡槽 ${slot + 1} 通话与短信未启用"
            syncStateFromController()
        }

    private val cameraPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchQrScanner(pendingScanClashId)
            } else {
                clashFeedback = "未授予摄像头权限，无法扫码添加订阅"
            }
        }

    private val qrScannerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK) {
                val url = res.data?.getStringExtra(QrScannerActivity.EXTRA_SCANNED_URL)?.trim().orEmpty()
                if (url.isNotEmpty()) {
                    val cid = pendingScanClashId
                    clashFeedback = "扫码成功，正在为内核 ${cid + 1} 拉取订阅…"
                    rt.clashFetchSubscription(cid, url) { msg ->
                        runOnUiThread {
                            clashFeedback = msg
                            syncStateFromController()
                        }
                    }
                }
            }
        }

    private val importYamlLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                importYamlFromUri(pendingImportClashId, uri)
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
                var selectedClashId by rememberSaveable { mutableIntStateOf(0) }
                var connectionsPaused by rememberSaveable { mutableStateOf(false) }
                val refreshConnections = {
                    pollConnections(selectedClashId) { selectedTab == 2 && !connectionsPaused }
                }
                MainShell(
                    state = uiState,
                    lifecycle = lifecycle,
                    selectedTab = selectedTab,
                    selectedClashId = selectedClashId,
                    connectionsPaused = connectionsPaused,
                    onSelectTab = { selectedTab = it },
                    onSelectClashId = { cid ->
                        selectedClashId = cid
                        if (selectedTab == 2 && !connectionsPaused) {
                            pollConnections(cid) { selectedTab == 2 && !connectionsPaused }
                        }
                    },
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
                        overviewFeedback = if (on) "总开关已开启，正在按卡槽与内核设置同步…" else "总开关已关闭，正在停止全部引擎与内置内核以省电…"
                        syncStateFromController()
                    },
                    onToggleSlotEnabled = { slot, on ->
                        rt.setSlotEnabled(slot, on)
                        overviewFeedback = if (on) "已开启卡槽 ${slot + 1} 网络通话" else "已关闭卡槽 ${slot + 1} 网络通话，正在恢复原厂蜂窝…"
                        syncStateFromController()
                    },
                    onRequestSlotBillableToggle = { slot, on -> handleSlotBillableToggle(slot, on) },
                    onSelectSimInterface = { simKey, iface ->
                        rt.select(simKey, iface)
                        overviewFeedback = "已将出口切换为 $iface"
                        syncStateFromController()
                    },
                    onUseBuiltinTunForSlot = { slot, cid ->
                        val tunDev = ClashConfig.tunDeviceForInstance(cid)
                        rt.useBuiltinTunForSlot(slot, cid)
                        overviewFeedback = "卡槽 ${slot + 1} 已绑定内置内核 ${cid + 1}（$tunDev）"
                        clashFeedback = "卡槽 ${slot + 1} 已绑定内核 ${cid + 1}（$tunDev）"
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
                    onFastReconnect = {
                        logFeedback = "正在触发 VoWiFi 隧道秒级重连…"
                        rt.reconnectActiveEnginesAsync { msg ->
                            runOnUiThread {
                                logFeedback = msg
                                syncStateFromController()
                            }
                        }
                    },
                    onEmergencyRestore = {
                        confirmEmergencyRestore()
                    },
                    onOpenRadioInfo = {
                        openSystemRadioInfo()
                    },
                    onToggleClash = { cid, on ->
                        val tunDev = ClashConfig.tunDeviceForInstance(cid)
                        if (on && !rt.enabled()) {
                            clashFeedback = "总开关已关闭（省电模式），请先在概览页开启总开关"
                        } else {
                            rt.setClashEnabled(cid, on)
                            clashFeedback = if (on) "已开启内核 ${cid + 1}（$tunDev）"
                            else "已关闭内核 ${cid + 1}（$tunDev）"
                        }
                        syncStateFromController()
                    },
                    onFetchSubscription = { cid, url ->
                        if (url.isEmpty()) {
                            clashFeedback = "请先输入内核 ${cid + 1} 订阅链接"
                        } else {
                            clashFeedback = "正在更新内核 ${cid + 1} 订阅…"
                            rt.clashFetchSubscription(cid, url) { msg ->
                                runOnUiThread {
                                    clashFeedback = msg
                                    syncStateFromController()
                                }
                            }
                        }
                    },
                    onScanSubscriptionQr = { cid ->
                        requestScanSubscriptionQr(cid)
                    },
                    onImportYaml = { cid ->
                        pendingImportClashId = cid
                        importYamlLauncher.launch(arrayOf("*/*"))
                    },
                    onReloadLocal = { cid ->
                        clashFeedback = "正在重载内核 ${cid + 1} 本地配置…"
                        rt.clashReloadLocal(cid) { msg ->
                            runOnUiThread {
                                clashFeedback = msg
                                syncStateFromController()
                            }
                        }
                    },
                    onCopyClashFrom = { dstId, srcId ->
                        clashFeedback = "正在从内核 ${srcId + 1} 复制配置到内核 ${dstId + 1}…"
                        rt.clashCopyFrom(dstId, srcId) { msg ->
                            runOnUiThread {
                                clashFeedback = msg
                                syncStateFromController()
                            }
                        }
                    },
                    onSelectNode = { cid, node ->
                        clashFeedback = "内核 ${cid + 1} 正在切换节点：$node…"
                        rt.clashSelectNode(cid, node) { msg ->
                            runOnUiThread {
                                clashFeedback = msg
                                syncStateFromController()
                            }
                        }
                    },
                    onTestDelay = { cid ->
                        clashFeedback = "正在测速内核 ${cid + 1} 全部节点…"
                        rt.clashTestDelay(cid) { msg ->
                            runOnUiThread {
                                clashFeedback = msg
                                syncStateFromController()
                            }
                        }
                    },
                    onRestartCore = { cid ->
                        clashFeedback = "正在重启内核 ${cid + 1}…"
                        rt.clashRestartCore(cid) { msg ->
                            runOnUiThread {
                                clashFeedback = msg
                                syncStateFromController()
                            }
                        }
                    },
                    onCloseConnection = { cid, connId ->
                        rt.clashCloseConnection(cid, connId) { msg ->
                            runOnUiThread {
                                connectionsFeedback = msg
                                refreshConnections()
                            }
                        }
                    },
                    onCloseAllConnections = { cid ->
                        rt.clashCloseAllConnections(cid) { msg ->
                            runOnUiThread {
                                connectionsFeedback = msg
                                refreshConnections()
                            }
                        }
                    },
                    onUpdateController = { cid, lan, port ->
                        connectionsFeedback = "正在保存内核 ${cid + 1} 外部控制设置…"
                        rt.clashSetController(cid, lan, port) { msg ->
                            runOnUiThread {
                                connectionsFeedback = msg
                                syncStateFromController()
                            }
                        }
                    },
                    onRotateSecret = { cid ->
                        connectionsFeedback = "正在重置内核 ${cid + 1} 访问密钥…"
                        rt.clashRotateSecret(cid) { msg ->
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

    private fun requestScanSubscriptionQr(clashId: Int) {
        pendingScanClashId = clashId
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchQrScanner(clashId)
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchQrScanner(clashId: Int) {
        val intent = Intent(this, QrScannerActivity::class.java)
            .putExtra(QrScannerActivity.EXTRA_CLASH_ID, clashId)
        qrScannerLauncher.launch(intent)
    }

    private fun handleSlotBillableToggle(slot: Int, on: Boolean) {
        if (!on) {
            rt.setSlotBillable(slot, false)
            overviewFeedback = "已关闭卡槽 ${slot + 1} 通话与短信发送，仅保持连接"
            syncStateFromController()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("启用卡槽 ${slot + 1} 通话与短信")
            .setMessage("启用后卡槽 ${slot + 1} 可使用系统电话和短信，费用按运营商套餐收取。仅测试连接时请保持关闭。")
            .setPositiveButton("启用") { _, _ ->
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingBillableSlot = slot
                    audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    rt.setSlotBillable(slot, true)
                    overviewFeedback = "已启用卡槽 ${slot + 1} 通话与短信"
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

    private fun confirmEmergencyRestore() {
        AlertDialog.Builder(this)
            .setTitle("一键彻底恢复原厂蜂窝与 IMS")
            .setMessage("将关闭网络通话总开关、停止内置代理、解除全部 IMS 服务覆盖并恢复所有卡槽蜂窝信号（通话中会自动拦截以防掉线）。确认执行？")
            .setPositiveButton("确认恢复") { _, _ ->
                logFeedback = "正在恢复原厂蜂窝与 IMS…"
                rt.emergencyRestoreNativeRadioAndIms { msg ->
                    runOnUiThread {
                        logFeedback = msg
                        overviewFeedback = msg
                        syncStateFromController()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openSystemRadioInfo() {
        val candidates = listOf(
            Intent().setClassName("com.android.phone", "com.android.phone.settings.RadioInfo"),
            Intent().setClassName("com.android.settings", "com.android.settings.RadioInfo"),
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode("*#*#4636#*#*")))
        )
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                logFeedback = "已打开系统 RadioInfo / 工程测试入口"
                return
            } catch (_: Exception) {
            }
        }
        logFeedback = "当前系统 ROM 限制直接拉起 RadioInfo，请在拨号盘输入 *#*#4636#*#*"
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

    private fun importYamlFromUri(clashId: Int, uri: Uri) {
        clashFeedback = "正在读取内核 ${clashId + 1} 配置文件…"
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
                    rt.clashSaveProfile(clashId, yaml) { msg ->
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

    private fun pollConnections(clashId: Int, isPageActive: () -> Boolean) {
        val canRefresh = {
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                isPageActive() && rt.clashState(clashId).running
        }
        if (!canRefresh() || !connectionRequestInFlight.compareAndSet(false, true)) return
        try {
            rt.clashFetchConnections(clashId) { snap ->
                runOnUiThread {
                    try {
                        if (canRefresh()) {
                            connectionsCoreId = clashId
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
        val conns = connectionsSnapshot

        val coreUiList = (0 until ClashConfig.INSTANCE_COUNT).map { id ->
            val cs = rt.clashState(id)
            ClashCoreUi(
                id = id,
                tunDevice = ClashConfig.tunDeviceForInstance(id),
                enabled = rt.clashEnabled(id),
                willRestore = rt.clashWillRestore(id),
                hasProfile = cs.hasProfile,
                running = cs.running,
                tunUp = cs.tunUp,
                currentNode = cs.currentNode,
                savedNode = cs.savedNode,
                netnsId = cs.netnsId,
                subscriptionUrl = cs.subscriptionUrl,
                externalLan = cs.externalLan,
                controllerPort = cs.controllerPort,
                controllerSecret = cs.controllerSecret,
                lanIpv4 = cs.lanIpv4,
                lastError = cs.lastError,
                nodes = cs.nodes.map { n ->
                    NodeRowUi(
                        name = n.name,
                        type = n.type,
                        delay = n.delay,
                        selected = n.name == cs.currentNode
                    )
                }
            )
        }

        val simList = mutableListOf<SimCardUi>()
        if (snap != null) {
            for (s in snap.sims) {
                val elig = PolicyEngine.eligibility(s.policy) == PolicyEngine.Reason.ELIGIBLE
                val mainland = PolicyEngine.isMainlandPlmn(s.policy.homePlmn)
                val rawBinding = rt.bindings[s.policy.slotId]
                val b = if (rt.bindingMatches(rawBinding, s)) rawBinding else null
                val selIface = rt.selection(s.policy.simKey)
                val selCid = ClashConfig.instanceForTun(selIface)
                val activeNode = if (selCid >= 0) {
                    val cs = rt.clashState(selCid)
                    if (cs.running && cs.tunUp) cs.currentNode.ifEmpty { cs.savedNode } else ""
                } else ""
                val fbCode = rt.fallbackReasons[s.policy.slotId] ?: ""
                val carrierLabel = s.carrierName.ifEmpty { s.simOperatorName }
                simList.add(
                    SimCardUi(
                        slotId = s.policy.slotId,
                        subId = s.policy.subId,
                        simKey = s.policy.simKey,
                        homePlmn = s.policy.homePlmn,
                        carrierName = carrierLabel,
                        ready = s.policy.ready,
                        eligible = elig,
                        mainland = mainland,
                        slotEnabled = rt.slotEnabled(s.policy.slotId),
                        slotBillable = rt.billable(s.policy.slotId),
                        selectedInterface = selIface,
                        activeProxyNode = activeNode,
                        radioOff = java.lang.Boolean.TRUE == rt.radioOff[s.policy.slotId],
                        featureAttached = rt.featureMatches(s),
                        providerBound = b?.providerBound == true,
                        imsState = b?.ims ?: "IDLE",
                        registered = b?.registered == true,
                        tunnelState = b?.tunnel ?: "IDLE",
                        voiceServiceOverride = b?.voiceServiceOverride?.lastApplied() == true,
                        fallbackReason = if (fbCode.isEmpty()) "" else FallbackPolicy.describe(fbCode),
                        carrierProfileHint = if (elig) rt.carrierHintForPlmn(s.policy.homePlmn) else ""
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
            statusText = rt.status,
            readOnlyNoRoot = noRootDetected,
            compatMode = compat?.mode ?: "",
            compatSummary = compat?.summary ?: "",
            compatItems = compatItems,
            clashCores = coreUiList,
            preferredVpnInterface = inv?.preferredVpnInterface ?: "",
            sims = simList,
            interfaces = ifaceList,
            connectionsCoreId = connectionsCoreId,
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
    selectedClashId: Int,
    connectionsPaused: Boolean,
    onSelectTab: (Int) -> Unit,
    onSelectClashId: (Int) -> Unit,
    onToggleConnectionsPause: () -> Unit,
    overviewFeedback: String,
    clashFeedback: String,
    connectionsFeedback: String,
    logFeedback: String,
    onPollController: () -> Unit,
    onPollConnections: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleSlotEnabled: (Int, Boolean) -> Unit,
    onRequestSlotBillableToggle: (Int, Boolean) -> Unit,
    onSelectSimInterface: (String, String) -> Unit,
    onUseBuiltinTunForSlot: (Int, Int) -> Unit,
    onRefreshAll: () -> Unit,
    onRunCompatCheck: () -> Unit,
    onFastReconnect: () -> Unit,
    onEmergencyRestore: () -> Unit,
    onOpenRadioInfo: () -> Unit,
    onToggleClash: (Int, Boolean) -> Unit,
    onFetchSubscription: (Int, String) -> Unit,
    onScanSubscriptionQr: (Int) -> Unit,
    onImportYaml: (Int) -> Unit,
    onReloadLocal: (Int) -> Unit,
    onCopyClashFrom: (Int, Int) -> Unit,
    onSelectNode: (Int, String) -> Unit,
    onTestDelay: (Int) -> Unit,
    onRestartCore: (Int) -> Unit,
    onCloseConnection: (Int, String) -> Unit,
    onCloseAllConnections: (Int) -> Unit,
    onUpdateController: (Int, Boolean, Int) -> Unit,
    onRotateSecret: (Int) -> Unit,
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
    val selectedCore = state.clashCore(selectedClashId)

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
        enabled = selectedTab == 2 && !connectionsPaused && selectedCore.running,
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
                        onToggleSlotEnabled = onToggleSlotEnabled,
                        onRequestSlotBillableToggle = onRequestSlotBillableToggle,
                        onSelectSimInterface = onSelectSimInterface,
                        onUseBuiltinTunForSlot = onUseBuiltinTunForSlot,
                        onRefreshAll = onRefreshAll
                    )
                    1 -> ProxyPage(
                        state = state,
                        selectedClashId = selectedClashId,
                        onSelectClashId = onSelectClashId,
                        clashFeedback = clashFeedback,
                        onToggleClash = onToggleClash,
                        onFetchSubscription = onFetchSubscription,
                        onScanSubscriptionQr = onScanSubscriptionQr,
                        onImportYaml = onImportYaml,
                        onReloadLocal = onReloadLocal,
                        onCopyClashFrom = onCopyClashFrom,
                        onSelectNode = onSelectNode,
                        onTestDelay = onTestDelay,
                        onRestartCore = onRestartCore,
                        onAssignCoreToSlot = onUseBuiltinTunForSlot
                    )
                    2 -> ConnectionsPage(
                        state = state,
                        selectedClashId = selectedClashId,
                        onSelectClashId = onSelectClashId,
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
                        onFastReconnect = onFastReconnect,
                        onEmergencyRestore = onEmergencyRestore,
                        onOpenRadioInfo = onOpenRadioInfo,
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

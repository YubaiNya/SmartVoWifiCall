package com.smartvowificall.ims

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.smartvowificall.ims.clash.ClashConfig
import com.smartvowificall.ims.network.InterfaceSnapshot
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Immutable
data class SimCardUi(
    val slotId: Int,
    val subId: Int,
    val simKey: String,
    val homePlmn: String,
    val carrierName: String = "",
    val ready: Boolean,
    val eligible: Boolean,
    val mainland: Boolean,
    val slotEnabled: Boolean = false,
    val slotBillable: Boolean = false,
    val selectedInterface: String,
    val activeProxyNode: String = "",
    val radioOff: Boolean,
    val featureAttached: Boolean,
    val providerBound: Boolean,
    val imsState: String,
    val registered: Boolean,
    val tunnelState: String,
    val voiceServiceOverride: Boolean,
    val fallbackReason: String = "",
    val carrierProfileHint: String = ""
)

@Immutable
data class InterfaceRowUi(
    val name: String,
    val type: String,
    val up: Boolean?,
    val available: Boolean,
    val voWifiTun: Boolean,
    val addresses: String,
    val label: String
)

@Immutable
data class NodeRowUi(
    val name: String,
    val type: String,
    val delay: Int,
    val selected: Boolean
)

@Immutable
data class ClashCoreUi(
    val id: Int,
    val tunDevice: String,
    val enabled: Boolean = false,
    val willRestore: Boolean = false,
    val hasProfile: Boolean = false,
    val running: Boolean = false,
    val tunUp: Boolean = false,
    val currentNode: String = "",
    val savedNode: String = "",
    val netnsId: String = "",
    val subscriptionUrl: String = "",
    val externalLan: Boolean = false,
    val controllerPort: Int = ClashConfig.CONTROLLER_PORT,
    val controllerSecret: String = "",
    val lanIpv4: String = "",
    val lastError: String = "",
    val nodes: List<NodeRowUi> = emptyList()
)

@Immutable
data class ConnectionRowUi(
    val id: String,
    val network: String,
    val type: String,
    val source: String,
    val destination: String,
    val rule: String,
    val chainSummary: String,
    val upload: Long,
    val download: Long,
    val start: String
)

@Immutable
data class TelemetryUploadRecordUi(
    val logId: String,
    val timeLabel: String,
    val triggerLabel: String,
    val ok: Boolean,
    val code: String,
    val sizeLabel: String
)

@Immutable
data class CompatItemUi(
    val key: String,
    val label: String,
    val status: String,
    val detail: String
)

@Immutable
data class AppUiState(
    val appVersion: String = "",
    val enabled: Boolean = false,
    val statusText: String = "正在读取设备…",
    val readOnlyNoRoot: Boolean = false,
    val compatMode: String = "",
    val compatSummary: String = "",
    val compatItems: List<CompatItemUi> = emptyList(),
    val clashCores: List<ClashCoreUi> = listOf(
        ClashCoreUi(id = 0, tunDevice = ClashConfig.TUN_DEVICE, controllerPort = ClashConfig.CONTROLLER_PORT),
        ClashCoreUi(id = 1, tunDevice = ClashConfig.TUN_DEVICE_1, controllerPort = ClashConfig.CONTROLLER_PORT_1)
    ),
    val preferredVpnInterface: String = "",
    val sims: List<SimCardUi> = emptyList(),
    val interfaces: List<InterfaceRowUi> = emptyList(),
    val connectionsCoreId: Int = 0,
    val connectionsRunning: Boolean = false,
    val downloadTotal: Long = 0L,
    val uploadTotal: Long = 0L,
    val memoryBytes: Long = 0L,
    val connections: List<ConnectionRowUi> = emptyList(),
    val connectionsError: String = "",
    val telemetryEnabled: Boolean = false,
    val telemetryRoute: String = "wificall",
    val telemetryEndpoint: String = "",
    val telemetryLastStatus: String = "",
    val telemetryHistory: List<TelemetryUploadRecordUi> = emptyList()
) {
    fun clashCore(id: Int): ClashCoreUi =
        clashCores.getOrElse(id) { clashCores.first() }
}

@Composable
fun OverviewPage(
    state: AppUiState,
    actionFeedback: String,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleSlotEnabled: (Int, Boolean) -> Unit,
    onRequestSlotBillableToggle: (Int, Boolean) -> Unit,
    onSelectSimInterface: (String, String) -> Unit,
    onUseBuiltinTunForSlot: (Int, Int) -> Unit,
    onRefreshAll: () -> Unit
) {
    val p = LocalApplePalette.current
    val scroll = rememberScrollState()
    var pickerSim by remember { mutableStateOf<SimCardUi?>(null) }

    val enabledSims = state.sims.filter { it.eligible && it.slotEnabled }
    val registeredSims = enabledSims.filter { it.registered }
    val anyConnecting = enabledSims.any { !it.registered && (it.tunnelState == "CONNECTING" || it.imsState == "REGISTERING" || it.imsState == "CONNECTING") }
    val core0 = state.clashCore(0)
    val core1 = state.clashCore(1)

    val heroAccent = when {
        !state.enabled -> p.textSecondary
        registeredSims.isNotEmpty() -> p.accentGreen
        anyConnecting -> p.accentBlue
        else -> p.accentOrange
    }
    val heroBadge = when {
        !state.enabled -> "省电模式"
        registeredSims.size >= 2 -> "双卡已连接"
        registeredSims.size == 1 -> "卡槽 ${registeredSims[0].slotId + 1} 已连接"
        enabledSims.isEmpty() -> "未启用卡槽"
        anyConnecting -> "正在连接"
        else -> "未连接"
    }
    val heroHeadline = when {
        !state.enabled -> "全部后台已停机省电"
        registeredSims.size >= 2 -> "双卡网络通话已就绪"
        registeredSims.size == 1 -> "网络通话已连接"
        enabledSims.isEmpty() -> "总开关已开启"
        else -> "等待网络通话连接"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .statusBarsPadding()
            .padding(top = 8.dp, bottom = 116.dp)
    ) {
        PageHeader(
            title = "SmartVoWifiCall",
            subtitle = "双卡网络通话 · 双内核代理",
            trailing = {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(p.card)
                        .border(0.75.dp, p.cardBorder, CircleShape)
                        .clickable(onClick = onRefreshAll),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = MiuixIcons.Refresh,
                        contentDescription = "刷新",
                        tint = p.accentBlue,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        )

        Spacer(Modifier.height(8.dp))

        if (state.readOnlyNoRoot) {
            AppleMiuixCard(insideMargin = PaddingValues(16.dp)) {
                StatusPill(label = "只读降级模式", color = p.accentOrange)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "未检测到 Root 权限授权",
                    color = p.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "请在 KernelSU / SukiSU / Magisk 管理器中为 SmartVoWifiCall 授予超级用户权限，并确认模块已激活后重启，然后点击「刷新状态」或在「网络」页运行系统兼容性自检。",
                    color = p.textSecondary,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        AppleHeroCard(accent = heroAccent) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(label = heroBadge, color = heroAccent)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (core0.running && core0.tunUp) {
                        StatusPill(label = ClashConfig.TUN_DEVICE, color = p.accentBlue, showDot = false)
                    }
                    if (core1.running && core1.tunUp) {
                        StatusPill(label = ClashConfig.TUN_DEVICE_1, color = p.accentPurple, showDot = false)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = heroHeadline,
                color = p.textPrimary,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.statusText,
                color = p.textSecondary,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCell(
                    label = "已开启卡槽",
                    value = if (state.enabled) "${enabledSims.size} / ${state.sims.size}" else "总开关关闭",
                    valueColor = if (state.enabled && enabledSims.isNotEmpty()) p.accentBlue else p.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                MetricCell(
                    label = "已注册 VoWiFi",
                    value = "${registeredSims.size} 张",
                    valueColor = if (registeredSims.isNotEmpty()) p.accentGreen else p.textPrimary,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCell(
                    label = "内核 1 · ${ClashConfig.TUN_DEVICE}",
                    value = when {
                        core0.running && core0.tunUp -> core0.currentNode.ifEmpty { "运行中" }
                        !state.enabled && core0.willRestore -> "已停机（开后恢复）"
                        core0.enabled -> "启动中"
                        else -> "已关闭"
                    },
                    valueColor = if (core0.running && core0.tunUp) p.accentBlue else p.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                MetricCell(
                    label = "内核 2 · ${ClashConfig.TUN_DEVICE_1}",
                    value = when {
                        core1.running && core1.tunUp -> core1.currentNode.ifEmpty { "运行中" }
                        !state.enabled && core1.willRestore -> "已停机（开后恢复）"
                        core1.enabled -> "启动中"
                        else -> "已关闭"
                    },
                    valueColor = if (core1.running && core1.tunUp) p.accentPurple else p.textSecondary,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (actionFeedback.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FeedbackBanner(text = actionFeedback)
        }

        Spacer(Modifier.height(14.dp))
        AppleSectionTitle("总开关")
        AppleMiuixCard {
            SwitchPreference(
                title = "网络通话总开关",
                summary = if (state.enabled)
                    "已开启；关闭后将停止全部后端引擎、恢复原厂蜂窝/IMS 并停用 wfctun0 / wfctun1 以省电"
                else
                    "已关闭全部引擎与双内置内核（省电模式），所有卡槽均使用系统原厂蜂窝网络",
                checked = state.enabled,
                onCheckedChange = onToggleEnabled
            )
        }

        Spacer(Modifier.height(16.dp))
        AppleSectionTitle("卡槽控制（不限国内外 SIM 卡，支持双卡同时 VoWiFi）")

        if (state.sims.isEmpty()) {
            AppleMiuixCard(insideMargin = PaddingValues(20.dp)) {
                Text(
                    text = "正在读取 SIM 卡状态…",
                    color = p.textSecondary,
                    fontSize = 14.sp
                )
            }
        } else {
            for (sim in state.sims) {
                SimCardView(
                    sim = sim,
                    masterEnabled = state.enabled,
                    onToggleSlotEnabled = { on -> onToggleSlotEnabled(sim.slotId, on) },
                    onRequestSlotBillableToggle = { on -> onRequestSlotBillableToggle(sim.slotId, on) },
                    onPickInterface = { pickerSim = sim },
                    onUseBuiltinTun = { cid -> onUseBuiltinTunForSlot(sim.slotId, cid) }
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    val activePickerSim = pickerSim
    if (activePickerSim != null) {
        InterfacePickerDialog(
            sim = activePickerSim,
            interfaces = state.interfaces,
            clashCores = state.clashCores,
            onDismiss = { pickerSim = null },
            onSelect = { iface ->
                onSelectSimInterface(activePickerSim.simKey, iface)
                pickerSim = null
            }
        )
    }
}

@Composable
private fun SimCardView(
    sim: SimCardUi,
    masterEnabled: Boolean,
    onToggleSlotEnabled: (Boolean) -> Unit,
    onRequestSlotBillableToggle: (Boolean) -> Unit,
    onPickInterface: () -> Unit,
    onUseBuiltinTun: (Int) -> Unit
) {
    val p = LocalApplePalette.current
    val regionText = if (sim.mainland) "大陆卡" else "海外卡"
    val statePillLabel = when {
        !sim.eligible -> "SIM 未就绪"
        !masterEnabled -> "总开关已关"
        !sim.slotEnabled -> "未开启"
        sim.registered -> "已注册"
        sim.fallbackReason.isNotEmpty() -> "已回退蜂窝"
        sim.tunnelState == "CONNECTING" || sim.imsState == "REGISTERING" -> "连接中"
        else -> "等待连接"
    }
    val statePillColor = when {
        !sim.eligible || !masterEnabled || !sim.slotEnabled -> p.textSecondary
        sim.registered -> p.accentGreen
        sim.fallbackReason.isNotEmpty() -> p.accentOrange
        else -> p.accentBlue
    }
    val subtitleText = buildString {
        append("subId ${sim.subId} · PLMN ${sim.homePlmn.ifEmpty { "未知" }}")
        if (sim.carrierName.isNotEmpty()) {
            append(" · ${sim.carrierName}")
        }
    }
    val egressLabel = when {
        sim.selectedInterface.isEmpty() -> "点击选择出口"
        sim.selectedInterface == ClashConfig.TUN_DEVICE -> "内核 1 · ${ClashConfig.TUN_DEVICE}"
        sim.selectedInterface == ClashConfig.TUN_DEVICE_1 -> "内核 2 · ${ClashConfig.TUN_DEVICE_1}"
        else -> "外部 TUN · ${sim.selectedInterface}"
    }
    val nodeDisplay = when {
        sim.activeProxyNode.isNotEmpty() -> sim.activeProxyNode
        ClashConfig.isBuiltinTun(sim.selectedInterface) -> "内核未运行"
        sim.selectedInterface.isNotEmpty() -> "直走 ${sim.selectedInterface}"
        else -> "未选择"
    }

    AppleMiuixCard(insideMargin = PaddingValues(bottom = 16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "卡槽 ${sim.slotId + 1}",
                        color = p.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusPill(
                        label = regionText,
                        color = if (sim.mainland) p.accentOrange else p.accentBlue,
                        showDot = false
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitleText,
                    color = p.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFeatureSettings = "tnum")
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusPill(label = statePillLabel, color = statePillColor)
        }

        if (sim.carrierProfileHint.isNotEmpty()) {
            Text(
                text = sim.carrierProfileHint,
                color = p.accentBlue,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
            )
        }
        if (sim.fallbackReason.isNotEmpty() && masterEnabled && sim.slotEnabled) {
            Text(
                text = "当前已回退蜂窝：${sim.fallbackReason}",
                color = p.accentOrange,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
            )
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        SwitchPreference(
            title = "开启卡槽 ${sim.slotId + 1} 网络通话",
            summary = when {
                !sim.eligible -> "此卡槽当前未就绪，无法启用"
                !masterEnabled -> "已记录开关状态；打开上方总开关后生效"
                sim.slotEnabled -> "关闭此卡蜂窝信号，通过所选 TUN 拉起 VoWiFi"
                else -> "保持系统原厂蜂窝与 IMS，不接管此卡槽"
            },
            checked = sim.slotEnabled,
            onCheckedChange = onToggleSlotEnabled
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        SwitchPreference(
            title = "允许卡槽 ${sim.slotId + 1} 通话与短信",
            summary = if (sim.slotBillable) "允许此卡拨打电话和收发短信（按套餐计费）" else "仅保持连接，拦截此卡外呼与短信发送",
            checked = sim.slotBillable,
            onCheckedChange = onRequestSlotBillableToggle
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricCell(
                    label = "隧道",
                    value = connectionStateLabel(sim.tunnelState),
                    valueColor = if (sim.tunnelState == "CONNECTED") p.accentBlue else p.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                MetricCell(
                    label = "IMS",
                    value = connectionStateLabel(sim.imsState),
                    valueColor = if (sim.registered) p.accentGreen else p.textPrimary,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricCell(
                    label = "蜂窝 / 语音",
                    value = if (sim.radioOff) {
                        if (sim.voiceServiceOverride) "蜂窝关 · 可通话" else "蜂窝已关"
                    } else {
                        "蜂窝开启"
                    },
                    valueColor = if (sim.radioOff && sim.voiceServiceOverride) p.accentGreen else p.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                MetricCell(
                    label = "当前节点",
                    value = nodeDisplay,
                    valueColor = if (sim.activeProxyNode.isNotEmpty()) p.accentBlue else p.textSecondary,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(p.cardSubtle)
                    .clickable(onClick = onPickInterface)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "卡槽 ${sim.slotId + 1} 通话出口",
                        color = p.textSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = egressLabel,
                        color = p.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Icon(
                    imageVector = MiuixIcons.ChevronForward,
                    contentDescription = "选择接口",
                    tint = p.textTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ApplePillButton(
                    text = "用 ${ClashConfig.TUN_DEVICE}",
                    onClick = { onUseBuiltinTun(0) },
                    primary = sim.selectedInterface == ClashConfig.TUN_DEVICE,
                    modifier = Modifier.weight(1f)
                )
                ApplePillButton(
                    text = "用 ${ClashConfig.TUN_DEVICE_1}",
                    onClick = { onUseBuiltinTun(1) },
                    primary = sim.selectedInterface == ClashConfig.TUN_DEVICE_1,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun InterfacePickerDialog(
    sim: SimCardUi,
    interfaces: List<InterfaceRowUi>,
    clashCores: List<ClashCoreUi>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val p = LocalApplePalette.current
    val combined = remember(interfaces, clashCores) {
        val byName = LinkedHashMap<String, InterfaceRowUi>()
        for (core in clashCores) {
            val existing = interfaces.firstOrNull { it.name == core.tunDevice }
            if (existing != null) {
                byName[core.tunDevice] = existing
            } else {
                byName[core.tunDevice] = InterfaceRowUi(
                    name = core.tunDevice,
                    type = "内置内核 ${core.id + 1}",
                    up = core.running && core.tunUp,
                    available = core.running && core.tunUp,
                    voWifiTun = true,
                    addresses = if (core.running && core.tunUp) ClashConfig.spec(core.id).tunIpv4 else "内核未启动（可在代理页手动开启）",
                    label = "${core.tunDevice} 内置内核 ${core.id + 1}"
                )
            }
        }
        for (item in interfaces) {
            if (!byName.containsKey(item.name)) {
                byName[item.name] = item
            }
        }
        byName.values.sortedWith(
            compareByDescending<InterfaceRowUi> { it.name == ClashConfig.TUN_DEVICE }
                .thenByDescending { it.name == ClashConfig.TUN_DEVICE_1 }
                .thenByDescending { it.voWifiTun }
                .thenByDescending { it.available }
                .thenBy { it.name }
        )
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(p.card)
                .border(0.75.dp, p.cardBorder, RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "卡槽 ${sim.slotId + 1} · 通话出口",
                    color = p.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(p.cardSubtle)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = MiuixIcons.Close,
                        contentDescription = "关闭",
                        tint = p.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "支持内置双内核 wfctun0 / wfctun1 或外部 TUN 接口；双卡可分别选择不同内核。",
                color = p.textSecondary,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (item in combined) {
                    val isSelected = item.name == sim.selectedInterface
                    val builtinCid = ClashConfig.instanceForTun(item.name)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isSelected) p.accentBlue.copy(alpha = if (p.dark) 0.22f else 0.10f)
                                else p.cardSubtle
                            )
                            .border(
                                width = if (isSelected) 1.dp else 0.dp,
                                color = if (isSelected) p.accentBlue else Color.Transparent,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { onSelect(item.name) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = item.name,
                                    color = p.textPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.width(8.dp))
                                StatusPill(
                                    label = when {
                                        builtinCid >= 0 -> "内置内核 ${builtinCid + 1}"
                                        item.voWifiTun -> "${item.type} · 可作出口"
                                        else -> "${item.type} · 不可用"
                                    },
                                    color = when {
                                        builtinCid == 0 -> p.accentBlue
                                        builtinCid == 1 -> p.accentPurple
                                        item.voWifiTun -> p.accentGreen
                                        else -> p.textTertiary
                                    },
                                    showDot = false
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "${if (item.up == true) "已启用" else "未启用"} · ${item.addresses.ifEmpty { "无 IP 地址" }}",
                                color = p.textSecondary,
                                fontSize = 12.sp
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = MiuixIcons.Ok,
                                contentDescription = "已选",
                                tint = p.accentBlue,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClashCoreSelector(
    cores: List<ClashCoreUi>,
    selectedId: Int,
    onSelect: (Int) -> Unit
) {
    val p = LocalApplePalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (core in cores) {
            val active = core.id == selectedId
            val accent = if (core.id == 0) p.accentBlue else p.accentPurple
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (active) accent.copy(alpha = if (p.dark) 0.24f else 0.13f)
                        else p.card
                    )
                    .border(
                        width = if (active) 1.25.dp else 0.75.dp,
                        color = if (active) accent else p.cardBorder,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable { onSelect(core.id) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                core.running && core.tunUp -> p.accentGreen
                                core.running -> p.accentOrange
                                core.enabled -> accent
                                else -> p.textTertiary.copy(alpha = 0.45f)
                            }
                        )
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "内核 ${core.id + 1} · ${core.tunDevice}",
                    color = if (active) accent else p.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ProxyPage(
    state: AppUiState,
    selectedClashId: Int,
    onSelectClashId: (Int) -> Unit,
    clashFeedback: String,
    onToggleClash: (Int, Boolean) -> Unit,
    onFetchSubscription: (Int, String) -> Unit,
    onScanSubscriptionQr: (Int) -> Unit,
    onImportYaml: (Int) -> Unit,
    onReloadLocal: (Int) -> Unit,
    onCopyClashFrom: (Int, Int) -> Unit,
    onSelectNode: (Int, String) -> Unit,
    onTestDelay: (Int) -> Unit,
    onRestartCore: (Int) -> Unit,
    onAssignCoreToSlot: (Int, Int) -> Unit
) {
    val p = LocalApplePalette.current
    val scroll = rememberScrollState()
    val core = state.clashCore(selectedClashId)
    var urlInput by rememberSaveable(core.id, core.subscriptionUrl) {
        mutableStateOf(core.subscriptionUrl)
    }
    var searchQuery by rememberSaveable(core.id) { mutableStateOf("") }

    val accent = if (core.id == 0) p.accentBlue else p.accentPurple
    val heroColor = when {
        !core.enabled -> p.textSecondary
        !state.enabled -> p.textSecondary
        core.running && core.tunUp -> accent
        core.running -> p.accentOrange
        else -> p.accentOrange
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .statusBarsPadding()
            .padding(top = 8.dp, bottom = 116.dp)
    ) {
        PageHeader(
            title = "内置双内核代理",
            subtitle = "wfctun0 / wfctun1 独立手动开关"
        )
        Spacer(Modifier.height(8.dp))

        ClashCoreSelector(
            cores = state.clashCores,
            selectedId = core.id,
            onSelect = onSelectClashId
        )
        Spacer(Modifier.height(10.dp))

        AppleHeroCard(accent = heroColor) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(
                    label = when {
                        !state.enabled && core.willRestore -> "总开关已停（开后自动恢复）"
                        !state.enabled -> "总开关已关（省电）"
                        !core.enabled -> "手动已关闭"
                        core.running && core.tunUp -> "已就绪 · ${core.tunDevice}"
                        core.running -> "正在启动"
                        core.hasProfile -> "待启动"
                        else -> "请导入配置"
                    },
                    color = heroColor
                )
                StatusPill(
                    label = "控制端口 :${core.controllerPort}",
                    color = accent,
                    showDot = false
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = core.currentNode.ifEmpty {
                    core.savedNode.ifEmpty { "未选择节点" }
                },
                color = p.textPrimary,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "内核 ${core.id + 1}（${core.tunDevice}）运行在独立隔离命名空间，仅服务所选卡槽的 VoWiFi 流量。",
                color = p.textSecondary,
                fontSize = 12.sp
            )
            if (core.lastError.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "最近状态：${core.lastError}",
                    color = p.accentOrange,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ApplePillButton(
                    text = "节点测速",
                    onClick = { onTestDelay(core.id) },
                    primary = true,
                    enabled = core.running,
                    modifier = Modifier.weight(1f)
                )
                ApplePillButton(
                    text = "重启内核",
                    onClick = { onRestartCore(core.id) },
                    primary = false,
                    enabled = core.hasProfile,
                    modifier = Modifier.weight(1f)
                )
            }
            if (state.sims.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (sim in state.sims.take(2)) {
                        val assigned = sim.selectedInterface == core.tunDevice
                        ApplePillButton(
                            text = if (assigned) "卡槽 ${sim.slotId + 1} 已绑定" else "设为卡槽 ${sim.slotId + 1} 出口",
                            onClick = { onAssignCoreToSlot(sim.slotId, core.id) },
                            primary = assigned,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        if (clashFeedback.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FeedbackBanner(text = clashFeedback)
        }

        Spacer(Modifier.height(14.dp))
        AppleSectionTitle("内核 ${core.id + 1}（${core.tunDevice}）设置")
        AppleMiuixCard(insideMargin = PaddingValues(bottom = 16.dp)) {
            SwitchPreference(
                title = "启用内核 ${core.id + 1}（${core.tunDevice}）",
                summary = when {
                    !state.enabled && core.willRestore -> "已随总开关停机省电；重新开启总开关后自动恢复运行"
                    !state.enabled -> "总开关已关闭（省电模式），请先在概览页开启总开关"
                    else -> "手动启停 ${core.tunDevice} 独立 Clash 内核，关闭后立即停机省电"
                },
                checked = state.enabled && core.enabled,
                onCheckedChange = { on -> onToggleClash(core.id, on) }
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "订阅链接（HTTP / HTTPS）",
                    color = p.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = "输入内核 ${core.id + 1} 的 Clash 订阅地址",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ApplePillButton(
                        text = "更新订阅",
                        onClick = { onFetchSubscription(core.id, urlInput.trim()) },
                        primary = true,
                        modifier = Modifier.weight(1f)
                    )
                    ApplePillButton(
                        text = "扫码订阅",
                        onClick = { onScanSubscriptionQr(core.id) },
                        primary = false,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ApplePillButton(
                        text = "导入 YAML",
                        onClick = { onImportYaml(core.id) },
                        primary = false,
                        modifier = Modifier.weight(1f)
                    )
                    ApplePillButton(
                        text = "重新加载",
                        onClick = { onReloadLocal(core.id) },
                        primary = false,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (core.id == 1) {
                    Spacer(Modifier.height(8.dp))
                    ApplePillButton(
                        text = "从内核 1（wfctun0）一键复制订阅与配置",
                        onClick = { onCopyClashFrom(1, 0) },
                        primary = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "内核 1 与内核 2 拥有独立配置目录、独立节点选择与独立 TUN 出口。",
                    color = p.textTertiary,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        AppleSectionTitle("内核 ${core.id + 1} 代理节点（${core.nodes.size}）")
        AppleMiuixCard(insideMargin = PaddingValues(16.dp)) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = "搜索节点或协议",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            val filtered = remember(core.nodes, searchQuery) {
                val q = searchQuery.trim().lowercase()
                if (q.isEmpty()) core.nodes
                else core.nodes.filter {
                    it.name.lowercase().contains(q) || it.type.lowercase().contains(q)
                }
            }

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (core.nodes.isEmpty()) "请先为内核 ${core.id + 1} 导入配置并开启总开关与内核开关"
                        else "未找到匹配的节点",
                        color = p.textSecondary,
                        fontSize = 14.sp
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (node in filtered) {
                        NodeItemRow(
                            node = node,
                            onClick = { onSelectNode(core.id, node.name) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeItemRow(
    node: NodeRowUi,
    onClick: () -> Unit
) {
    val p = LocalApplePalette.current
    val delayColor = when {
        node.delay <= 0 -> p.textTertiary
        node.delay < 160 -> p.accentGreen
        node.delay < 360 -> p.accentOrange
        else -> p.accentRed
    }
    val delayLabel = if (node.delay > 0) "${node.delay} ms" else "未测速"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (node.selected) p.accentBlue.copy(alpha = if (p.dark) 0.22f else 0.10f)
                else p.cardSubtle
            )
            .border(
                width = if (node.selected) 1.dp else 0.dp,
                color = if (node.selected) p.accentBlue else Color.Transparent,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (node.selected) p.accentBlue else p.textTertiary.copy(alpha = 0.35f))
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.name,
                    color = p.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = if (node.selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (node.type.isNotEmpty()) {
                    Text(
                        text = node.type,
                        color = p.textSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        StatusPill(
            label = delayLabel,
            color = delayColor,
            showDot = node.delay > 0
        )
    }
}

@Composable
fun ConnectionsPage(
    state: AppUiState,
    selectedClashId: Int,
    onSelectClashId: (Int) -> Unit,
    paused: Boolean,
    feedback: String,
    onTogglePause: () -> Unit,
    onRefreshNow: () -> Unit,
    onCloseConnection: (Int, String) -> Unit,
    onCloseAllConnections: (Int) -> Unit,
    onUpdateController: (Int, Boolean, Int) -> Unit,
    onRotateSecret: (Int) -> Unit,
    onCopyText: (String, String) -> Unit
) {
    val p = LocalApplePalette.current
    val scroll = rememberScrollState()
    val core = state.clashCore(selectedClashId)
    val activeConnections = if (state.connectionsCoreId == core.id) state.connections else emptyList()
    val activeUpload = if (state.connectionsCoreId == core.id) state.uploadTotal else 0L
    val activeDownload = if (state.connectionsCoreId == core.id) state.downloadTotal else 0L
    val activeMemory = if (state.connectionsCoreId == core.id) state.memoryBytes else 0L

    var protoFilter by rememberSaveable { mutableStateOf("ALL") }
    var searchFilter by rememberSaveable { mutableStateOf("") }
    var portText by rememberSaveable(core.id, core.controllerPort) {
        mutableStateOf(core.controllerPort.toString())
    }
    var showSecret by rememberSaveable(core.id) { mutableStateOf(false) }

    val filteredConnections = remember(activeConnections, protoFilter, searchFilter) {
        val q = searchFilter.trim().lowercase()
        activeConnections.filter { c ->
            val protoMatch = when (protoFilter) {
                "UDP" -> c.network.equals("udp", ignoreCase = true)
                "TCP" -> c.network.equals("tcp", ignoreCase = true)
                else -> true
            }
            val textMatch = q.isEmpty() ||
                c.destination.lowercase().contains(q) ||
                c.source.lowercase().contains(q) ||
                c.chainSummary.lowercase().contains(q)
            protoMatch && textMatch
        }
    }

    val endpointHost = if (core.externalLan && core.lanIpv4.isNotEmpty()) {
        core.lanIpv4
    } else {
        "127.0.0.1"
    }
    val endpointUrl = "http://$endpointHost:${core.controllerPort}"
    val accent = if (core.id == 0) p.accentBlue else p.accentPurple

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .statusBarsPadding()
            .padding(top = 8.dp, bottom = 116.dp)
    ) {
        PageHeader(
            title = "连接",
            subtitle = "双内核流量与连接管理"
        )
        Spacer(Modifier.height(8.dp))

        ClashCoreSelector(
            cores = state.clashCores,
            selectedId = core.id,
            onSelect = onSelectClashId
        )
        Spacer(Modifier.height(10.dp))

        AppleHeroCard(accent = accent) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(
                    label = if (!core.running) "内核 ${core.id + 1} 未运行"
                    else if (paused) "已暂停刷新"
                    else "自动刷新中 · ${core.tunDevice}",
                    color = if (!core.running) p.textTertiary
                    else if (paused) p.accentOrange
                    else p.accentGreen
                )
                if (activeMemory > 0) {
                    StatusPill(
                        label = "内存 ${formatBytes(activeMemory)}",
                        color = accent,
                        showDot = false
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCell(
                    label = "活跃连接",
                    value = "${activeConnections.size}",
                    valueColor = accent,
                    modifier = Modifier.weight(1f)
                )
                MetricCell(
                    label = "累计上传",
                    value = formatBytes(activeUpload),
                    modifier = Modifier.weight(1f)
                )
                MetricCell(
                    label = "累计下载",
                    value = formatBytes(activeDownload),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ApplePillButton(
                    text = if (paused) "继续刷新" else "暂停刷新",
                    onClick = onTogglePause,
                    primary = false,
                    modifier = Modifier.weight(1f)
                )
                ApplePillButton(
                    text = "立即刷新",
                    onClick = onRefreshNow,
                    primary = false,
                    enabled = !paused && core.running,
                    modifier = Modifier.weight(1f)
                )
                ApplePillButton(
                    text = "断开全部",
                    onClick = { onCloseAllConnections(core.id) },
                    primary = false,
                    danger = true,
                    enabled = activeConnections.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (feedback.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FeedbackBanner(text = feedback)
        }

        Spacer(Modifier.height(14.dp))
        AppleSectionTitle("内核 ${core.id + 1} 当前连接（${filteredConnections.size} / ${activeConnections.size}）")
        AppleMiuixCard(insideMargin = PaddingValues(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for ((key, label) in listOf("ALL" to "全部", "UDP" to "UDP", "TCP" to "TCP")) {
                    val active = protoFilter == key
                    ApplePillButton(
                        text = label,
                        onClick = { protoFilter = key },
                        primary = active,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            TextField(
                value = searchFilter,
                onValueChange = { searchFilter = it },
                label = "搜索地址、端口或节点",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            if (filteredConnections.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 22.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (!core.running) "内核 ${core.id + 1}（${core.tunDevice}）未运行"
                        else "暂无匹配的连接",
                        color = p.textSecondary,
                        fontSize = 14.sp
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (conn in filteredConnections) {
                        ConnectionItemCard(
                            conn = conn,
                            onClose = { onCloseConnection(core.id, conn.id) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        AppleSectionTitle("内核 ${core.id + 1} 外部控制")
        AppleMiuixCard(insideMargin = PaddingValues(bottom = 16.dp)) {
            SwitchPreference(
                title = "允许局域网控制",
                summary = if (core.externalLan)
                    "同一网络中的设备可凭密钥访问，请勿泄露密钥"
                else
                    "仅本机可访问，更安全",
                checked = core.externalLan,
                onCheckedChange = { enabled ->
                    val port = portText.toIntOrNull() ?: core.controllerPort
                    onUpdateController(core.id, enabled, port)
                }
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = portText,
                        onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
                        label = "端口（1024-65535）",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    ApplePillButton(
                        text = "应用端口",
                        onClick = {
                            val defaultPort = ClashConfig.spec(core.id).defaultControllerPort
                            val port = portText.toIntOrNull() ?: defaultPort
                            onUpdateController(core.id, core.externalLan, port)
                        },
                        primary = true
                    )
                }

                // Copyable Endpoint row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(p.cardSubtle)
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "控制地址（API）",
                            color = p.textSecondary,
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = endpointUrl,
                            color = p.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(p.card)
                            .clickable { onCopyText("API 地址", endpointUrl) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Copy,
                            contentDescription = "复制",
                            tint = p.accentBlue,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "复制",
                            color = p.accentBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Copyable Secret row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(p.cardSubtle)
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "访问密钥",
                            color = p.textSecondary,
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (core.controllerSecret.isEmpty()) "未生成"
                            else if (showSecret) core.controllerSecret
                            else "${core.controllerSecret.take(6)}••••••••••••••••••••",
                            color = p.textPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(p.card)
                                .clickable { showSecret = !showSecret }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (showSecret) "隐藏" else "显示",
                                color = p.textSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(p.card)
                                .clickable {
                                    if (core.controllerSecret.isNotEmpty()) {
                                        onCopyText("访问密钥", core.controllerSecret)
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "复制",
                                color = p.accentBlue,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "仅开放控制面板，不代理其他应用流量。",
                        color = p.textTertiary,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    ApplePillButton(
                        text = "重置密钥",
                        onClick = { onRotateSecret(core.id) },
                        primary = false
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionItemCard(
    conn: ConnectionRowUi,
    onClose: () -> Unit
) {
    val p = LocalApplePalette.current
    val isUdp = conn.network.equals("udp", ignoreCase = true)
    val protoColor = if (isUdp) p.accentBlue else p.accentPurple

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(p.cardSubtle)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(
                    label = conn.network.uppercase(),
                    color = protoColor,
                    showDot = false
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = conn.destination.ifEmpty { "未知目标" },
                    color = p.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(p.accentRed.copy(alpha = if (p.dark) 0.22f else 0.12f))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = "断开连接",
                    tint = p.accentRed,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "链路：${conn.chainSummary}",
            color = p.accentBlue,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "源 ${conn.source} · 规则 ${conn.rule.ifEmpty { "Match" }}",
                color = p.textSecondary,
                fontSize = 11.sp
            )
            Text(
                text = "↑ ${formatBytes(conn.upload)} · ↓ ${formatBytes(conn.download)}",
                color = p.textSecondary,
                fontSize = 11.sp,
                style = TextStyle(fontFeatureSettings = "tnum")
            )
        }
    }
}

@Composable
fun NetworkPage(
    state: AppUiState,
    logFeedback: String,
    onRefreshAll: () -> Unit,
    onRunCompatCheck: () -> Unit,
    onFastReconnect: () -> Unit,
    onEmergencyRestore: () -> Unit,
    onOpenRadioInfo: () -> Unit,
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
    val scroll = rememberScrollState()
    val tunCount = state.interfaces.count { it.voWifiTun }
    var endpointInput by rememberSaveable(state.telemetryEndpoint) {
        mutableStateOf(state.telemetryEndpoint)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .statusBarsPadding()
            .padding(top = 8.dp, bottom = 116.dp)
    ) {
        PageHeader(
            title = "网络",
            subtitle = "接口与日志"
        )
        Spacer(Modifier.height(8.dp))

        AppleHeroCard(accent = p.accentGreen) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(label = "网络信息", color = p.accentGreen)
                StatusPill(
                    label = if (state.appVersion.isNotEmpty()) "v${state.appVersion}" else "v0.6.0-dev",
                    color = p.accentBlue,
                    showDot = false
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCell(
                    label = "通话出口",
                    value = "$tunCount 个",
                    valueColor = if (tunCount > 0) p.accentGreen else p.accentOrange,
                    modifier = Modifier.weight(1f)
                )
                MetricCell(
                    label = "首选出口",
                    value = state.preferredVpnInterface.ifEmpty { "无" },
                    valueColor = p.accentBlue,
                    modifier = Modifier.weight(1f)
                )
                MetricCell(
                    label = "接口数量",
                    value = "${state.interfaces.size}",
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(12.dp))
            ApplePillButton(
                text = "刷新网络信息",
                onClick = onRefreshAll,
                primary = false,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(16.dp))
        AppleSectionTitle("系统接口兼容性自检（Android 14–17）")
        AppleMiuixCard(insideMargin = PaddingValues(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.compatSummary.ifEmpty { "检测 Root、常驻守护、IMS 覆盖、单卡蜂窝与跨基带 SIM AKA 接口" },
                    color = p.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                if (state.compatMode.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    val modeColor = when (state.compatMode) {
                        "READY" -> p.accentGreen
                        "DEGRADED_COMPAT" -> p.accentOrange
                        else -> p.accentRed
                    }
                    StatusPill(label = state.compatMode, color = modeColor, showDot = false)
                }
            }
            if (state.compatItems.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (item in state.compatItems) {
                        val badgeColor = when (item.status) {
                            "OK" -> p.accentGreen
                            "WARN" -> p.accentOrange
                            else -> p.accentRed
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(p.cardSubtle)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.label,
                                    color = p.textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = item.detail,
                                    color = p.textSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            StatusPill(label = item.status, color = badgeColor, showDot = false)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            ApplePillButton(
                text = "运行系统接口兼容性自检",
                onClick = onRunCompatCheck,
                primary = false,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(16.dp))
        AppleSectionTitle("系统工具与快捷恢复")
        AppleMiuixCard(insideMargin = PaddingValues(16.dp)) {
            Text(
                text = "切网/换节点后可触发秒级隧道重连；遇到异常时可带通话保护一键恢复原厂蜂窝与 IMS，或直达系统 RadioInfo 工程页。",
                color = p.textSecondary,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ApplePillButton(
                    text = "秒级重连隧道",
                    onClick = onFastReconnect,
                    primary = false,
                    modifier = Modifier.weight(1f)
                )
                ApplePillButton(
                    text = "系统 RadioInfo",
                    onClick = onOpenRadioInfo,
                    primary = false,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            ApplePillButton(
                text = "一键恢复原厂蜂窝与 IMS",
                onClick = onEmergencyRestore,
                primary = false,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(16.dp))
        AppleSectionTitle("日志与遥测")
        AppleMiuixCard(insideMargin = PaddingValues(bottom = 16.dp)) {
            SwitchPreference(
                title = "上传脱敏遥测日志",
                summary = "默认关闭。上传脱敏后的完整原始日志、网络请求包体与 SIM 国家/运营商，隐私字段统一显示为 [Hidden]",
                checked = state.telemetryEnabled,
                onCheckedChange = onToggleTelemetry
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = "上传链路",
                    color = p.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "默认走内置 Wi-Fi Calling 代理链路（免系统代理）；未启用内置代理时可切换为系统路由",
                    color = p.textSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isWifiCall = state.telemetryRoute != "system"
                    ApplePillButton(
                        text = "Wi-Fi Calling 链路",
                        onClick = { onSelectTelemetryRoute("wificall") },
                        primary = isWifiCall,
                        modifier = Modifier.weight(1f)
                    )
                    ApplePillButton(
                        text = "系统路由",
                        onClick = { onSelectTelemetryRoute("system") },
                        primary = !isWifiCall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = "上传接口地址（Cloudflare Worker → 私有 GitHub 仓库）",
                    color = p.textSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = endpointInput,
                    onValueChange = { endpointInput = it },
                    label = "https://smartvowificall-telemetry.yubainya.workers.dev/v1/telemetry",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.telemetryLastStatus.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "最近上传：${state.telemetryLastStatus}",
                        color = p.textSecondary,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ApplePillButton(
                        text = "保存地址",
                        onClick = { onSaveTelemetryEndpoint(endpointInput) },
                        primary = false,
                        modifier = Modifier.weight(1f)
                    )
                    ApplePillButton(
                        text = "预览脱敏内容",
                        onClick = onPreviewTelemetry,
                        primary = false,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                ApplePillButton(
                    text = "立即上传一次脱敏日志",
                    onClick = onUploadTelemetryNow,
                    primary = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (state.telemetryHistory.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = "日志上传记录（提供日志 ID 即可查询）",
                        color = p.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (item in state.telemetryHistory.take(8)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(p.cardSubtle)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = item.logId.ifEmpty { "无 ID" },
                                            color = p.textPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        StatusPill(
                                            label = if (item.ok) "成功" else "失败",
                                            color = if (item.ok) p.accentGreen else p.accentRed,
                                            showDot = false
                                        )
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    val detail = buildString {
                                        append(item.timeLabel)
                                        append(" · ")
                                        append(item.triggerLabel)
                                        if (item.sizeLabel.isNotEmpty()) {
                                            append(" · ")
                                            append(item.sizeLabel)
                                        }
                                        if (!item.ok && item.code.isNotEmpty()) {
                                            append(" · ")
                                            append(item.code)
                                        }
                                    }
                                    Text(
                                        text = detail,
                                        color = p.textSecondary,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (item.logId.isNotEmpty()) {
                                    Spacer(Modifier.width(8.dp))
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(p.accentBlue.copy(alpha = if (p.dark) 0.20f else 0.10f))
                                            .clickable { onCopyLogId(item.logId) }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = MiuixIcons.Copy,
                                            contentDescription = "复制 ID",
                                            tint = p.accentBlue,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "复制 ID",
                                            color = p.accentBlue,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = "手动导出全部日志",
                    color = p.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "打包应用诊断、运行事件、引擎日志与系统通话日志到本地 ZIP",
                    color = p.textSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ApplePillButton(
                        text = "导出脱敏日志",
                        onClick = onExportSanitizedLogs,
                        primary = false,
                        modifier = Modifier.weight(1f)
                    )
                    ApplePillButton(
                        text = "导出原始全部日志",
                        onClick = onExportRawLogs,
                        primary = false,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (logFeedback.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FeedbackBanner(text = logFeedback)
        }

        Spacer(Modifier.height(16.dp))
        AppleSectionTitle("网络接口（${state.interfaces.size}）")
        AppleMiuixCard(insideMargin = PaddingValues(16.dp)) {
            val sorted = remember(state.interfaces) {
                state.interfaces.sortedWith(
                    compareByDescending<InterfaceRowUi> { it.name == ClashConfig.TUN_DEVICE }
                        .thenByDescending { it.name == ClashConfig.TUN_DEVICE_1 }
                        .thenByDescending { it.voWifiTun }
                        .thenByDescending { it.available }
                        .thenBy { it.name }
                )
            }
            if (sorted.isEmpty()) {
                Text(
                    text = "正在读取网络信息…",
                    color = p.textSecondary,
                    fontSize = 14.sp
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (iface in sorted) {
                        val builtinCid = ClashConfig.instanceForTun(iface.name)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(p.cardSubtle)
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = iface.name,
                                        color = p.textPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    StatusPill(
                                        label = if (builtinCid >= 0) "内置内核 ${builtinCid + 1}" else iface.type,
                                        color = when {
                                            builtinCid == 0 -> p.accentBlue
                                            builtinCid == 1 -> p.accentPurple
                                            iface.voWifiTun -> p.accentGreen
                                            else -> p.textSecondary
                                        },
                                        showDot = false
                                    )
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "${if (iface.up == true) "已启用" else if (iface.up == false) "未启用" else "未知"} · ${iface.addresses.ifEmpty { "无 IP 地址" }}",
                                    color = p.textSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            StatusPill(
                                label = if (iface.voWifiTun) "可作通话出口" else "不可作通话出口",
                                color = if (iface.voWifiTun) p.accentGreen else p.textTertiary,
                                showDot = iface.voWifiTun
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun connectionStateLabel(state: String): String = when (state) {
    "IDLE", "DISCONNECTED" -> "未连接"
    "CONNECTING" -> "正在连接"
    "CONNECTED" -> "已连接"
    "REGISTERING" -> "正在注册"
    "REGISTERED" -> "已注册"
    "RECONNECTING" -> "正在重连"
    "BACKOFF" -> "重试中"
    "UNKNOWN" -> "未知"
    else -> state
}

@Composable
private fun FeedbackBanner(text: String) {
    val p = LocalApplePalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(p.accentBlue.copy(alpha = if (p.dark) 0.20f else 0.10f))
            .border(0.5.dp, p.accentBlue.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = p.accentBlue,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

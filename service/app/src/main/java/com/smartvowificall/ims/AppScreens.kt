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
    val ready: Boolean,
    val foreignEligible: Boolean,
    val mainland: Boolean,
    val selectedInterface: String,
    val radioOff: Boolean,
    val featureAttached: Boolean,
    val providerBound: Boolean,
    val imsState: String,
    val registered: Boolean,
    val tunnelState: String,
    val voiceServiceOverride: Boolean
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
data class AppUiState(
    val appVersion: String = "",
    val enabled: Boolean = false,
    val billable: Boolean = false,
    val statusText: String = "正在读取设备…",
    val clashEnabled: Boolean = false,
    val clashHasProfile: Boolean = false,
    val clashRunning: Boolean = false,
    val clashTunUp: Boolean = false,
    val clashCurrentNode: String = "",
    val clashSavedNode: String = "",
    val clashNetnsId: String = "",
    val clashSubscriptionUrl: String = "",
    val clashExternalLan: Boolean = false,
    val clashControllerPort: Int = ClashConfig.CONTROLLER_PORT,
    val clashControllerSecret: String = "",
    val clashLanIpv4: String = "",
    val clashLastError: String = "",
    val preferredVpnInterface: String = "",
    val sims: List<SimCardUi> = emptyList(),
    val interfaces: List<InterfaceRowUi> = emptyList(),
    val nodes: List<NodeRowUi> = emptyList(),
    val connectionsRunning: Boolean = false,
    val downloadTotal: Long = 0L,
    val uploadTotal: Long = 0L,
    val memoryBytes: Long = 0L,
    val connections: List<ConnectionRowUi> = emptyList(),
    val connectionsError: String = "",
    val telemetryEnabled: Boolean = false,
    val telemetryEndpoint: String = "",
    val telemetryLastStatus: String = ""
)

@Composable
fun OverviewPage(
    state: AppUiState,
    actionFeedback: String,
    onToggleEnabled: (Boolean) -> Unit,
    onRequestBillableToggle: (Boolean) -> Unit,
    onSelectSimInterface: (String, String) -> Unit,
    onUseBuiltinTunForForeign: () -> Unit,
    onRefreshAll: () -> Unit
) {
    val p = LocalApplePalette.current
    val scroll = rememberScrollState()
    var pickerSim by remember { mutableStateOf<SimCardUi?>(null) }

    val foreignSims = state.sims.filter { it.foreignEligible }
    val anyRegistered = foreignSims.any { it.registered }
    val anyConnecting = foreignSims.any { !it.registered && it.tunnelState == "CONNECTING" }
    val heroAccent = when {
        !state.enabled -> p.textSecondary
        anyRegistered -> p.accentGreen
        anyConnecting -> p.accentBlue
        else -> p.accentOrange
    }
    val heroBadge = when {
        !state.enabled -> "蜂窝模式"
        anyRegistered -> "已连接"
        anyConnecting -> "正在连接"
        else -> "未连接"
    }
    val heroHeadline = when {
        !state.enabled -> "正在使用蜂窝网络"
        anyRegistered -> "网络通话已连接"
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
            subtitle = "通话与短信",
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

        AppleHeroCard(accent = heroAccent) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(label = heroBadge, color = heroAccent)
                if (state.clashRunning && state.clashTunUp) {
                    StatusPill(
                        label = "代理已就绪",
                        color = p.accentBlue,
                        showDot = false
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = heroHeadline,
                color = p.textPrimary,
                fontSize = 24.sp,
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

            val primaryForeign = foreignSims.firstOrNull()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCell(
                    label = "IMS 状态",
                    value = primaryForeign?.imsState?.let(::connectionStateLabel) ?: if (state.enabled) "待连接" else "系统默认",
                    valueColor = if (anyRegistered) p.accentGreen else p.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                MetricCell(
                    label = "ePDG 隧道",
                    value = primaryForeign?.tunnelState?.let(::connectionStateLabel) ?: "未连接",
                    valueColor = if (primaryForeign?.tunnelState == "CONNECTED") p.accentBlue else p.textPrimary,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCell(
                    label = "此卡蜂窝网络",
                    value = if (primaryForeign?.radioOff == true) "已关闭蜂窝" else "蜂窝开启",
                    valueColor = if (primaryForeign?.radioOff == true) p.accentGreen else p.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                MetricCell(
                    label = "当前出口节点",
                    value = state.clashCurrentNode.ifEmpty {
                        primaryForeign?.selectedInterface?.ifEmpty { "未选择" } ?: "未选择"
                    },
                    valueColor = p.textPrimary,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (actionFeedback.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FeedbackBanner(text = actionFeedback)
        }

        Spacer(Modifier.height(14.dp))
        AppleSectionTitle("通话设置")
        AppleMiuixCard {
            SwitchPreference(
                title = "海外卡网络通话",
                summary = "关闭此卡蜂窝网络，普通通话不会回退蜂窝",
                checked = state.enabled,
                onCheckedChange = onToggleEnabled
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchPreference(
                title = "允许通话和发送短信",
                summary = if (state.billable) "可使用系统电话和短信，费用按套餐收取" else "仅检查连接，不可拨打电话或发送短信",
                checked = state.billable,
                onCheckedChange = onRequestBillableToggle
            )
        }

        Spacer(Modifier.height(16.dp))
        AppleSectionTitle("SIM 卡与网络")

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
                    onPickInterface = { pickerSim = sim },
                    onQuickUseBuiltin = onUseBuiltinTunForForeign
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
    onPickInterface: () -> Unit,
    onQuickUseBuiltin: () -> Unit
) {
    val p = LocalApplePalette.current
    val badgeText = when {
        sim.foreignEligible -> "海外卡"
        sim.mainland -> "大陆卡"
        else -> "待识别"
    }
    val badgeColor = when {
        sim.foreignEligible && sim.registered -> p.accentGreen
        sim.foreignEligible -> p.accentBlue
        else -> p.textSecondary
    }

    AppleMiuixCard(insideMargin = PaddingValues(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "卡槽 ${sim.slotId + 1}",
                    color = p.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "subId ${sim.subId} · PLMN ${sim.homePlmn.ifEmpty { "未知" }}",
                    color = p.textSecondary,
                    fontSize = 12.sp,
                    style = TextStyle(fontFeatureSettings = "tnum")
                )
            }
            StatusPill(label = badgeText, color = badgeColor)
        }

        if (!sim.foreignEligible) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "此卡继续使用系统通话和蜂窝网络。",
                color = p.textSecondary,
                fontSize = 13.sp
            )
        } else {
            Spacer(Modifier.height(14.dp))
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
                MetricCell(
                    label = "语音服务",
                    value = if (sim.voiceServiceOverride) "通话可用" else "未就绪",
                    valueColor = if (sim.voiceServiceOverride) p.accentGreen else p.textSecondary,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))
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
                        text = "通话出口",
                        color = p.textSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when {
                            sim.selectedInterface.isEmpty() -> "点击选择"
                            sim.selectedInterface == ClashConfig.TUN_DEVICE ->
                                "内置代理 · ${ClashConfig.TUN_DEVICE}"
                            else -> sim.selectedInterface
                        },
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

            if (sim.selectedInterface != ClashConfig.TUN_DEVICE) {
                Spacer(Modifier.height(10.dp))
                ApplePillButton(
                    text = "使用内置代理",
                    onClick = onQuickUseBuiltin,
                    primary = false,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun InterfacePickerDialog(
    sim: SimCardUi,
    interfaces: List<InterfaceRowUi>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val p = LocalApplePalette.current
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
                text = "仅支持 TUN 接口，推荐内置代理。",
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
                val sorted = interfaces.sortedWith(
                    compareByDescending<InterfaceRowUi> { it.name == ClashConfig.TUN_DEVICE }
                        .thenByDescending { it.voWifiTun }
                        .thenByDescending { it.available }
                        .thenBy { it.name }
                )
                for (item in sorted) {
                    val isSelected = item.name == sim.selectedInterface
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
                                    label = if (item.voWifiTun) "${item.type} · 可作出口" else "${item.type} · 不可用",
                                    color = if (item.voWifiTun) p.accentGreen else p.textTertiary,
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
fun ProxyPage(
    state: AppUiState,
    clashFeedback: String,
    onToggleClash: (Boolean) -> Unit,
    onFetchSubscription: (String) -> Unit,
    onImportYaml: () -> Unit,
    onReloadLocal: () -> Unit,
    onSelectNode: (String) -> Unit,
    onTestDelay: () -> Unit,
    onRestartCore: () -> Unit,
    onUseBuiltinTun: () -> Unit
) {
    val p = LocalApplePalette.current
    val scroll = rememberScrollState()
    var urlInput by rememberSaveable(state.clashSubscriptionUrl) {
        mutableStateOf(state.clashSubscriptionUrl)
    }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val heroColor = when {
        !state.clashEnabled -> p.textSecondary
        state.clashRunning && state.clashTunUp -> p.accentBlue
        state.clashRunning -> p.accentOrange
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
            title = "内置代理",
            subtitle = "节点与订阅"
        )
        Spacer(Modifier.height(8.dp))

        AppleHeroCard(accent = heroColor) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(
                    label = when {
                        !state.clashEnabled -> "自动代理已关闭"
                        state.clashRunning && state.clashTunUp -> "已就绪"
                        state.clashRunning -> "正在启动"
                        state.clashHasProfile -> if (state.enabled) "待启动" else "等待通话服务"
                        else -> "请导入配置"
                    },
                    color = heroColor
                )
                StatusPill(
                    label = "通话专用",
                    color = p.accentGreen,
                    showDot = false
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.clashCurrentNode.ifEmpty {
                    state.clashSavedNode.ifEmpty { "未选择节点" }
                },
                color = p.textPrimary,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "仅用于网络通话，不代理其他应用。",
                color = p.textSecondary,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ApplePillButton(
                    text = "节点测速",
                    onClick = onTestDelay,
                    primary = true,
                    enabled = state.clashRunning,
                    modifier = Modifier.weight(1f)
                )
                ApplePillButton(
                    text = "重启代理",
                    onClick = onRestartCore,
                    primary = false,
                    enabled = state.clashHasProfile,
                    modifier = Modifier.weight(1f)
                )
                ApplePillButton(
                    text = "设为出口",
                    onClick = onUseBuiltinTun,
                    primary = false,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (clashFeedback.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FeedbackBanner(text = clashFeedback)
        }

        Spacer(Modifier.height(14.dp))
        AppleSectionTitle("代理设置")
        AppleMiuixCard(insideMargin = PaddingValues(bottom = 16.dp)) {
            SwitchPreference(
                title = "自动代理",
                summary = "随网络通话服务启动和停止",
                checked = state.clashEnabled,
                onCheckedChange = onToggleClash
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
                    label = "输入 Clash 订阅地址",
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
                        onClick = { onFetchSubscription(urlInput.trim()) },
                        primary = true,
                        modifier = Modifier.weight(1f)
                    )
                    ApplePillButton(
                        text = "导入 YAML",
                        onClick = onImportYaml,
                        primary = false,
                        modifier = Modifier.weight(1f)
                    )
                    ApplePillButton(
                        text = "重新加载",
                        onClick = onReloadLocal,
                        primary = false,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "支持订阅链接或 YAML 配置。",
                    color = p.textTertiary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        AppleSectionTitle("代理节点（${state.nodes.size}）")
        AppleMiuixCard(insideMargin = PaddingValues(16.dp)) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = "搜索节点或协议",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            val filtered = remember(state.nodes, searchQuery) {
                val q = searchQuery.trim().lowercase()
                if (q.isEmpty()) state.nodes
                else state.nodes.filter {
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
                        text = if (state.nodes.isEmpty()) "请导入订阅并开启网络通话"
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
                            onClick = { onSelectNode(node.name) }
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
    paused: Boolean,
    feedback: String,
    onTogglePause: () -> Unit,
    onRefreshNow: () -> Unit,
    onCloseConnection: (String) -> Unit,
    onCloseAllConnections: () -> Unit,
    onUpdateController: (Boolean, Int) -> Unit,
    onRotateSecret: () -> Unit,
    onCopyText: (String, String) -> Unit
) {
    val p = LocalApplePalette.current
    val scroll = rememberScrollState()
    var protoFilter by rememberSaveable { mutableStateOf("ALL") }
    var searchFilter by rememberSaveable { mutableStateOf("") }
    var portText by rememberSaveable(state.clashControllerPort) {
        mutableStateOf(state.clashControllerPort.toString())
    }
    var showSecret by rememberSaveable { mutableStateOf(false) }

    val filteredConnections = remember(state.connections, protoFilter, searchFilter) {
        val q = searchFilter.trim().lowercase()
        state.connections.filter { c ->
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

    val endpointHost = if (state.clashExternalLan && state.clashLanIpv4.isNotEmpty()) {
        state.clashLanIpv4
    } else {
        "127.0.0.1"
    }
    val endpointUrl = "http://$endpointHost:${state.clashControllerPort}"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .statusBarsPadding()
            .padding(top = 8.dp, bottom = 116.dp)
    ) {
        PageHeader(
            title = "连接",
            subtitle = "流量与连接管理"
        )
        Spacer(Modifier.height(8.dp))

        AppleHeroCard(accent = p.accentBlue) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(
                    label = if (!state.clashRunning) "代理未运行"
                    else if (paused) "已暂停刷新"
                    else "自动刷新中",
                    color = if (!state.clashRunning) p.textTertiary
                    else if (paused) p.accentOrange
                    else p.accentGreen
                )
                if (state.memoryBytes > 0) {
                    StatusPill(
                        label = "内存 ${formatBytes(state.memoryBytes)}",
                        color = p.accentBlue,
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
                    value = "${state.connections.size}",
                    valueColor = p.accentBlue,
                    modifier = Modifier.weight(1f)
                )
                MetricCell(
                    label = "累计上传",
                    value = formatBytes(state.uploadTotal),
                    modifier = Modifier.weight(1f)
                )
                MetricCell(
                    label = "累计下载",
                    value = formatBytes(state.downloadTotal),
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
                    enabled = !paused && state.clashRunning,
                    modifier = Modifier.weight(1f)
                )
                ApplePillButton(
                    text = "断开全部",
                    onClick = onCloseAllConnections,
                    primary = false,
                    danger = true,
                    enabled = state.connections.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (feedback.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FeedbackBanner(text = feedback)
        }

        Spacer(Modifier.height(14.dp))
        AppleSectionTitle("当前连接（${filteredConnections.size} / ${state.connections.size}）")
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
                        text = if (!state.clashRunning) "代理未运行"
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
                            onClose = { onCloseConnection(conn.id) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        AppleSectionTitle("外部控制")
        AppleMiuixCard(insideMargin = PaddingValues(bottom = 16.dp)) {
            SwitchPreference(
                title = "允许局域网控制",
                summary = if (state.clashExternalLan)
                    "同一网络中的设备可凭密钥访问，请勿泄露密钥"
                else
                    "仅本机可访问，更安全",
                checked = state.clashExternalLan,
                onCheckedChange = { enabled ->
                    val port = portText.toIntOrNull() ?: state.clashControllerPort
                    onUpdateController(enabled, port)
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
                            val port = portText.toIntOrNull() ?: ClashConfig.CONTROLLER_PORT
                            onUpdateController(state.clashExternalLan, port)
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
                            text = if (state.clashControllerSecret.isEmpty()) "未生成"
                            else if (showSecret) state.clashControllerSecret
                            else "${state.clashControllerSecret.take(6)}••••••••••••••••••••",
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
                                    if (state.clashControllerSecret.isNotEmpty()) {
                                        onCopyText("访问密钥", state.clashControllerSecret)
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
                        onClick = onRotateSecret,
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
    onToggleTelemetry: (Boolean) -> Unit,
    onSaveTelemetryEndpoint: (String) -> Unit,
    onUploadTelemetryNow: () -> Unit,
    onPreviewTelemetry: () -> Unit,
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
                    label = if (state.appVersion.isNotEmpty()) "v${state.appVersion}" else "v0.4.6-dev",
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
        AppleSectionTitle("日志与遥测")
        AppleMiuixCard(insideMargin = PaddingValues(bottom = 16.dp)) {
            SwitchPreference(
                title = "上传脱敏遥测日志",
                summary = "默认关闭。仅上传版本、机型与连接状态，不含卡号、号码、订阅和节点名",
                checked = state.telemetryEnabled,
                onCheckedChange = onToggleTelemetry
            )
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
                                        label = iface.type,
                                        color = if (iface.name == ClashConfig.TUN_DEVICE) p.accentBlue
                                        else if (iface.voWifiTun) p.accentGreen
                                        else p.textSecondary,
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

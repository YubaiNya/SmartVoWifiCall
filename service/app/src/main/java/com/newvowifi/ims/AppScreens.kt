package com.newvowifi.ims

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
import com.newvowifi.ims.clash.ClashConfig
import com.newvowifi.ims.network.InterfaceSnapshot
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
    val connectionsError: String = ""
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
        !state.enabled -> "蜂窝模式 · 未接管"
        anyRegistered -> "Wi-Fi Calling 已就绪"
        anyConnecting -> "正在建立加密隧道"
        else -> "蜂窝已关闭 · 等待注册"
    }
    val heroHeadline = when {
        !state.enabled -> "纯粹蜂窝，按需切换。"
        anyRegistered -> "无线通话，现已就绪。"
        else -> "专属隧道，静候连接。"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .statusBarsPadding()
            .padding(top = 8.dp, bottom = 116.dp)
    ) {
        PageHeader(
            title = "NewVoWiFi",
            subtitle = "海外卡独立 IMS · 大陆卡保留原厂服务",
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
                        label = "${ClashConfig.TUN_DEVICE} 隔离运行",
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
                    value = primaryForeign?.imsState ?: if (state.enabled) "待接入" else "系统默认",
                    valueColor = if (anyRegistered) p.accentGreen else p.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                MetricCell(
                    label = "ePDG 隧道",
                    value = primaryForeign?.tunnelState ?: "未启动",
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
                    label = "单卡蜂窝隔离",
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
        AppleSectionTitle("核心策略")
        AppleMiuixCard {
            SwitchPreference(
                title = "海外卡 Wi-Fi Calling",
                summary = "开启后关闭目标卡蜂窝协议栈，普通呼叫不回退蜂窝",
                checked = state.enabled,
                onCheckedChange = onToggleEnabled
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchPreference(
                title = "允许通话和发送短信",
                summary = if (state.billable) "已启用系统拨号器与短信业务通道" else "验证模式：仅验证注册，禁止呼出与发短信",
                checked = state.billable,
                onCheckedChange = onRequestBillableToggle
            )
        }

        Spacer(Modifier.height(16.dp))
        AppleSectionTitle("SIM 卡与出口绑定")

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
        sim.foreignEligible -> "海外归属卡 · 独立接管"
        sim.mainland -> "大陆归属卡 · 原厂 IMS"
        else -> "身份待确认 · 原厂 IMS"
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
                text = "此卡保留系统原厂 IMS 与蜂窝网络，本模块不接管、不改动其射频与通话路由。",
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
                    value = sim.tunnelState,
                    valueColor = if (sim.tunnelState == "CONNECTED") p.accentBlue else p.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                MetricCell(
                    label = "IMS",
                    value = sim.imsState,
                    valueColor = if (sim.registered) p.accentGreen else p.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                MetricCell(
                    label = "语音状态同步",
                    value = if (sim.voiceServiceOverride) "IN_SERVICE" else "待命",
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
                        text = "VoWiFi 出口 TUN 接口",
                        color = p.textSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when {
                            sim.selectedInterface.isEmpty() -> "未选择（点击选择 TUN 出口）"
                            sim.selectedInterface == ClashConfig.TUN_DEVICE ->
                                "${ClashConfig.TUN_DEVICE} · 内置 Clash（隔离命名空间）"
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
                    text = "一键切换至内置 ${ClashConfig.TUN_DEVICE}",
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
                    text = "卡槽 ${sim.slotId + 1} · 选择 VoWiFi 出口",
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
                text = "仅真实 Linux TUN 接口可用作 VoWiFi 出口。推荐使用内置隔离 ${ClashConfig.TUN_DEVICE}。",
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
                                text = "${if (item.up == true) "UP" else "DOWN"} · ${item.addresses.ifEmpty { "无 IP" }}",
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
            subtitle = "Mihomo 内核 · 仅代理 Wi-Fi Calling 流量"
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
                        !state.clashEnabled -> "已停用"
                        state.clashRunning && state.clashTunUp -> "${ClashConfig.TUN_DEVICE} 隔离就绪"
                        state.clashRunning -> "正在启动 ${ClashConfig.TUN_DEVICE}"
                        state.clashHasProfile -> "已配置 · 待启动"
                        else -> "等待导入订阅或配置"
                    },
                    color = heroColor
                )
                StatusPill(
                    label = "零系统 VPN",
                    color = p.accentGreen,
                    showDot = false
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.clashCurrentNode.ifEmpty {
                    state.clashSavedNode.ifEmpty { "尚未选择出口节点" }
                },
                color = p.textPrimary,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "独立命名空间 ${state.clashNetnsId.ifEmpty { "待分配" }} · 根命名空间零路由侵入",
                color = p.textSecondary,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ApplePillButton(
                    text = "全组测速",
                    onClick = onTestDelay,
                    primary = true,
                    enabled = state.clashRunning,
                    modifier = Modifier.weight(1f)
                )
                ApplePillButton(
                    text = "重启内核",
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
        AppleSectionTitle("内核与订阅管理")
        AppleMiuixCard(insideMargin = PaddingValues(bottom = 16.dp)) {
            SwitchPreference(
                title = "启用内置 Clash 内核",
                summary = "在隔离命名空间内创建 ${ClashConfig.TUN_DEVICE}（198.19.0.1/30）",
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
                        text = "拉取订阅",
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
                        text = "重载本地",
                        onClick = onReloadLocal,
                        primary = false,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "本地路径：/data/adb/newvowifi_ims/clash/profile.yaml",
                    color = p.textTertiary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        AppleSectionTitle("WFC-PROXY 出口节点（${state.nodes.size}）")
        AppleMiuixCard(insideMargin = PaddingValues(16.dp)) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = "搜索节点名称或协议（如 香港 / AnyTLS）",
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
                        text = if (state.nodes.isEmpty()) "启用内置 Clash 并导入订阅后显示节点列表"
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
            title = "连接与调试",
            subtitle = "实时观测 wfctun0 会话 · 外部控制器配置"
        )
        Spacer(Modifier.height(8.dp))

        AppleHeroCard(accent = p.accentBlue) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(
                    label = if (!state.clashRunning) "内核未运行"
                    else if (paused) "实时刷新已暂停"
                    else "实时监控中（1.5s）",
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
        AppleSectionTitle("实时活跃连接（${filteredConnections.size} / ${state.connections.size}）")
        AppleMiuixCard(insideMargin = PaddingValues(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for ((key, label) in listOf("ALL" to "全部", "UDP" to "UDP (IKE/ESP/DNS)", "TCP" to "TCP")) {
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
                label = "过滤目标域名、IP、端口或出口节点",
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
                        text = if (!state.clashRunning) "内置 Clash 未运行"
                        else "当前无匹配的活跃连接",
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
        AppleSectionTitle("外部控制（External Controller 调试）")
        AppleMiuixCard(insideMargin = PaddingValues(bottom = 16.dp)) {
            SwitchPreference(
                title = "允许局域网外部控制",
                summary = if (state.clashExternalLan)
                    "已监听 0.0.0.0:${state.clashControllerPort}（支持 MetaCubeXD / Yacd / Zashboard）"
                else
                    "仅本机回环 127.0.0.1:${state.clashControllerPort}（默认最安全）",
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
                        label = "控制器端口（1024-65535）",
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
                            text = "RESTful API 地址",
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
                            text = "访问密钥（Secret）",
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
                                        onCopyText("控制器密钥", state.clashControllerSecret)
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
                        text = "仅开放控制面板 API；代理端口恒为 0，不代理手机其他流量。",
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
    onRefreshAll: () -> Unit
) {
    val p = LocalApplePalette.current
    val scroll = rememberScrollState()
    val tunCount = state.interfaces.count { it.voWifiTun }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .statusBarsPadding()
            .padding(top = 8.dp, bottom = 116.dp)
    ) {
        PageHeader(
            title = "网络与诊断",
            subtitle = "内核接口审查 · 命名空间隔离验证"
        )
        Spacer(Modifier.height(8.dp))

        AppleHeroCard(accent = p.accentGreen) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(label = "硬件级命名空间隔离", color = p.accentGreen)
                StatusPill(label = "v0.4.0-dev", color = p.accentBlue, showDot = false)
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCell(
                    label = "可用 TUN 出口",
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
                    label = "内核接口总数",
                    value = "${state.interfaces.size}",
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(12.dp))
            ApplePillButton(
                text = "立即刷新全部 SIM 与网络接口",
                onClick = onRefreshAll,
                primary = false,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(16.dp))
        AppleSectionTitle("全部内核网络接口（${state.interfaces.size}）")
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
                    text = "正在扫描内核网络接口…",
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
                                    text = "${if (iface.up == true) "UP" else if (iface.up == false) "DOWN" else "UNKNOWN"} · ${iface.addresses.ifEmpty { "无 IP 地址" }}",
                                    color = p.textSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            StatusPill(
                                label = if (iface.voWifiTun) "VoWiFi 出口" else "非 VoWiFi",
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

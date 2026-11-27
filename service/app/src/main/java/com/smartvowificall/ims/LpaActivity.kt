package com.smartvowificall.ims

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import com.smartvowificall.ims.lpa.LpaProtocol
import net.typeblog.lpac_jni.LocalProfileInfo
import top.yukonga.miuix.kmp.basic.Text

/** Standalone in-app LPA. Profile secrets never enter telemetry, activity saved state or clipboard. */
class LpaActivity : ComponentActivity() {
    private lateinit var model: LpaViewModel
    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val code = result.data?.getStringExtra(QrScannerActivity.EXTRA_RAW_TEXT).orEmpty()
            if (code.isNotEmpty()) downloadDialog(code)
        }
    }
    private val camera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) launchScanner() else downloadDialog("")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[LpaViewModel::class.java]
        setContent {
            SmartVoWifiCallTheme {
                BackHandler(enabled = model.state.busy) {
                    confirm("取消当前操作？", "请求可能已到达卡片。取消后需要重新读取配置确认结果。") { model.cancel() }
                }
                LpaPage(model.state, model::discover, model::select, model::refresh,
                    onPause = { confirm("暂停此卡网络通话？", "将先恢复原厂蜂窝。完成卡片管理后，可手动恢复网络通话。") { model.pauseVoWifi() } },
                    onResume = model::resumeVoWifi,
                    onScan = { if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchScanner()
                        else camera.launch(Manifest.permission.CAMERA) },
                    onInput = { downloadDialog("") },
                    onNotifications = { confirm("发送待处理通知？", "会向对应配置提供方发送卡内操作结果，使用系统网络连接。") { model.sendNotifications() } },
                    onAction = ::profileDialog, onCancel = model::cancel,
                    onBack = { if (!model.state.busy) finish() })
            }
        }
        if (savedInstanceState == null) model.discover()
    }

    private fun launchScanner() {
        scanner.launch(Intent(this, QrScannerActivity::class.java).putExtra(QrScannerActivity.EXTRA_LPA, true))
    }
    private fun confirm(title: String, message: String, accepted: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton("取消", null).setPositiveButton("确认") { _, _ -> accepted() }.show()
    }
    private fun input(hintText: String, secret: Boolean = false) = EditText(this).apply {
        hint = hintText; isSaveEnabled = false; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        setSingleLine(true)
    }

    private fun downloadDialog(initialCode: String) {
        if (model.state.busy || model.state.eid.isEmpty()) return
        val code = input("LPA:1\$服务器\$匹配码", true).apply { setText(initialCode) }
        val confirmation = input("确认码（运营商要求时填写）", true)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0); addView(code); addView(confirmation)
        }
        val dialog = AlertDialog.Builder(this).setTitle("下载 eSIM 配置")
            .setMessage("使用系统网络联系激活码指定的配置服务器。激活码可能只能使用一次；下载成功后不会自动启用。")
            .setView(form).setNegativeButton("取消", null).setPositiveButton("下载", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = code.text.toString().trim()
                try { LpaProtocol.activation(value) }
                catch (_: Exception) { code.error = "请输入支持的 LPA:1 格式激活码"; return@setOnClickListener }
                model.download(value, confirmation.text.toString())
                code.text.clear(); confirmation.text.clear(); dialog.dismiss()
            }
        }
        dialog.setOnDismissListener { code.text.clear(); confirmation.text.clear() }
        dialog.show()
    }

    private fun profileDialog(action: String, profile: LocalProfileInfo) {
        if (model.state.busy) return
        val label = profile.nickName.ifEmpty { profile.name.ifEmpty { LpaProtocol.mask(profile.iccid) } }
        when (action) {
            "rename" -> {
                val name = input("配置昵称").apply { setText(profile.nickName) }
                val dialog = AlertDialog.Builder(this).setTitle("修改昵称").setView(name)
                    .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
                dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    try { LpaProtocol.nickname(name.text.toString()) }
                    catch (_: Exception) { name.error = "请输入有效文字，最多 63 个 UTF-8 字节"; return@setOnClickListener }
                    model.profileAction(action, profile, name.text.toString()); dialog.dismiss()
                } }
                dialog.show()
            }
            "enable" -> confirm("启用 $label？", "会切换这张卡的当前配置，蜂窝服务可能短暂中断。") { model.profileAction(action, profile) }
            "disable" -> confirm("停用 $label？", "这张卡可能没有启用的配置，部分手机随后无法访问它，可能需要读卡器恢复。") { model.profileAction(action, profile) }
            "delete" -> confirm("永久删除 $label？", "删除无法撤销。激活码可能不能再次下载；本应用不能备份或恢复配置密钥。") { model.profileAction(action, profile) }
        }
    }
}

@Composable
private fun LpaPage(state: LpaUiState, onDiscover: () -> Unit, onSelect: (LpaCard) -> Unit,
    onRefresh: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onScan: () -> Unit,
    onInput: () -> Unit, onNotifications: () -> Unit, onAction: (String, LocalProfileInfo) -> Unit,
    onCancel: () -> Unit, onBack: () -> Unit) {
    val palette = LocalApplePalette.current
    Column(Modifier.fillMaxSize().background(palette.canvas).verticalScroll(rememberScrollState())
        .statusBarsPadding().navigationBarsPadding().padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader("可插拔 eSIM", "LPA 配置管理 · Root + OMAPI")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ApplePillButton("返回", onBack, primary = false, enabled = !state.busy, modifier = Modifier.weight(1f))
            ApplePillButton("刷新卡槽", onDiscover, enabled = !state.busy, modifier = Modifier.weight(1f))
        }
        AppleMiuixCard(insideMargin = PaddingValues(16.dp)) {
            Text(state.message, color = palette.textSecondary)
        }
        if (state.busy) AppleMiuixCard(insideMargin = PaddingValues(16.dp)) {
            ApplePillButton("请求取消", onCancel, primary = false)
        }
        for (card in state.cards) AppleMiuixCard(insideMargin = PaddingValues(16.dp)) {
            Text("卡槽 ${card.logicalSlot + 1}", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("可插拔卡 · 端口 ${card.port}", color = palette.textSecondary)
            Spacer(Modifier.height(8.dp))
            ApplePillButton(if (card == state.selected) "重新读取配置" else "读取 eSIM 配置",
                { onSelect(card) }, enabled = !state.busy)
        }
        if (state.selected != null) {
            AppleMiuixCard(insideMargin = PaddingValues(16.dp)) {
                Text("管理此卡前，请先暂停它的网络通话。", color = palette.textSecondary)
                Spacer(Modifier.height(8.dp))
                ApplePillButton("暂停此卡网络通话", onPause, primary = false, enabled = !state.busy)
                Spacer(Modifier.height(8.dp))
                ApplePillButton("完成管理，恢复网络通话", onResume, primary = false, enabled = !state.busy)
            }
        }
        if (state.eid.isNotEmpty()) {
            AppleMiuixCard(insideMargin = PaddingValues(16.dp)) {
                Text("EID ${LpaProtocol.mask(state.eid)}", fontWeight = FontWeight.Bold)
                state.freeBytes?.let { Text("卡内剩余空间：$it 字节", color = palette.textSecondary) }
                Text("配置 ${state.profiles.size} 个 · 待处理通知 ${state.pendingNotifications} 条", color = palette.textSecondary)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ApplePillButton("扫描二维码", onScan, enabled = !state.busy, modifier = Modifier.weight(1f))
                    ApplePillButton("输入激活码", onInput, primary = false, enabled = !state.busy, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                ApplePillButton("发送待处理通知", onNotifications, primary = false,
                    enabled = !state.busy && state.pendingNotifications > 0)
            }
            for (profile in state.profiles) AppleMiuixCard(insideMargin = PaddingValues(16.dp)) {
                val enabled = profile.state == LocalProfileInfo.State.Enabled
                val known = profile.state != LocalProfileInfo.State.Unknown
                Text(profile.nickName.ifEmpty { profile.name.ifEmpty { "未命名配置" } }, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(profile.providerName, color = palette.textSecondary)
                Text("${LpaProtocol.mask(profile.iccid)} · ${if (!known) "状态未知" else if (enabled) "已启用" else "已停用"}", color = palette.textSecondary)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ApplePillButton(if (enabled) "停用" else "启用", { onAction(if (enabled) "disable" else "enable", profile) },
                        enabled = !state.busy && known, modifier = Modifier.weight(1f))
                    ApplePillButton("改名", { onAction("rename", profile) }, primary = false,
                        enabled = !state.busy && known, modifier = Modifier.weight(1f))
                    ApplePillButton("删除", { onAction("delete", profile) }, danger = true,
                        enabled = !state.busy && profile.state == LocalProfileInfo.State.Disabled, modifier = Modifier.weight(1f))
                }
            }
            AppleMiuixCard(insideMargin = PaddingValues(16.dp)) {
                ApplePillButton("刷新配置状态", onRefresh, primary = false, enabled = !state.busy)
            }
        }
    }
}

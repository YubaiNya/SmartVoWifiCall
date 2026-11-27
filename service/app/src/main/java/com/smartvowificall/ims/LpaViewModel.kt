package com.smartvowificall.ims

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.smartvowificall.ims.lpa.LpaProtocol
import net.typeblog.lpac_jni.LocalProfileInfo
import net.typeblog.lpac_jni.ProfileDownloadInput
import net.typeblog.lpac_jni.impl.LocalProfileAssistantImpl
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class LpaCard(val physicalSlot: Int, val port: Int, val logicalSlot: Int)
internal data class LpaUiState(
    val cards: List<LpaCard> = emptyList(), val selected: LpaCard? = null,
    val eid: String = "", val profiles: List<LocalProfileInfo> = emptyList(),
    val freeBytes: Int? = null, val pendingNotifications: Int = 0,
    val busy: Boolean = false, val message: String = "点击刷新检测可插拔 eSIM 卡槽。"
)

internal class LpaViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(LpaUiState()); private set
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    @Volatile private var bridge: LpaRootBridge? = null
    @Volatile private var http: LpaHttp? = null
    @Volatile private var activeCard: LpaOmapi? = null
    private val app get() = getApplication<Application>()

    private fun work(message: String, block: () -> Unit) {
        if (!running.compareAndSet(false, true)) return
        cancelled.set(false)
        state = state.copy(busy = true, message = message)
        executor.execute {
            try { block() }
            catch (e: Throwable) {
                main.post { state = state.copy(message = errorText(e), profiles = emptyList(), eid = "", freeBytes = null) }
            } finally {
                activeCard?.close(); activeCard = null
                bridge?.close(); bridge = null; http = null
                running.set(false)
                main.post { state = state.copy(busy = false) }
            }
        }
    }

    fun discover() = work("正在检测可插拔卡槽…") {
        LpaRootBridge(app).use { root ->
            bridge = root
            val array = root.request("cards").getJSONArray("cards")
            val cards = (0 until array.length()).map { array.getJSONObject(it) }.map {
                LpaCard(it.getInt("physicalSlot"), it.getInt("port"), it.getInt("logicalSlot"))
            }.distinct()
            main.post { state = LpaUiState(cards = cards,
                message = if (cards.isEmpty()) "没有检测到可访问的可插拔卡槽；内置 eSIM 不在此页管理。"
                else "选择卡槽读取配置。普通 SIM 卡不会被当作 eSIM。") }
        }
    }

    fun select(card: LpaCard) {
        if (running.get()) return
        state = state.copy(selected = card, eid = "", profiles = emptyList(), freeBytes = null)
        refresh()
    }

    private fun <T> session(card: LpaCard, expectedEid: String? = null, action: (LocalProfileAssistantImpl) -> T): T {
        LpaRootBridge(app).use { root ->
            bridge = root
            val transport = LpaHttp(cancelled).also { http = it }
            LpaOmapi(app, card, root, cancelled).use { access ->
                activeCard = access
                val assistant = try {
                    LocalProfileAssistantImpl(LpaProtocol.unhex(LpaProtocol.ISDR_AID), access, transport)
                } catch (e: Exception) { throw IllegalStateException(root.failureCode ?: access.failureCode ?: e.message) }
                try {
                check(!cancelled.get()) { "LPA_CANCELLED" }
                val eid = assistant.eID
                check(eid.matches(Regex("[0-9]{32}"))) { "LPA_EID_INVALID" }
                check(expectedEid == null || eid == expectedEid) { "LPA_CARD_CHANGED" }
                return action(assistant)
                } catch (e: Exception) {
                    if (root.failureCode != null) throw IllegalStateException(root.failureCode)
                    if (access.failureCode != null) throw IllegalStateException(access.failureCode)
                    throw e
                } finally { assistant.close() }
            }
        }
    }

    fun refresh() {
        val card = state.selected ?: return
        work("正在读取卡内配置…") { read(card) }
    }

    private fun read(card: LpaCard, message: String = "已读取卡内配置。", expectedEid: String? = null,
        verified: (List<LocalProfileInfo>) -> Boolean = { true }) {
        session(card, expectedEid) { assistant ->
            val eid = assistant.eID
            val profiles = assistant.profiles
            val memory = assistant.euiccInfo2?.freeNvram
            val notifications = assistant.notifications.size
            val resultMessage = if (verified(profiles)) message else
                "卡片已回应，但读取到的状态尚未满足操作目标。请核对列表，不会自动重复操作。"
            main.post { state = state.copy(selected = card, eid = eid, profiles = profiles,
                freeBytes = memory, pendingNotifications = notifications, message = resultMessage) }
        }
    }

    fun pauseVoWifi() {
        val card = state.selected ?: return
        RuntimeController.get(app).setSlotEnabled(card.logicalSlot, false)
        state = state.copy(message = "已请求暂停此卡网络通话。等待蜂窝恢复后，点击读取配置。")
    }

    fun resumeVoWifi() {
        val card = state.selected ?: return
        if (running.get()) return
        RuntimeController.get(app).setSlotEnabled(card.logicalSlot, true)
        state = state.copy(message = "已请求恢复此卡网络通话。")
    }

    fun profileAction(action: String, profile: LocalProfileInfo, nickname: String = "") {
        val card = state.selected ?: return
        val expectedEid = state.eid.takeIf { it.isNotEmpty() } ?: return
        work("正在执行配置操作，请勿拔卡…") {
            session(card, expectedEid) { assistant ->
                val current = assistant.profiles.firstOrNull { it.iccid == profile.iccid }
                    ?: error("LPA_PROFILE_CHANGED")
                check(current.state != LocalProfileInfo.State.Unknown) { "LPA_PROFILE_STATE_UNKNOWN" }
                when (action) {
                    "enable" -> check(assistant.enableProfile(current.iccid, true)) { "LPA_PROFILE_REJECTED" }
                    "disable" -> check(assistant.disableProfile(current.iccid, true)) { "LPA_PROFILE_REJECTED" }
                    "delete" -> {
                        check(current.state == LocalProfileInfo.State.Disabled) { "LPA_DISABLE_BEFORE_DELETE" }
                        check(assistant.deleteProfile(current.iccid)) { "LPA_PROFILE_REJECTED" }
                    }
                    "rename" -> { LpaProtocol.nickname(nickname); assistant.setNickname(current.iccid, nickname) }
                    else -> error("LPA_ACTION_INVALID")
                }
            }
            // A profile switch can temporarily remove the port. Never repeat a successful mutation.
            try { read(card, "已重新读取卡片并确认操作结果；如有待处理通知，可手动发送。", expectedEid) { profiles ->
                val updated = profiles.firstOrNull { it.iccid == profile.iccid }
                when (action) {
                    "delete" -> updated == null
                    "enable" -> updated?.state == LocalProfileInfo.State.Enabled
                    "disable" -> updated?.state == LocalProfileInfo.State.Disabled
                    "rename" -> updated?.nickName == nickname
                    else -> false
                }
            } }
            catch (_: Exception) {
                main.post { state = state.copy(profiles = emptyList(), eid = "",
                    message = "操作已返回成功，卡槽正在刷新或暂不可访问。请稍后重新读取；不要重复删除或下载。") }
            }
        }
    }

    fun download(code: String, confirmation: String) {
        val card = state.selected ?: return
        val expectedEid = state.eid.takeIf { it.isNotEmpty() } ?: return
        work("正在下载配置，请勿拔卡…") {
            val activation = LpaProtocol.activation(code)
            require(confirmation.length <= 128 && !confirmation.any { it.isISOControl() }) { "LPA_CONFIRMATION_INVALID" }
            session(card, expectedEid) { assistant ->
                assistant.downloadProfile(ProfileDownloadInput(activation.server, activation.matchingId,
                    null, confirmation.takeIf { it.isNotEmpty() })) { step ->
                    val label = when (step) {
                        is net.typeblog.lpac_jni.ProfileDownloadState.Preparing -> "准备卡片"
                        is net.typeblog.lpac_jni.ProfileDownloadState.Connecting -> "连接配置服务器"
                        is net.typeblog.lpac_jni.ProfileDownloadState.Authenticating -> "验证服务器与卡片"
                        is net.typeblog.lpac_jni.ProfileDownloadState.ConfirmingDownload -> "确认下载"
                        is net.typeblog.lpac_jni.ProfileDownloadState.Downloading -> "下载配置"
                        is net.typeblog.lpac_jni.ProfileDownloadState.Finalizing -> "写入卡片"
                    }
                    main.post { state = state.copy(message = "正在下载：$label") }
                    !cancelled.get()
                }
            }
            try { read(card, "下载已完成，未自动启用新配置。请核对列表并发送待处理通知。", expectedEid) }
            catch (_: Exception) { main.post { state = state.copy(profiles = emptyList(), eid = "",
                message = "下载已返回成功，但暂时无法读取卡片。请刷新确认，不要重复使用激活码。") } }
        }
    }

    fun sendNotifications() {
        val card = state.selected ?: return
        val expectedEid = state.eid.takeIf { it.isNotEmpty() } ?: return
        work("正在向配置提供方发送待处理通知…") {
            session(card, expectedEid) { assistant ->
                for (notification in assistant.notifications) {
                    check(!cancelled.get()) { "LPA_CANCELLED" }
                    check(assistant.handleNotification(notification.seqNumber)) { "LPA_NOTIFICATION_FAILED" }
                    check(assistant.deleteNotification(notification.seqNumber)) { "LPA_NOTIFICATION_ACK_REQUIRED" }
                }
            }
            read(card, "通知发送完成。", expectedEid)
        }
    }

    fun cancel() {
        cancelled.set(true); http?.cancel()
        val closingCard = activeCard
        val closingRoot = bridge
        // Preserve the exclusion lease until channels close; never block the UI on an OMAPI Binder call.
        Thread({ closingCard?.close(); closingRoot?.close() }, "lpa-cancel").apply { isDaemon = true; start() }
        state = state.copy(message = "已请求取消。操作可能已到达卡片，请刷新确认结果，不要自动重试。")
    }
    override fun onCleared() { cancel(); executor.shutdownNow() }

    private fun errorText(error: Throwable): String {
        val text = error.message.orEmpty()
        return when {
            cancelled.get() -> "操作已取消；请重新读取卡片确认最终状态。"
            text.contains("LPA_PAUSE_VOWIFI_REQUIRED") -> "请先暂停这张卡的网络通话，等待原厂蜂窝恢复后再读取。"
            text.contains("CALL_ACTIVE") || text.contains("EMERGENCY_CALLBACK") -> "通话、紧急回拨或通话状态未知，暂不访问卡片。"
            text.contains("LPA_BUSY") -> "此卡正被其他操作使用，请稍后重试。"
            text.contains("LPA_MODULE_PERMISSION_REQUIRED") -> "缺少安全元件管理权限。请安装配套模块并重启，再打开 LPA 管理。"
            text.contains("LPA_OMAPI_UNAVAILABLE") || text.contains("LPA_UICC_READER_UNAVAILABLE") ->
                "系统没有提供此卡槽的 OMAPI 读卡接口，当前无法管理这张卡。"
            text.contains("LPA_NOT_EUICC_OR_DENIED") || text.contains("LPA_CARD_PERMISSION_DENIED") ->
                "无法打开 eSIM 管理通道：卡片可能不是支持的 eUICC，或系统／卡片拒绝了访问。"
            text.contains("LPA_CARD_CHANGED") -> "卡片已变化，请重新选择卡槽。"
            text.contains("LPA_CODE_") || text.contains("LPA_SERVER_") -> "激活码格式不支持。请输入 LPA:1\$服务器\$匹配码。"
            text.contains("LPA_NAME_") -> "昵称必须是有效文字，UTF-8 编码不能超过 63 字节。"
            text.contains("LPA_DISABLE_BEFORE_DELETE") -> "请先启用另一配置，再删除当前配置。"
            text.contains("LPA_PROFILE_REJECTED") -> "卡片拒绝了操作，可能受配置策略限制。未绕过卡片限制。"
            text.contains("LPA_RESPONSE_UNKNOWN") -> "操作回应超时，结果未知。请刷新卡内状态后再决定下一步。"
            text.contains("LPA_NOTIFICATION_ACK_REQUIRED") -> "通知已发送，但卡内待处理记录尚未清除。请刷新确认。"
            text.contains("LPA_NOTIFICATION") -> "待处理通知读取或发送失败；卡内记录保留，请稍后检查。"
            error is net.typeblog.lpac_jni.LocalProfileAssistant.ProfileDownloadException ->
                "下载未完成。请检查激活码、确认码、网络和卡片支持情况；不要连续重复消耗激活码。"
            else -> "无法访问可插拔 eSIM。请检查 Root 授权、卡片状态及手机卡槽接口支持情况。"
        }
    }
}

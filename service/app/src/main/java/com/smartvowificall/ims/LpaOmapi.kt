package com.smartvowificall.ims

import android.content.Context
import android.content.pm.PackageManager
import android.se.omapi.Channel
import android.se.omapi.SEService
import android.se.omapi.Session
import com.smartvowificall.ims.lpa.LpaProtocol
import net.typeblog.lpac_jni.ApduInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Authorized OMAPI transport; never impersonates the system LPA or alters card access rules. */
internal class LpaOmapi(private val context: Context, private val card: LpaCard,
    private val root: LpaRootBridge, private val cancelled: AtomicBoolean) : ApduInterface, AutoCloseable {
    var failureCode: String? = null; private set
    private val closed = AtomicBoolean(false)
    private var service: SEService? = null
    private var session: Session? = null
    private var channel: Channel? = null
    override val valid get() = !closed.get() && channel?.isOpen == true

    @Synchronized override fun connect() {
        check(!closed.get() && !cancelled.get()) { "LPA_CANCELLED" }
        root.acquire(card)
        val connected = CountDownLatch(1)
        try {
            val se = SEService(context, { it.run() }) { connected.countDown() }.also { service = it }
            check(connected.await(10, TimeUnit.SECONDS) && se.isConnected) { "LPA_OMAPI_UNAVAILABLE" }
            check(!cancelled.get()) { "LPA_CANCELLED" }
            val reader = try { se.getUiccReader(card.logicalSlot + 1) }
                catch (_: Exception) { error("LPA_UICC_READER_UNAVAILABLE") }
            check(reader.isSecureElementPresent) { "LPA_CARD_UNAVAILABLE" }
            session = reader.openSession()
        } catch (e: Exception) {
            failureCode = e.message?.takeIf { it.matches(Regex("LPA_[A-Z_]{3,70}")) } ?: "LPA_OMAPI_UNAVAILABLE"
            close(); throw e
        }
    }
    override fun logicalChannelOpen(aid: ByteArray): Int {
        require(LpaProtocol.hex(aid) == LpaProtocol.ISDR_AID) { "LPA_AID_NOT_ALLOWED" }
        check(!cancelled.get() && !closed.get()) { "LPA_CANCELLED" }
        root.request("check")
        try {
            channel = session?.openLogicalChannel(aid) ?: error("LPA_NOT_EUICC_OR_DENIED")
        } catch (e: SecurityException) {
            val granted = context.checkSelfPermission("android.permission.SECURE_ELEMENT_PRIVILEGED_OPERATION") == PackageManager.PERMISSION_GRANTED
            failureCode = if (granted) "LPA_CARD_PERMISSION_DENIED" else "LPA_MODULE_PERMISSION_REQUIRED"
            throw IllegalStateException(failureCode)
        } catch (e: Exception) {
            failureCode = "LPA_NOT_EUICC_OR_DENIED"
            throw e
        }
        // OMAPI rewrites CLA to its actual logical channel; the native handle is session-local.
        return 1
    }
    override fun transmit(handle: Int, tx: ByteArray): ByteArray {
        check(handle == 1 && valid && !cancelled.get()) { "LPA_CANCELLED" }
        LpaProtocol.validateApdu(tx)
        root.request("check")
        return channel!!.transmit(tx).also {
            check(it.size in 2..65538) { "LPA_APDU_RESPONSE_INVALID" }
        }
    }
    @Synchronized override fun logicalChannelClose(handle: Int) {
        val current = channel
        channel = null
        current?.close()
    }
    override fun disconnect() = close()
    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { runCatching { channel?.close() }; channel = null }
        finally {
            runCatching { session?.close() }; session = null
            runCatching { service?.shutdown() }; service = null
            runCatching { root.request("close") }
        }
    }
}

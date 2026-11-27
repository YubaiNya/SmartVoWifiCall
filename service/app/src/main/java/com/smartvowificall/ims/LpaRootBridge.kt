package com.smartvowificall.ims

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One private process per management session; no APDU or card identifier enters app diagnostics. */
internal class LpaRootBridge(context: Context) : AutoCloseable {
    var failureCode: String? = null; private set
    private val stopped = AtomicBoolean(false)
    private val process = ProcessBuilder("su", "-c", "CLASSPATH=" +
        RootSupervisor.quote(context.applicationInfo.sourceDir) +
        " exec /system/bin/app_process /system/bin com.smartvowificall.ims.root.RootLpaMain").start()
    private val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
    private val reads = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lpa-root-response").apply { isDaemon = true }
    }
    init {
        Thread({ runCatching {
            process.errorStream.use { input ->
                val buffer = ByteArray(1024)
                while (input.read(buffer) >= 0) { /* Drain without retaining privileged output. */ }
            }
        } }, "lpa-root-stderr").apply { isDaemon = true; start() }
    }

    @Synchronized fun request(action: String, fill: (JSONObject) -> Unit = {}): JSONObject {
        check(!stopped.get()) { "LPA_CANCELLED" }
        val message = JSONObject().put("action", action).also(fill)
        process.outputStream.write((message.toString() + "\n").toByteArray(Charsets.UTF_8))
        process.outputStream.flush()
        val pending = reads.submit<String> {
            val line = StringBuilder()
            while (true) {
                val c = reader.read()
                if (c < 0) throw IllegalStateException("LPA_HELPER_CLOSED")
                if (c == 10) break
                if (line.length >= 262144) throw IllegalStateException("LPA_REPLY_TOO_LARGE")
                line.append(c.toChar())
            }
            line.toString()
        }
        val reply = try { JSONObject(pending.get(35, TimeUnit.SECONDS)) }
        catch (e: Exception) { pending.cancel(true); close(); throw IllegalStateException("LPA_RESPONSE_UNKNOWN") }
        if (!reply.optBoolean("ok")) {
            val code = reply.optString("code").takeIf { it.matches(Regex("[A-Z0-9_]{3,80}")) }
                ?: "LPA_CARD_ACCESS_FAILED"
            if (failureCode == null) failureCode = code
            throw IllegalStateException(code)
        }
        return reply
    }

    override fun close() {
        if (stopped.compareAndSet(false, true)) {
            runCatching { process.outputStream.close() }
            process.destroy()
            reads.shutdownNow()
            runCatching { process.inputStream.close() }
        }
    }

    fun acquire(card: LpaCard) {
        val result = request("acquire") {
            it.put("physicalSlot", card.physicalSlot).put("port", card.port)
        }
        check(result.getInt("logicalSlot") == card.logicalSlot) { "LPA_CARD_CHANGED" }
    }
}

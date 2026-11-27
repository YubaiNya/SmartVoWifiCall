package com.smartvowificall.ims

import com.smartvowificall.ims.lpa.LpaProtocol
import net.typeblog.lpac_jni.HttpInterface
import net.typeblog.lpac_jni.impl.PKID_GSMA_LIVE_CI
import net.typeblog.lpac_jni.impl.keyIdToKeystore
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** ES9+ HTTPS only. System/selected GSMA live trust roots; no test roots or trust bypass. */
internal class LpaHttp(private val cancelled: AtomicBoolean) : HttpInterface {
    @Volatile private var active: HttpsURLConnection? = null
    private var socketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
    fun cancel() { active?.disconnect() }
    override fun usePublicKeyIds(pkids: Array<String>) {
        val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }.trustManagers.filterIsInstance<X509TrustManager>()
        val live = pkids.map { it.lowercase() }.filter { it in PKID_GSMA_LIVE_CI }
        val extra = if (live.isEmpty()) emptyList() else
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(keyIdToKeystore(live.toTypedArray()))
            }.trustManagers.filterIsInstance<X509TrustManager>()
        val managers = system + extra
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = managers.flatMap { it.acceptedIssuers.toList() }.toTypedArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("Client certificates are not accepted")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                for (manager in managers) {
                    try { manager.checkServerTrusted(chain, authType); return }
                    catch (_: CertificateException) {}
                }
                throw CertificateException("LPA_TLS_CERTIFICATE_REJECTED")
            }
        }
        socketFactory = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trust), null)
        }.socketFactory
    }

    override fun transmit(url: String, tx: ByteArray, headers: Array<String>): HttpInterface.HttpResponse {
        check(!cancelled.get()) { "LPA_CANCELLED" }
        val endpoint = URL(url)
        require(endpoint.protocol == "https" && endpoint.userInfo == null && endpoint.ref == null &&
            (endpoint.port == -1 || endpoint.port == 443)) { "LPA_HTTPS_REQUIRED" }
        LpaProtocol.server(endpoint.host)
        val connection = (endpoint.openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15000; readTimeout = 30000
            sslSocketFactory = socketFactory
            requestMethod = "POST"; doOutput = true
        }
        active = connection
        try {
            for (header in headers) {
                val pair = header.split(':', limit = 2)
                require(pair.size == 2 && !header.contains('\r') && !header.contains('\n')) { "LPA_HEADER_INVALID" }
                connection.setRequestProperty(pair[0].trim(), pair[1].trim())
            }
            connection.setFixedLengthStreamingMode(tx.size)
            connection.outputStream.use { it.write(tx) }
            check(!cancelled.get()) { "LPA_CANCELLED" }
            val status = connection.responseCode
            require(status !in 300..399) { "LPA_REDIRECT_REJECTED" }
            val source = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = source?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    check(!cancelled.get()) { "LPA_CANCELLED" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 16 * 1024 * 1024) { "LPA_RESPONSE_TOO_LARGE" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            return HttpInterface.HttpResponse(status, bytes)
        } finally { active = null; connection.disconnect() }
    }
}

package com.studyontv.tv

import android.content.Context
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 为 Android 5/6 老电视提供「系统 CA + assets/certs/cloud_roots.pem」的 TLS 信任管理，
 * 避免老系统缺失 ISRG Root X1 导致 HTTPS / WSS 握手失败，同时保持严格主机名校验。
 */
object CloudCerts {
    val hostnameVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

    @Volatile private var cachedTrustManager: X509TrustManager? = null
    @Volatile private var cachedSocketFactory: SSLSocketFactory? = null

    fun sslSocketFactory(context: Context): SSLSocketFactory {
        cachedSocketFactory?.let { return it }
        synchronized(this) {
            cachedSocketFactory?.let { return it }
            val tm = trustManager(context)
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), null)
            return ctx.socketFactory.also { cachedSocketFactory = it }
        }
    }

    /** 兼容已有调用（若尚未初始化则返回系统默认工厂，传入 Context 后升级）。 */
    val sslSocketFactory: SSLSocketFactory
        get() = cachedSocketFactory ?: (HttpsURLConnection.getDefaultSSLSocketFactory())

    fun init(context: Context) {
        sslSocketFactory(context.applicationContext)
    }

    private fun trustManager(context: Context): X509TrustManager {
        cachedTrustManager?.let { return it }
        val sys = defaultSystemTrustManager()
        val extra = loadBundledTrustManager(context)
        val combined = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                sys?.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    if (sys != null) {
                        sys.checkServerTrusted(chain, authType)
                        return
                    }
                } catch (sysErr: Exception) {
                    if (extra != null) {
                        extra.checkServerTrusted(chain, authType)
                        return
                    }
                    throw sysErr
                }
                extra?.checkServerTrusted(chain, authType)
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                (sys?.acceptedIssuers ?: emptyArray()) + (extra?.acceptedIssuers ?: emptyArray())
        }
        cachedTrustManager = combined
        return combined
    }

    private fun defaultSystemTrustManager(): X509TrustManager? = try {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    } catch (_: Throwable) {
        null
    }

    private fun loadBundledTrustManager(context: Context): X509TrustManager? = try {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val cf = CertificateFactory.getInstance("X.509")
        context.assets.open("certs/cloud_roots.pem").use { stream ->
            val certs = cf.generateCertificates(stream)
            certs.forEachIndexed { idx, cert ->
                ks.setCertificateEntry("cloud_root_$idx", cert)
            }
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    } catch (_: Throwable) {
        null
    }
}

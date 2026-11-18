package com.studyontv.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/**
 * HTTPS 长轮询传输实现（打底通道，50s 服务端挂起 + 55s 客户端读超时）。
 */
class LongPollTransport(
    private val context: Context,
    private val origin: String,
    private val deviceId: String,
    private val deviceSecret: String,
    private val listener: TunnelListener,
) : Transport {
    override val name: String = "poll"
    private val running = AtomicBoolean(false)
    private val sendPool = Executors.newSingleThreadExecutor()
    @Volatile private var activeConn: HttpURLConnection? = null
    private var worker: Thread? = null

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({
            listener.onConnected(name)
            while (running.get()) {
                try {
                    val conn = openConn("$origin/ziyin/tv/poll", "GET", 58_000)
                    activeConn = conn
                    val code = conn.responseCode
                    val text = try {
                        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                        stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    } finally {
                        conn.disconnect()
                        activeConn = null
                    }
                    if (!running.get()) break
                    if (code == 401 || code == 403) {
                        listener.onDisconnected(name, "unauthorized:$code")
                        break
                    }
                    if (code !in 200..299) {
                        listener.onDisconnected(name, "http_$code")
                        break
                    }
                    val root = JSONObject(text)
                    val msgs = root.optJSONObject("data")?.optJSONArray("messages") ?: JSONArray()
                    for (i in 0 until msgs.length()) {
                        val env = msgs.optJSONObject(i) ?: continue
                        listener.onMessage(env, name)
                    }
                } catch (e: Throwable) {
                    if (!running.get()) break
                    listener.onDisconnected(name, e.javaClass.simpleName + ":" + (e.message ?: ""))
                    break
                }
            }
        }, "sotv-poll").apply { isDaemon = true; start() }
    }

    override fun send(envelope: JSONObject): Boolean {
        if (!running.get()) return false
        sendPool.execute {
            try {
                val conn = openConn("$origin/ziyin/tv/reply", "POST", 10_000)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(envelope.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = try {
                    (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                } finally {
                    conn.disconnect()
                }
                if (code in 200..299 && text.isNotEmpty()) {
                    val replies = JSONObject(text).optJSONObject("data")?.optJSONArray("replies")
                    if (replies != null) {
                        for (i in 0 until replies.length()) {
                            replies.optJSONObject(i)?.let { listener.onMessage(it, name) }
                        }
                    }
                }
            } catch (e: Throwable) {
                Diag.warn("LongPoll", "send error: ${e.message}")
            }
        }
        return true
    }

    override fun stop() {
        running.set(false)
        try { activeConn?.disconnect() } catch (_: Throwable) { }
        try { worker?.interrupt() } catch (_: Throwable) { }
    }

    private fun openConn(urlStr: String, method: String, readTimeoutMs: Int): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = CloudCerts.sslSocketFactory(context)
        }
        conn.requestMethod = method
        conn.connectTimeout = 8000
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("Authorization", "Bearer $deviceSecret")
        conn.setRequestProperty("X-Sotv-Device", deviceId)
        return conn
    }
}

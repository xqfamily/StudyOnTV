package com.studyontv.tv

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket

/**
 * 纯 JDK 手写 RFC 6455 WSS 客户端（零第三方重依赖）：
 * - 强制使用 wss:// 与内置根证书 TrustManager；
 * - 启用 SNI 并显式调用 HostnameVerifier 校验主机名；
 * - 支持掩码、分片重组、ping/pong 与 60s 半开检测。
 */
class WsTransport(
    private val context: Context,
    private val httpsOrigin: String,
    private val wsPath: String,
    private val deviceId: String,
    private val deviceSecret: String,
    private val listener: TunnelListener,
) : Transport {
    override val name: String = "ws"
    private val running = AtomicBoolean(false)
    private val sendPool = Executors.newSingleThreadExecutor()
    private val writeLock = Any()
    private val random = SecureRandom()
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                val u = URL(httpsOrigin)
                if (!u.protocol.equals("https", ignoreCase = true)) {
                    listener.onDisconnected(name, "wss_required")
                    return@Thread
                }
                val host = u.host
                val port = if (u.port > 0) u.port else 443
                val raw = Socket()
                raw.connect(InetSocketAddress(host, port), 8000)
                val sslSock = CloudCerts.sslSocketFactory(context).createSocket(raw, host, port, true) as SSLSocket
                // Android 7+ 可用 SSLParameters 强化主机名校验；Android 5/6 由下面 hostnameVerifier 兜底
                try {
                    val params = sslSock.sslParameters
                    if (android.os.Build.VERSION.SDK_INT >= 24) {
                        params.endpointIdentificationAlgorithm = "HTTPS"
                        sslSock.sslParameters = params
                    }
                } catch (_: Throwable) { }
                sslSock.soTimeout = 60_000
                sslSock.startHandshake()
                if (!CloudCerts.hostnameVerifier.verify(host, sslSock.session)) {
                    sslSock.close()
                    throw javax.net.ssl.SSLPeerUnverifiedException("Hostname verification failed for $host")
                }

                socket = sslSock
                val inp = BufferedInputStream(sslSock.inputStream)
                val outStream = sslSock.outputStream
                out = outStream

                val keyBytes = ByteArray(16).also { random.nextBytes(it) }
                val wsKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
                val hostHeader = if (u.port > 0) "$host:${u.port}" else host
                val req = buildString {
                    append("GET $wsPath HTTP/1.1\r\n")
                    append("Host: $hostHeader\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $wsKey\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("Authorization: Bearer $deviceSecret\r\n")
                    append("X-Sotv-Device: $deviceId\r\n\r\n")
                }
                outStream.write(req.toByteArray(Charsets.US_ASCII))
                outStream.flush()

                val statusLine = readHttpLine(inp) ?: throw EOFException("ws handshake eof")
                if (!statusLine.contains("101")) throw IllegalStateException("ws handshake failed: $statusLine")
                var acceptGot = ""
                while (true) {
                    val line = readHttpLine(inp) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0 && line.substring(0, idx).trim().equals("Sec-WebSocket-Accept", ignoreCase = true)) {
                        acceptGot = line.substring(idx + 1).trim()
                    }
                }
                val expectedAccept = Base64.encodeToString(
                    MessageDigest.getInstance("SHA-1")
                        .digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)),
                    Base64.NO_WRAP
                )
                if (acceptGot != expectedAccept) throw IllegalStateException("invalid Sec-WebSocket-Accept")

                listener.onConnected(name)
                readLoop(inp)
            } catch (e: Throwable) {
                if (running.get()) {
                    listener.onDisconnected(name, e.javaClass.simpleName + ":" + (e.message ?: ""))
                }
            } finally {
                running.set(false)
                try { socket?.close() } catch (_: Throwable) { }
            }
        }, "sotv-ws").apply { isDaemon = true; start() }
    }

    private fun readLoop(inp: BufferedInputStream) {
        var fragBuf: ByteArrayOutputStream? = null
        while (running.get()) {
            val b0 = inp.read()
            if (b0 < 0) throw EOFException("ws closed")
            val b1 = inp.read()
            if (b1 < 0) throw EOFException("ws closed")
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) {
                val hi = inp.read(); val lo = inp.read()
                if (hi < 0 || lo < 0) throw EOFException()
                len = ((hi shl 8) or lo).toLong()
            } else if (len == 127L) {
                var v = 0L
                for (i in 0..7) {
                    val x = inp.read()
                    if (x < 0) throw EOFException()
                    v = (v shl 8) or x.toLong()
                }
                len = v
            }
            if (len < 0 || len > 8_000_000L) throw IllegalStateException("ws frame too large: $len")
            val mask = if (masked) ByteArray(4).also { readFully(inp, it) } else null
            val payload = ByteArray(len.toInt())
            readFully(inp, payload)
            if (mask != null) {
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }

            when (opcode) {
                0x8 -> { // Close
                    writeFrame(0x8, ByteArray(0))
                    listener.onDisconnected(name, "server_close")
                    return
                }
                0x9 -> writeFrame(0xA, payload) // Ping -> Pong
                0xA -> { /* Pong */ }
                0x1 -> { // Text
                    if (fin) {
                        val txt = String(payload, Charsets.UTF_8)
                        try { listener.onMessage(JSONObject(txt), name) } catch (_: Throwable) { }
                    } else {
                        fragBuf = ByteArrayOutputStream().apply { write(payload) }
                    }
                }
                0x0 -> { // Continuation
                    fragBuf?.write(payload)
                    if (fin && fragBuf != null) {
                        val txt = String(fragBuf!!.toByteArray(), Charsets.UTF_8)
                        fragBuf = null
                        try { listener.onMessage(JSONObject(txt), name) } catch (_: Throwable) { }
                    }
                }
            }
        }
    }

    override fun send(envelope: JSONObject): Boolean {
        if (!running.get() || out == null) return false
        sendPool.execute {
            try {
                writeFrame(0x1, envelope.toString().toByteArray(Charsets.UTF_8))
            } catch (e: Throwable) {
                if (running.get()) {
                    running.set(false)
                    listener.onDisconnected(name, "write_error:${e.message}")
                }
            }
        }
        return true
    }

    private fun writeFrame(opcode: Int, payload: ByteArray) {
        val os = out ?: return
        val mask = ByteArray(4).also { random.nextBytes(it) }
        val hdr = ByteArrayOutputStream()
        hdr.write(0x80 or (opcode and 0x0F))
        val len = payload.size
        when {
            len <= 125 -> hdr.write(0x80 or len)
            len <= 65535 -> {
                hdr.write(0x80 or 126)
                hdr.write((len shr 8) and 0xFF)
                hdr.write(len and 0xFF)
            }
            else -> {
                hdr.write(0x80 or 127)
                val l = len.toLong()
                for (i in 7 downTo 0) hdr.write(((l shr (i * 8)) and 0xFF).toInt())
            }
        }
        hdr.write(mask)
        val maskedPayload = ByteArray(len)
        for (i in 0 until len) maskedPayload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()

        synchronized(writeLock) {
            os.write(hdr.toByteArray())
            os.write(maskedPayload)
            os.flush()
        }
    }

    override fun stop() {
        running.set(false)
        try { writeFrame(0x8, ByteArray(0)) } catch (_: Throwable) { }
        try { socket?.close() } catch (_: Throwable) { }
    }

    private fun readHttpLine(inp: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (buf.size() == 0) null else String(buf.toByteArray(), Charsets.US_ASCII)
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return String(buf.toByteArray(), Charsets.US_ASCII)
    }

    private fun readFully(inp: BufferedInputStream, target: ByteArray) {
        var off = 0
        while (off < target.size) {
            val n = inp.read(target, off, target.size - off)
            if (n < 0) throw EOFException("ws unexpected eof")
            off += n
        }
    }
}

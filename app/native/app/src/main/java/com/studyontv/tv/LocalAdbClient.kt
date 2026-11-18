package com.studyontv.tv

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * 极简本机 ADB 协议子集客户端（纯 Kotlin / JDK，零第三方依赖）：
 * 仅用于引导页家长点击「一键授权」时连接 127.0.0.1:5555，
 * 触发系统自带「允许 USB 调试吗？」弹窗并执行固定权限脚本，绝不向任何网络接口暴露。
 */
object LocalAdbClient {
    private const val A_CNXN = 0x4E584E43
    private const val A_AUTH = 0x48545541
    private const val A_OPEN = 0x4E45504F
    private const val A_OKAY = 0x59414B4F
    private const val A_CLSE = 0x45534C43
    private const val A_WRTE = 0x45545257
    private const val AUTH_TOKEN = 1
    private const val AUTH_SIGNATURE = 2
    private const val AUTH_RSAPUBLICKEY = 3
    private const val ADB_VERSION = 0x01000000
    private const val MAXDATA = 4096

    data class Result(val ok: Boolean, val output: String, val error: String = "")

    fun buildGrantCommands(pkg: String): List<String> {
        val list = mutableListOf(
            "appops set $pkg SYSTEM_ALERT_WINDOW allow",
            "appops set $pkg GET_USAGE_STATS allow",
            "if [ \"\$(getprop ro.build.version.sdk)\" -ge 26 ]; then appops set $pkg REQUEST_INSTALL_PACKAGES allow; fi",
            "if [ \"\$(getprop ro.build.version.sdk)\" -ge 33 ]; then pm grant $pkg android.permission.POST_NOTIFICATIONS; fi",
            "CUR_A11Y=\$(settings get secure enabled_accessibility_services); " +
                "SVC=\"$pkg/com.studyontv.tv.WatchAccessibilityService\"; " +
                "case \":\$CUR_A11Y:\" in *\":\$SVC:\"*) ;; *) " +
                "CLEAN=\$(echo \"\$CUR_A11Y\" | sed 's/null//g;s/^:*//;s/:*$//'); " +
                "if [ -z \"\$CLEAN\" ]; then settings put secure enabled_accessibility_services \"\$SVC\"; " +
                "else settings put secure enabled_accessibility_services \"\$CLEAN:\$SVC\"; fi ;; esac; " +
                "settings put secure accessibility_enabled 1",
            "dumpsys deviceidle whitelist +$pkg",
            "if [ \"\$(getprop ro.build.version.sdk)\" -ge 27 ]; then cmd notification allow_listener $pkg/com.studyontv.tv.RevivalListener; fi",
        )
        return list
    }

    fun executeGrantCommands(context: Context): Result {
        val kp = ensureKeyPair(context)
        val cmds = buildGrantCommands(context.packageName)
        val doneMarker = "__SOTV_GRANTS_DONE__"
        val script = "set -e; ${cmds.joinToString("; ")}; echo $doneMarker"
        var sock: Socket? = null
        return try {
            sock = Socket()
            sock.connect(InetSocketAddress("127.0.0.1", 5555), 3500)
            sock.soTimeout = 35_000
            val inp = sock.getInputStream()
            val out = sock.getOutputStream()

            sendPacket(out, A_CNXN, ADB_VERSION, MAXDATA, "host::\u0000".toByteArray(Charsets.US_ASCII))
            var sentPubKey = false
            var connected = false
            while (!connected) {
                val pkt = readPacket(inp)
                when (pkt.command) {
                    A_AUTH -> {
                        if (pkt.arg0 == AUTH_TOKEN) {
                            if (!sentPubKey) {
                                // 先用私钥签名尝试，失败再发公钥触发电视弹窗
                                val sig = signToken(kp, pkt.payload)
                                sendPacket(out, A_AUTH, AUTH_SIGNATURE, 0, sig)
                                sentPubKey = true
                            } else {
                                val pub = encodeAdbPublicKey(kp.public as RSAPublicKey, "xueyihui-tv")
                                sendPacket(out, A_AUTH, AUTH_RSAPUBLICKEY, 0, pub)
                            }
                        }
                    }
                    A_CNXN -> connected = true
                    else -> return Result(false, "", "ADB 握手意外响应: 0x${pkt.command.toString(16)}")
                }
            }

            val localId = 1
            sendPacket(out, A_OPEN, localId, 0, "shell:$script\u0000".toByteArray(Charsets.UTF_8))
            val outBuf = ByteArrayOutputStream()
            var remoteId = 0
            while (true) {
                val pkt = readPacket(inp)
                when (pkt.command) {
                    A_OKAY -> if (remoteId == 0) remoteId = pkt.arg0
                    A_WRTE -> {
                        outBuf.write(pkt.payload)
                        if (remoteId != 0) sendPacket(out, A_OKAY, localId, remoteId, ByteArray(0))
                    }
                    A_CLSE -> {
                        if (remoteId != 0) sendPacket(out, A_CLSE, localId, remoteId, ByteArray(0))
                        break
                    }
                }
            }
            val output = String(outBuf.toByteArray(), Charsets.UTF_8)
            if (output.contains(doneMarker)) Result(true, output)
            else Result(false, output, "授权命令未全部执行成功，请检查电视权限状态后重试")
        } catch (e: Throwable) {
            Result(false, "", e.message ?: "连接本机 5555 失败，请确认已开启「ADB 调试 / USB 调试」")
        } finally {
            try { sock?.close() } catch (_: Throwable) { }
        }
    }

    private data class AdbPacket(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    private fun sendPacket(out: OutputStream, command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        var checksum = 0
        for (b in payload) checksum += (b.toInt() and 0xFF)
        val hdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(payload.size)
            .putInt(checksum)
            .putInt(command xor -1)
            .array()
        out.write(hdr)
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    private fun readPacket(inp: InputStream): AdbPacket {
        val hdrBytes = ByteArray(24)
        readFully(inp, hdrBytes)
        val bb = ByteBuffer.wrap(hdrBytes).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val len = bb.int
        bb.int // checksum
        bb.int // magic
        val payload = if (len > 0) ByteArray(len).also { readFully(inp, it) } else ByteArray(0)
        return AdbPacket(cmd, arg0, arg1, payload)
    }

    private fun readFully(inp: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException("ADB 连接被断开（请在电视弹窗点允许后重试）")
            off += n
        }
    }

    @Synchronized
    private fun ensureKeyPair(context: Context): KeyPair {
        val dir = File(context.filesDir, "adb_key").apply { mkdirs() }
        val privF = File(dir, "adbkey.pk8")
        val pubF = File(dir, "adbkey.x509")
        if (privF.exists() && pubF.exists()) {
            val kf = KeyFactory.getInstance("RSA")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privF.readBytes()))
            val pub = kf.generatePublic(X509EncodedKeySpec(pubF.readBytes()))
            return KeyPair(pub, priv)
        }
        val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val kp = gen.generateKeyPair()
        privF.writeBytes(kp.private.encoded)
        pubF.writeBytes(kp.public.encoded)
        return kp
    }

    private fun signToken(kp: KeyPair, token: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA1withRSA")
        sig.initSign(kp.private)
        sig.update(token)
        return sig.sign()
    }

    /** 将 RSA 2048 公钥编码为 ADB RSAPublicKey 结构（524 字节小端 + Base64 + 标签 + \0）。 */
    private fun encodeAdbPublicKey(pub: RSAPublicKey, comment: String): ByteArray {
        val n = pub.modulus
        val nWords = 64 // 2048 bits / 32
        val r = BigInteger.ONE.shiftLeft(2048)
        val r2 = r.multiply(r).mod(n)
        val n0inv = BigInteger.ONE.shiftLeft(32).subtract(n.modInverse(BigInteger.ONE.shiftLeft(32))).toLong()

        val bb = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(nWords)
        bb.putInt(n0inv.toInt())
        writeBigIntWords(bb, n, nWords)
        writeBigIntWords(bb, r2, nWords)
        bb.putInt(pub.publicExponent.toInt())

        val b64 = Base64.encodeToString(bb.array(), Base64.NO_WRAP)
        return "$b64 $comment\u0000".toByteArray(Charsets.US_ASCII)
    }

    private fun writeBigIntWords(bb: ByteBuffer, value: BigInteger, words: Int) {
        val mask = BigInteger.valueOf(0xFFFFFFFFL)
        var v = value
        for (i in 0 until words) {
            bb.putInt(v.and(mask).toInt())
            v = v.shiftRight(32)
        }
    }
}

package com.studyontv.tv

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import org.json.JSONObject

/**
 * 主进程与 `:gate`（WebView）隔离进程间的轻量同步通信桥。
 * 这样主进程不加载 libwebviewchromium.so，仅保持监控服务与 HTTP 服务；
 * `:gate` 进程不直接触摸 Prefs / SQLite / MediaPause，避免跨进程缓存一致性问题。
 */
object CoreBridge {
    const val ACTION_SPEECH_NOTICE = "com.studyontv.tv.action.SPEECH_NOTICE"
    const val EXTRA_PAYLOAD = "payload"

    private fun authority(context: Context) = Uri.parse("content://${context.packageName}.core")

    @Volatile private var cachedCoreProcess: Boolean? = null

    fun processName(context: Context): String {
        if (Build.VERSION.SDK_INT >= 28) {
            return android.app.Application.getProcessName() ?: context.packageName
        }
        try {
            val bytes = java.io.File("/proc/self/cmdline").readBytes()
            val end = bytes.indexOf(0.toByte()).let { if (it < 0) bytes.size else it }
            val name = String(bytes, 0, end, Charsets.UTF_8).trim()
            if (name.isNotEmpty()) return name
        } catch (_: Throwable) { }
        val pid = Process.myPid()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val found = am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        return found ?: context.packageName
    }

    fun isCoreProcess(context: Context): Boolean {
        cachedCoreProcess?.let { return it }
        val core = processName(context) == context.packageName
        cachedCoreProcess = core
        return core
    }

    private fun call(context: Context, method: String, arg: String? = null, extras: Bundle? = null): Bundle {
        return try {
            context.contentResolver.call(authority(context), method, arg, extras) ?: Bundle()
        } catch (e: Throwable) {
            Diag.warn("CoreBridge", "call $method failed: ${e.message}")
            Bundle()
        }
    }

    fun bootInfo(context: Context): Bundle = call(context, "boot")

    fun reportActivity(context: Context, resumed: Boolean, className: String) {
        call(context, "activity", className, Bundle().apply { putBoolean("resumed", resumed) })
    }

    fun mediaPause(context: Context, action: String) {
        call(context, "mediaPause", action)
    }

    fun speech(context: Context, action: String, text: String = "", lang: String = "", url: String = ""): String {
        val b = call(context, "speech", action, Bundle().apply {
            if (text.isNotEmpty()) putString("text", text)
            if (lang.isNotEmpty()) putString("lang", lang)
            if (url.isNotEmpty()) putString("url", url)
        })
        return b.getString("result") ?: "{}"
    }

    fun apiToken(context: Context): String = call(context, "token").getString("token") ?: ""

    fun learningStatus(context: Context): JSONObject {
        val raw = call(context, "status").getString("json") ?: "{}"
        return try { JSONObject(raw) } catch (_: Throwable) { JSONObject() }
    }

    fun updateInfo(context: Context): String = call(context, "updateInfo").getString("json") ?: ""

    fun checkUpdate(context: Context, onReady: (String) -> Unit) {
        Thread {
            val raw = call(context, "checkUpdate").getString("json") ?: ""
            if (raw.isNotEmpty()) onReady(raw)
        }.apply { isDaemon = true }.start()
    }

    fun crashAck(context: Context, consent: Boolean) {
        call(context, "crashAck", null, Bundle().apply { putBoolean("consent", consent) })
    }

    fun finishGate(context: Context, mode: String): Bundle = call(context, "finishGate", mode)
}

package com.studyontv.tv

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 运行在默认主进程的 ContentProvider：
 * 为 `:gate`（WebView）隔离进程提供状态读取、TTS/音频播报、媒体静音和收起解锁兜底，
 * 确保主进程永不加载 Chromium WebView，并在 `:gate` 销毁时保持一切状态完全一致。
 */
class CoreBridgeProvider : ContentProvider() {
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun onCreate(): Boolean = true
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context?.applicationContext ?: return Bundle()
        DeviceRuntime.ensure(ctx)
        val out = Bundle()
        when (method) {
            "boot" -> {
                val configured = AdminAuth.configured(ctx)
                val onboarded = Prefs.onboarded(ctx)
                if (configured && onboarded) {
                    mainHandler.post { GateService.ensureRunning(ctx, "gate_proc") }
                }
                val crashPending = CrashDetector.hasPending(ctx)
                val consent = Prefs.buglyConsent(ctx)
                var crashSummary = ""
                if (crashPending && consent) {
                    CrashDetector.clear(ctx)
                    BuglyInit.init(ctx)
                } else if (crashPending) {
                    crashSummary = CrashDetector.readAndClear(ctx)
                }
                out.putBoolean("configured", configured)
                out.putBoolean("onboarded", onboarded)
                out.putString("localBase", Prefs.localBase(ctx))
                out.putString("token", AdminAuth.deviceToken)
                out.putBoolean("free", try { DeviceRuntime.learning.status().optBoolean("free") } catch (_: Throwable) { false })
                out.putBoolean("crashPending", crashPending && !consent)
                out.putBoolean("buglyConsent", consent)
                out.putString("crashSummary", crashSummary)
            }
            "activity" -> {
                val resumed = extras?.getBoolean("resumed", false) ?: false
                Foreground.setExternalOwnActivity(ctx.packageName, resumed, arg)
                if (resumed) GateService.onOwnActivityResumed()
            }
            "mediaPause" -> {
                val appCtx = DeviceRuntime.appContext
                mainHandler.post {
                    when (arg) {
                        "recover" -> MediaPause.recover(appCtx)
                        "pause" -> MediaPause.pause(appCtx)
                        "release" -> MediaPause.release(appCtx)
                    }
                }
            }
            "speech" -> {
                val notice: (JSONObject) -> Unit = { payload ->
                    try {
                        ctx.sendBroadcast(
                            Intent(CoreBridge.ACTION_SPEECH_NOTICE)
                                .setPackage(ctx.packageName)
                                .putExtra(CoreBridge.EXTRA_PAYLOAD, payload.toString())
                        )
                    } catch (_: Throwable) { }
                }
                when (arg) {
                    "read" -> SpeechReader.read(ctx, extras?.getString("text").orEmpty(), "", notice)
                    "readLang" -> SpeechReader.read(ctx, extras?.getString("text").orEmpty(), extras?.getString("lang").orEmpty(), notice)
                    "play" -> SpeechReader.playAudio(ctx, extras?.getString("url").orEmpty(), notice)
                    "stop" -> SpeechReader.stop()
                    "status" -> out.putString("result", SpeechReader.status().toString())
                }
            }
            "token" -> out.putString("token", AdminAuth.deviceToken)
            "status" -> out.putString("json", try { DeviceRuntime.learning.status().toString() } catch (_: Throwable) { "{}" })
            "updateInfo" -> {
                val info = Updater.newerThanCurrent(ctx, Updater.cachedInfo(ctx))
                if (info != null) {
                    out.putString("json", Updater.toJson(info).put("current", Updater.currentVersion(ctx)).toString())
                }
            }
            "checkUpdate" -> {
                val latch = CountDownLatch(1)
                var resultJson = ""
                Updater.check(ctx) { info ->
                    val newer = Updater.newerThanCurrent(ctx, info)
                    if (newer != null) {
                        resultJson = Updater.toJson(newer).put("current", Updater.currentVersion(ctx)).toString()
                    }
                    latch.countDown()
                }
                try { latch.await(8, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
                out.putString("json", resultJson)
            }
            "crashAck" -> {
                if (extras?.getBoolean("consent", false) == true) {
                    BuglyInit.initWithConsent(ctx)
                } else {
                    CrashDetector.clear(ctx)
                }
            }
            "finishGate" -> {
                if (arg == "exitToLauncher") {
                    val status = DeviceRuntime.learning.status()
                    val blocked = status.getString("reason").isNotEmpty() ||
                        (status.optLong("remainingSeconds") <= 0 && !status.optBoolean("free"))
                    out.putBoolean("allowed", !blocked)
                    if (!blocked) {
                        mainHandler.post {
                            MediaPause.release(DeviceRuntime.appContext)
                            GateService.hideGates()
                        }
                    }
                } else {
                    val target = GateService.rememberedActive()
                    out.putBoolean("allowed", true)
                    out.putString("target", target)
                    mainHandler.post {
                        MediaPause.release(DeviceRuntime.appContext)
                        GateService.hideGates()
                    }
                    if (target != null && target != ctx.packageName && !Foreground.isLauncher(target)) {
                        mainHandler.postDelayed({
                            val now = try { Foreground.current(ctx) } catch (_: Exception) { null }
                            if (now == null || now == ctx.packageName || Foreground.isLauncher(now)) {
                                DeviceBridge.launchPackage(ctx, target)
                            }
                        }, 700)
                    }
                }
            }
        }
        return out
    }
}

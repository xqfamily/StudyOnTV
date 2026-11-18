package com.studyontv.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicInteger

open class MainActivity : Activity() {
    protected lateinit var web: WebView
    private var webReleased = false
    private var page = "quiz"
    private var baseUrl = "http://127.0.0.1:8099/"
    private val isCore by lazy { CoreBridge.isCoreProcess(this) }
    private val uiHandler = Handler(Looper.getMainLooper())
    private var speechReceiverRegistered = false
    private val speechReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val act = intent?.action ?: return
            val payload = intent.getStringExtra(CoreBridge.EXTRA_PAYLOAD) ?: "{}"
            if (::web.isInitialized && !webReleased) {
                web.post {
                    try {
                        when (act) {
                            CoreBridge.ACTION_SPEECH_NOTICE -> web.evaluateJavascript("window.SOTV_SPEECH_NOTICE&&SOTV_SPEECH_NOTICE($payload)", null)
                            CloudTunnelClient.ACTION_LESSON_CTRL -> web.evaluateJavascript("window.SOTV_CLOUD_EVENT&&SOTV_CLOUD_EVENT('lesson_ctrl',$payload)", null)
                            CloudTunnelClient.ACTION_TOAST -> web.evaluateJavascript("window.SOTV_CLOUD_EVENT&&SOTV_CLOUD_EVENT('toast',$payload)", null)
                            CloudTunnelClient.ACTION_LESSON_UPDATED -> web.evaluateJavascript("window.SOTV_CLOUD_EVENT&&SOTV_CLOUD_EVENT('lesson_updated',$payload)", null)
                        }
                    } catch (_: Throwable) { }
                }
            }
        }
    }
    private val remote = RemoteKeys {
        startActivity(Intent(this, ParentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isCore) {
            MediaPause.recover(this)
            if (!AdminAuth.configured(this)) {
                startActivity(Intent(this, ParentActivity::class.java)); finish(); return
            }
            if (!Prefs.onboarded(this)) {
                startActivity(Intent(this, OnboardingActivity::class.java)); finish(); return
            }
            DeviceRuntime.ensure(this)
            baseUrl = Prefs.localBase(this)
            checkCrashReport(null)
            GateService.start(this)
        } else {
            CoreBridge.mediaPause(this, "recover")
            val boot = CoreBridge.bootInfo(this)
            if (!boot.getBoolean("configured", false)) {
                startActivity(Intent(this, ParentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
                return
            }
            if (!boot.getBoolean("onboarded", false)) {
                startActivity(Intent(this, OnboardingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
                return
            }
            baseUrl = boot.getString("localBase") ?: baseUrl
            checkCrashReport(boot)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (DeviceBridge.isTv(this)) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val reason = intent.getStringExtra("reason")
        val targetPage = intent.getStringExtra("page")
        page = when {
            !targetPage.isNullOrBlank() -> targetPage
            this is GateActivity && reason != null && reason !in listOf("timeup", "feedback") -> "lock?reason=$reason"
            else -> "quiz"
        }
        createWeb()
    }

    private fun checkCrashReport(boot: Bundle?) {
        if (boot == null) {
            if (!CrashDetector.hasPending(this)) return
            if (Prefs.buglyConsent(this)) {
                CrashDetector.clear(this)
                BuglyInit.init(this)
                return
            }
            CrashDetector.readAndClear(this)
        } else {
            if (!boot.getBoolean("crashPending", false)) return
        }
        AlertDialog.Builder(this)
            .setTitle("🚨 上次运行时发生了崩溃")
            .setMessage(
                "是否允许上传崩溃日志帮助修复问题？\n\n" +
                "包含：崩溃调用堆栈、设备型号、Android 版本、应用版本号\n" +
                "不含：题库内容、管理密码、答题记录、局域网 IP\n\n" +
                "日志将上传至腾讯 Bugly 服务器，可随时在「设置 → 崩溃报告」关闭。"
            )
            .setPositiveButton("提交日志") { _, _ ->
                if (isCore) BuglyInit.initWithConsent(this) else CoreBridge.crashAck(this, true)
                android.widget.Toast.makeText(this, "已提交，感谢反馈！", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("不了") { _, _ ->
                if (!isCore) CoreBridge.crashAck(this, false)
            }
            .setCancelable(false)
            .show()
    }

    private fun checkUpdate() {
        if (this is GateActivity) return
        if (!isCore) {
            CoreBridge.checkUpdate(this) { raw ->
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    val o = try { org.json.JSONObject(raw) } catch (_: Throwable) { return@runOnUiThread }
                    if (o.optBoolean("force")) {
                        startActivity(Intent(this, UpdateActivity::class.java).putExtra("payload", raw).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else if (::web.isInitialized && !webReleased) {
                        web.evaluateJavascript("window.SOTV_UPDATE&&SOTV_UPDATE($raw)", null)
                    }
                }
            }
            return
        }
        Updater.check(this) { info ->
            val newer = Updater.newerThanCurrent(this, info) ?: return@check
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val raw = DeviceBridge(this).updateInfo()
                if (newer.force) startActivity(Intent(this, UpdateActivity::class.java).putExtra("payload", raw))
                else if (::web.isInitialized && !webReleased) web.evaluateJavascript("window.SOTV_UPDATE&&SOTV_UPDATE($raw)", null)
            }
        }
    }

    private fun createWeb() {
        web = WebShell(this).create(baseUrl + "#/$page")
        web.addJavascriptInterface(DeviceBridge(this, false, web), "SOTVNative")
        setContentView(web)
        web.requestFocus()
        webReleased = false
    }

    override fun onStart() {
        super.onStart()
        cancelScheduledKill()
        startedCount.incrementAndGet()
    }

    override fun onStop() {
        super.onStop()
        val remaining = startedCount.decrementAndGet()
        if (isCore) {
            MediaPause.release(this)
            SpeechReader.stop()
        } else {
            CoreBridge.mediaPause(this, "release")
            CoreBridge.speech(this, "stop")
        }
        // 正在等原生输入页（填空题）回传答案时不能销毁 WebView
        val awaitingInput = InputSupport.active || AnswerInput.active
        if (DeviceBridge.isTv(this) && ::web.isInitialized && !webReleased && !awaitingInput) {
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.destroy()
            webReleased = true
        }
        if (!isCore && DeviceBridge.isTv(this) && !awaitingInput && remaining <= 0) {
            scheduleGateProcessKill()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val reason = intent?.getStringExtra("reason")
        val targetPage = intent?.getStringExtra("page")
        page = when {
            !targetPage.isNullOrBlank() -> targetPage
            this is GateActivity && reason != null && reason !in listOf("timeup", "feedback") -> "lock?reason=$reason"
            else -> "quiz"
        }
        if (::web.isInitialized && !webReleased) {
            web.loadUrl(baseUrl + "#/$page")
            web.evaluateJavascript("window.SOTV_RESUME&&SOTV_RESUME()", null)
        }
    }

    override fun onResume() {
        super.onResume()
        cancelScheduledKill()
        if (!speechReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(CoreBridge.ACTION_SPEECH_NOTICE)
                addAction(CloudTunnelClient.ACTION_LESSON_CTRL)
                addAction(CloudTunnelClient.ACTION_TOAST)
                addAction(CloudTunnelClient.ACTION_LESSON_UPDATED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(speechReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(speechReceiver, filter)
            }
            speechReceiverRegistered = true
        }
        if (isCore) {
            MediaPause.recover(this)
            if (Prefs.onboarded(this)) GateService.ensureRunning(this, "app")
            if (this is GateActivity && !DeviceRuntime.learning.status().optBoolean("free")) MediaPause.pause(this)
        } else {
            CoreBridge.mediaPause(this, "recover")
            CoreBridge.reportActivity(this, true, javaClass.name)
            if (this is GateActivity && !CoreBridge.learningStatus(this).optBoolean("free")) {
                CoreBridge.mediaPause(this, "pause")
            }
        }
        if (webReleased) createWeb()
        checkUpdate()
        if (::web.isInitialized && !webReleased) web.evaluateJavascript("window.SOTV_RESUME&&SOTV_RESUME()", null)
    }

    override fun onPause() {
        if (!isCore) {
            CoreBridge.reportActivity(this, false, javaClass.name)
        }
        if (speechReceiverRegistered) {
            try { unregisterReceiver(speechReceiver) } catch (_: Throwable) { }
            speechReceiverRegistered = false
        }
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!DeviceBridge.isTv(this) && event.keyCode == KeyEvent.KEYCODE_ENTER) return super.dispatchKeyEvent(event)
        val view = if (::web.isInitialized && !webReleased) web else null
        return remote.handle(view, event) || super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (speechReceiverRegistered) {
            try { unregisterReceiver(speechReceiver) } catch (_: Throwable) { }
            speechReceiverRegistered = false
        }
        if (::web.isInitialized && !webReleased) web.destroy()
        super.onDestroy()
    }

    companion object {
        private val startedCount = AtomicInteger(0)
        private val killHandler = Handler(Looper.getMainLooper())
        private val killRunnable = Runnable {
            if (startedCount.get() <= 0 && !InputSupport.active && !AnswerInput.active) {
                Process.killProcess(Process.myPid())
            }
        }

        private fun cancelScheduledKill() {
            killHandler.removeCallbacks(killRunnable)
        }

        private fun scheduleGateProcessKill() {
            killHandler.removeCallbacks(killRunnable)
            // 预留 1.5s 确保过渡动画和窗口回收干净后彻底销毁 `:gate` 进程，归还 80+ MB WebView 内存
            killHandler.postDelayed(killRunnable, 1500L)
        }
    }
}

package com.studyontv.tv

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.CertificateNotYetValidException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import java.util.Random
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/**
 * 轻电视端统一云端长连接通道管理器：
 * 1. 先连 HTTPS 长轮询打底（PollTransport），hello_ack 后后台探测 WSS 并自动升级（WsTransport）；
 * 2. WS 异常或 60s 无数据立即回落长轮询；10 分钟内失败 3 次熔断 6 小时，网络变化时清零；
 * 3. 消息 ID 200 条 LRU 去重，校准服务端时钟后做 expiresAt 过滤，执行完毕回 ack；
 * 4. 隧道 http_req 直接在进程内以 local=false 调用 LanServer，杜绝越权与回环假身份。
 */
object CloudTunnelClient : TunnelListener {
    const val ACTION_LESSON_CTRL = "com.studyontv.tv.action.LESSON_CTRL"
    const val ACTION_TOAST = "com.studyontv.tv.action.CLOUD_TOAST"
    const val ACTION_LESSON_UPDATED = "com.studyontv.tv.action.LESSON_UPDATED"

    private val worker = Executors.newCachedThreadPool()
    private val random = Random()
    private val started = AtomicBoolean(false)

    @Volatile private var appContext: Context? = null
    @Volatile private var primary: Transport? = null
    @Volatile private var probeWs: WsTransport? = null
    @Volatile private var clockOffsetMs: Long = 0L
    @Volatile private var lastClockCalibrated: Boolean = false
    @Volatile private var lastReconnectAttemptAt = 0L
    @Volatile private var reconnectAttempts = 0
    @Volatile private var lastSnapshotAt = 0L
    @Volatile private var networkCallbackRegistered = false

    private val wsFailTimestamps = ArrayList<Long>()
    private val seenIds = LinkedHashSet<String>()
    private val seenLock = Any()

    private val BACKOFF_MS = longArrayOf(3000L, 5000L, 10_000L, 30_000L, 60_000L)

    fun startIfPaired(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!Prefs.isCloudPaired(app)) return
        registerNetworkWatch(app)
        if (!started.compareAndSet(false, true)) return
        connectLongPoll(app)
    }

    fun restart(context: Context) {
        stop()
        val app = context.applicationContext
        appContext = app
        if (Prefs.isCloudPaired(app)) {
            started.set(true)
            registerNetworkWatch(app)
            connectLongPoll(app)
        }
    }

    fun stop() {
        started.set(false)
        try { probeWs?.stop() } catch (_: Throwable) { }
        probeWs = null
        try { primary?.stop() } catch (_: Throwable) { }
        primary = null
    }

    fun tick(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!Prefs.isCloudPaired(app)) return
        startIfPaired(app)
        val now = SystemClock.elapsedRealtime()
        if (primary == null && now - lastReconnectAttemptAt > currentBackoffMs()) {
            connectLongPoll(app)
        }
        if (now - lastSnapshotAt > 5 * 60_000L) {
            pushSnapshot(app, force = false)
        }
    }

    fun pushSnapshotSoon(context: Context) {
        val app = context.applicationContext
        if (!Prefs.isCloudPaired(app)) return
        pushSnapshot(app, force = true)
    }

    /** 开机一诗钩子（每个自然日仅第一次开屏/唤醒拉取并展示一次）。 */
    fun checkBootPoem(context: Context) {
        val app = context.applicationContext
        if (!Prefs.isCloudPaired(app)) return
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (Prefs.bootPoemDate(app) == today) return
        val policy = LessonStore.checkDisplayPolicy(app, isQuizBusy = Foreground.hasResumedActivity())
        if (policy != null) return
        worker.execute {
            try {
                val origin = Prefs.cloudOrigin(app)
                val did = Prefs.ensureDeviceId(app)
                val sec = Prefs.ensureDeviceSecret(app)
                val conn = (URL("$origin/ziyin/tv/lesson/auto?deviceId=$did").openConnection() as HttpURLConnection).apply {
                    if (this is HttpsURLConnection) sslSocketFactory = CloudCerts.sslSocketFactory(app)
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 8000
                    setRequestProperty("Authorization", "Bearer $sec")
                    setRequestProperty("X-Sotv-Device", did)
                }
                val txt = try {
                    if (conn.responseCode !in 200..299) return@execute
                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally {
                    conn.disconnect()
                }
                val lesson = JSONObject(txt).optJSONObject("data") ?: return@execute
                if (!lesson.has("sections")) return@execute
                Prefs.setBootPoemDate(app, today)
                val body = JSONObject().put("runMode", "auto").put("lesson", lesson)
                LessonStore.saveCurrent(app, body)
                launchLessonScreen(app, lesson.optString("mode", "poem"))
            } catch (_: Throwable) { }
        }
    }

    private fun currentBackoffMs(): Long {
        val base = BACKOFF_MS[reconnectAttempts.coerceAtMost(BACKOFF_MS.size - 1)]
        val jitter = (base * 0.2 * (random.nextDouble() * 2 - 1)).toLong()
        return (base + jitter).coerceAtLeast(1500L)
    }

    @Synchronized
    private fun connectLongPoll(app: Context) {
        if (!started.get()) return
        lastReconnectAttemptAt = SystemClock.elapsedRealtime()
        reconnectAttempts++
        try { primary?.stop() } catch (_: Throwable) { }
        val origin = Prefs.cloudOrigin(app)
        val did = Prefs.ensureDeviceId(app)
        val sec = Prefs.ensureDeviceSecret(app)
        val lp = LongPollTransport(app, origin, did, sec, this)
        primary = lp
        lp.start()
    }

    override fun onConnected(transportName: String) {
        val app = appContext ?: return
        reconnectAttempts = 0
        Diag.info("CloudTunnel", "connected via $transportName")
        val hello = envelope(
            "hello",
            JSONObject()
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("protocol", 1)
                .put("deviceName", Prefs.deviceName(app))
                .put("channel", transportName)
                .put("caps", JSONArray(listOf("lesson", "quiz", "toast", "lesson_ctrl", "dictation", "tunnel_http")))
                .put("activeChild", try { DeviceRuntime.store.activeChild() } catch (_: Throwable) { "" })
                .put("permissions", permissionSnapshot(app))
        )
        if (transportName == "ws") {
            probeWs?.send(hello)
        } else {
            primary?.send(hello)
            pushSnapshot(app, force = true)
        }
    }

    override fun onMessage(envelope: JSONObject, transportName: String) {
        val app = appContext ?: return
        val id = envelope.optString("id")
        val type = envelope.optString("type")
        val body = envelope.optJSONObject("body") ?: JSONObject()

        if (type == "hello_ack") {
            val srvTime = body.optLong("serverTime", 0L)
            if (srvTime > 1_700_000_000_000L) {
                clockOffsetMs = srvTime - System.currentTimeMillis()
                lastClockCalibrated = true
            }
            val transports = body.optJSONArray("transports")
            val wsSupported = transports != null && (0 until transports.length()).any { transports.optString(it) == "ws" }
            val wsPath = body.optString("wsPath", "/ziyin/tv/ws?deviceId=${Prefs.ensureDeviceId(app)}")
            if (wsSupported && transportName == "poll" && probeWs == null && primary?.name != "ws") {
                maybeProbeWs(app, wsPath)
            }
            return
        }

        if (type == "ping") {
            send(envelope("pong", JSONObject().put("ts", System.currentTimeMillis())))
            return
        }
        if (type == "pong") {
            // WS ping/pong 验证通过：正式升级主通道并停止长轮询
            if (transportName == "ws" && probeWs != null && primary !== probeWs) {
                val old = primary
                primary = probeWs
                old?.stop()
                Diag.info("CloudTunnel", "upgraded primary transport to ws")
            }
            return
        }

        // 去重（仅对非 http_req 控制/推屏消息做，或全部消息 ID 去重）
        if (id.isNotEmpty() && !markSeen(id)) {
            if (type in listOf("push_lesson", "push_quiz", "cmd")) {
                sendAck(id, "ok", "dedup")
            }
            return
        }

        // 过期判断（仅在服务端时间已校准或本地时钟>=2024年时启用，避开老电视 1970 坑）
        val exp = envelope.optLong("expiresAt", 0L)
        val estimatedNow = System.currentTimeMillis() + clockOffsetMs
        if (exp > 0 && (lastClockCalibrated || System.currentTimeMillis() > 1_700_000_000_000L) && estimatedNow > exp) {
            if (id.isNotEmpty()) sendAck(id, "rejected:expired", "expired")
            return
        }

        when (type) {
            "http_req" -> worker.execute { handleTunnelHttp(id, body) }
            "push_lesson" -> worker.execute { handlePushLesson(app, id, body) }
            "push_quiz" -> worker.execute { handlePushQuiz(app, id, body) }
            "lesson_ctrl" -> {
                sendLocalBroadcast(app, ACTION_LESSON_CTRL, body)
                sendAck(id, "ok", "")
            }
            "toast" -> {
                sendLocalBroadcast(app, ACTION_TOAST, body)
                val audio = body.optString("ttsAudio")
                if (audio.isNotEmpty()) {
                    val full = resolveAudioUrl(app, audio)
                    if (full.isNotEmpty()) try { SpeechReader.playAudio(app, full) {} } catch (_: Throwable) { }
                }
                sendAck(id, "ok", "")
            }
            "cmd" -> handleCmd(app, id, body)
        }
    }

    override fun onDisconnected(transportName: String, reason: String) {
        val app = appContext ?: return
        Diag.warn("CloudTunnel", "disconnected ($transportName): $reason")
        if (transportName == "ws") {
            probeWs = null
            recordWsFailure(app)
            if (primary?.name == "ws") {
                primary = null
                if (started.get()) connectLongPoll(app)
            }
            return
        }
        if (primary?.name == "poll") {
            primary = null
        }
        // 如果是证书时钟未就绪等可恢复异常，继续按退避重连
        if (reason.contains(CertificateNotYetValidException::class.java.simpleName) || reason.contains("1970")) {
            Diag.warn("CloudTunnel", "clock not synced yet, will retry")
        }
        if (started.get()) {
            val delay = currentBackoffMs()
            Handler(Looper.getMainLooper()).postDelayed({
                if (started.get() && primary == null) connectLongPoll(app)
            }, delay)
        }
    }

    private fun maybeProbeWs(app: Context, wsPath: String) {
        val origin = Prefs.cloudOrigin(app)
        if (!origin.startsWith("https://")) return
        if (System.currentTimeMillis() < Prefs.wsBreakerUntil(app)) return
        val ws = WsTransport(app, origin, wsPath, Prefs.ensureDeviceId(app), Prefs.ensureDeviceSecret(app), object : TunnelListener {
            override fun onConnected(transportName: String) {
                this@CloudTunnelClient.onConnected(transportName)
                // 发起一次 ping，收到 pong 后才真正切换主通道
                probeWs?.send(envelope("ping", JSONObject().put("probe", true)))
            }
            override fun onMessage(envelope: JSONObject, transportName: String) = this@CloudTunnelClient.onMessage(envelope, transportName)
            override fun onDisconnected(transportName: String, reason: String) = this@CloudTunnelClient.onDisconnected(transportName, reason)
        })
        probeWs = ws
        ws.start()
    }

    @Synchronized
    private fun recordWsFailure(app: Context) {
        val now = System.currentTimeMillis()
        wsFailTimestamps.add(now)
        wsFailTimestamps.removeAll { now - it > 10 * 60_000L }
        if (wsFailTimestamps.size >= 3) {
            Prefs.setWsBreakerUntil(app, now + 6 * 3600_000L)
            wsFailTimestamps.clear()
            Diag.warn("CloudTunnel", "WS failed 3 times in 10m, tripping circuit breaker for 6h")
        }
    }

    private fun handleTunnelHttp(msgId: String, body: JSONObject) {
        val reqId = body.optString("reqId")
        val method = body.optString("method", "GET")
        val path = body.optString("path", "")
        val query = body.optString("query", "")
        val hdrJson = body.optJSONObject("headers") ?: JSONObject()
        val headers = HashMap<String, String>()
        hdrJson.keys().forEach { k -> headers[k.lowercase()] = hdrJson.optString(k) }
        val rawBody = body.optString("bodyBase64", "").let {
            if (it.isEmpty()) "" else String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8)
        }

        val server = DeviceRuntime.lanServer()
        if (server == null) {
            send(envelope("http_resp", JSONObject().put("reqId", reqId).put("status", 503).put("bodyBase64", "")))
            return
        }
        val resp = server.dispatchTunnel(method, path, query, headers, rawBody)
        val respHdrs = JSONObject()
        resp.headers.forEach { (k, v) -> respHdrs.put(k, v) }
        val respB64 = Base64.encodeToString(resp.body, Base64.NO_WRAP)
        send(
            envelope(
                "http_resp",
                JSONObject()
                    .put("reqId", reqId)
                    .put("status", resp.status)
                    .put("headers", respHdrs)
                    .put("bodyBase64", respB64)
            )
        )
        if (msgId.isNotEmpty()) sendAck(msgId, "ok", "")
    }

    private fun handlePushLesson(app: Context, msgId: String, body: JSONObject) {
        val lesson = body.optJSONObject("lesson") ?: run {
            sendAck(msgId, "rejected:unsupported", "missing lesson")
            return
        }
        LessonStore.saveCurrent(app, body)
        val isQuizBusy = Foreground.hasResumedActivity() && DeviceRuntime.learning.status().optBoolean("inQuiz", false)
        val rejectOrQueue = LessonStore.checkDisplayPolicy(app, isQuizBusy)
        if (rejectOrQueue == "queued") {
            LessonStore.enqueuePendingLesson(body)
            sendAck(msgId, "queued", "in_quiz")
            return
        }
        if (rejectOrQueue != null) {
            sendAck(msgId, rejectOrQueue, "")
            return
        }
        val mode = lesson.optString("mode", "poem")
        launchLessonScreen(app, mode)
        sendAck(msgId, "shown", "")
    }

    private fun handlePushQuiz(app: Context, msgId: String, body: JSONObject) {
        val items = body.optJSONArray("items")
        if (items == null || items.length() == 0) {
            sendAck(msgId, "rejected:unsupported", "empty items")
            return
        }
        val isQuizBusy = Foreground.hasResumedActivity() && DeviceRuntime.learning.status().optBoolean("inQuiz", false)
        val rejectOrQueue = LessonStore.checkDisplayPolicy(app, isQuizBusy)
        if (rejectOrQueue == "queued") {
            LessonStore.setPendingPushQuiz(body)
            sendAck(msgId, "queued", "in_quiz")
            return
        }
        if (rejectOrQueue != null) {
            sendAck(msgId, rejectOrQueue, "")
            return
        }
        LessonStore.setActivePushQuiz(body)
        Handler(Looper.getMainLooper()).post {
            try {
                app.startActivity(
                    Intent(app, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("page", "push-quiz")
                )
            } catch (e: Throwable) {
                Diag.warn("CloudTunnel", "start push-quiz failed: ${e.message}")
            }
        }
        sendAck(msgId, "shown", "")
    }

    private fun handleCmd(app: Context, msgId: String, body: JSONObject) {
        val action = body.optString("action")
        try {
            when (action) {
                "sync_bank" -> BankAutomation.start(DeviceRuntime.store)
                "start_quiz" -> Handler(Looper.getMainLooper()).post {
                    app.startActivity(
                        Intent(app, GateActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra("reason", "manual")
                    )
                }
                // 注意：安全基线规定云端普通 cmd 不再执行免密 unlock / update_config
            }
            sendAck(msgId, "ok", action)
        } catch (e: Throwable) {
            sendAck(msgId, "error", e.message ?: "")
        }
    }

    private fun launchLessonScreen(app: Context, mode: String) {
        val targetPage = if (mode == "dictation") "dictation" else "lesson"
        Handler(Looper.getMainLooper()).post {
            try {
                MediaPause.pause(app)
                app.startActivity(
                    Intent(app, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("page", targetPage)
                )
                sendLocalBroadcast(app, ACTION_LESSON_UPDATED, JSONObject().put("page", targetPage))
            } catch (e: Throwable) {
                Diag.warn("CloudTunnel", "launchLessonScreen failed: ${e.message}")
            }
        }
    }

    private fun sendLocalBroadcast(app: Context, action: String, payload: JSONObject) {
        try {
            app.sendBroadcast(
                Intent(action)
                    .setPackage(app.packageName)
                    .putExtra(CoreBridge.EXTRA_PAYLOAD, payload.toString())
            )
        } catch (_: Throwable) { }
    }

    fun pushSnapshot(context: Context, force: Boolean) {
        val app = context.applicationContext
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSnapshotAt < 60_000L) return
        lastSnapshotAt = now

        worker.execute {
            try {
                val store = DeviceRuntime.store
                val cfg = store.config()
                // 剔除敏感密钥字段
                val cleanCfg = JSONObject(cfg.toString()).apply {
                    remove("aiApiKey")
                    remove("apiKey")
                }
                val snap = JSONObject()
                    .put("deviceName", Prefs.deviceName(app))
                    .put("port", Prefs.localPort(app))
                    .put("version", BuildConfig.VERSION_NAME)
                    .put("activeChild", store.activeChild())
                    .put("today", try { store.todayStats() } catch (_: Throwable) { JSONObject() })
                    .put("learning", try { DeviceRuntime.learning.status() } catch (_: Throwable) { JSONObject() })
                    .put("questions", try { store.countQuestions() } catch (_: Throwable) { 0 })
                    .put("config", cleanCfg)
                    .put("permissions", permissionSnapshot(app))
                send(envelope("snapshot", snap))
            } catch (_: Throwable) { }
        }
    }

    fun permissionSnapshot(context: Context): JSONObject {
        val overlay = OverlayAccess.canDraw(context)
        val a11y = WatchAccessibilityService.connected
        val usage = Foreground.hasUsageAccess(context)
        val guardReady = a11y || (overlay && usage)
        return JSONObject()
            .put("tier", if (guardReady) "guard" else "study")
            .put("overlay", overlay)
            .put("accessibility", a11y)
            .put("usageStats", usage)
            .put("batteryWhitelist", KeepAlive.ignoringBattery(context))
            .put("notificationListener", RevivalListener.enabled(context))
    }

    private fun resolveAudioUrl(app: Context, raw: String): String {
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val base = Prefs.audioBaseUrl(app)
        if (base.isEmpty()) return ""
        return "$base${if (raw.startsWith("/")) raw else "/$raw"}"
    }

    private fun sendAck(targetId: String, status: String, detail: String) {
        if (targetId.isEmpty()) return
        send(envelope("ack", JSONObject().put("targetId", targetId).put("status", status).put("detail", detail)))
    }

    private fun send(env: JSONObject) {
        primary?.send(env)
    }

    private fun envelope(type: String, body: JSONObject): JSONObject = JSONObject()
        .put("v", 1)
        .put("id", "d_" + UUID.randomUUID().toString().replace("-", "").take(12))
        .put("type", type)
        .put("ts", System.currentTimeMillis())
        .put("body", body)

    private fun markSeen(id: String): Boolean = synchronized(seenLock) {
        if (seenIds.contains(id)) return false
        seenIds.add(id)
        if (seenIds.size > 200) {
            val it = seenIds.iterator()
            if (it.hasNext()) { it.next(); it.remove() }
        }
        true
    }

    private fun registerNetworkWatch(app: Context) {
        if (networkCallbackRegistered || Build.VERSION.SDK_INT < 24) return
        networkCallbackRegistered = true
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Prefs.setWsBreakerUntil(app, 0L)
                    synchronized(this@CloudTunnelClient) { wsFailTimestamps.clear() }
                    if (started.get() && primary == null) {
                        connectLongPoll(app)
                    }
                }
            })
        } catch (_: Throwable) { }
    }
}

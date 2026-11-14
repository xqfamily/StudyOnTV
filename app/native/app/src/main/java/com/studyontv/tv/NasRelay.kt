package com.studyontv.tv

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * NAS 状态镜像与离线指令中继客户端：
 * 从已配置的远程题库源地址推导 NAS Origin，定时/按事件把今日学习进度与设备快照推送到
 * `/ziyin/tv/device/heartbeat`，并消费来自 NAS 的控制指令（sync_bank / unlock / start_quiz / update_config）。
 */
object NasRelay {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var lastPushAt = 0L
    @Volatile private var inFlight = false
    private const val HEARTBEAT_INTERVAL_MS = 60_000L

    fun tick(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (lastPushAt > 0 && now - lastPushAt < HEARTBEAT_INTERVAL_MS) return
        pushAsync(context.applicationContext, force = false)
    }

    fun pushSoon(context: Context) {
        pushAsync(context.applicationContext, force = true)
    }

    private fun resolveNasOrigin(store: LocalStore): String? {
        val sources = BankAutomation.settings(store).optJSONArray("sources") ?: return null
        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            if (!s.optBoolean("enabled", true)) continue
            val raw = s.optString("url").trim()
            if (!raw.startsWith("http://") && !raw.startsWith("https://")) continue
            try {
                val u = URL(raw)
                val port = if (u.port > 0) ":${u.port}" else ""
                return "${u.protocol}://${u.host}$port"
            } catch (_: Exception) { }
        }
        return null
    }

    private fun pushAsync(appContext: Context, force: Boolean) {
        if (inFlight) return
        val now = SystemClock.elapsedRealtime()
        if (!force && lastPushAt > 0 && now - lastPushAt < HEARTBEAT_INTERVAL_MS) return
        inFlight = true
        lastPushAt = now
        worker.execute {
            try {
                val store = DeviceRuntime.store
                val learning = DeviceRuntime.learning
                val origin = resolveNasOrigin(store) ?: return@execute
                val payload = JSONObject()
                    .put("deviceName", Prefs.deviceName(appContext))
                    .put("port", Prefs.localPort(appContext))
                    .put("version", BuildConfig.VERSION_NAME)
                    .put("today", try { store.todayStats() } catch (_: Throwable) { JSONObject() })
                    .put("learning", try { learning.status() } catch (_: Throwable) { JSONObject() })
                    .put("questions", try { store.countQuestions() } catch (_: Throwable) { 0 })
                    .put("config", try { store.config() } catch (_: Throwable) { JSONObject() })
                    .put("recentHistory", try { store.historyDays(7) } catch (_: Throwable) { JSONArray() })

                val conn = (URL("$origin/ziyin/tv/device/heartbeat").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val respText = try {
                    conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode !in 200..299) return@execute
                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally {
                    conn.disconnect()
                }
                val root = JSONObject(respText)
                val data = root.optJSONObject("data") ?: return@execute
                val cmds = data.optJSONArray("commands") ?: return@execute
                if (cmds.length() > 0) handleCommands(appContext, store, learning, cmds)
            } catch (e: Throwable) {
                Diag.warn("NasRelay", "heartbeat error: ${e.message}")
            } finally {
                inFlight = false
            }
        }
    }

    private fun handleCommands(appContext: Context, store: LocalStore, learning: LearningEngine, cmds: JSONArray) {
        val main = Handler(Looper.getMainLooper())
        for (i in 0 until cmds.length()) {
            val cmd = cmds.optJSONObject(i) ?: continue
            val action = cmd.optString("action")
            val payload = cmd.optJSONObject("payload") ?: JSONObject()
            try {
                when (action) {
                    "sync_bank" -> BankAutomation.start(store)
                    "unlock" -> {
                        val minutes = payload.optInt("minutes", 15).coerceIn(1, 240)
                        learning.remote("unlock", minutes, "nas-relay")
                        GateService.refreshNow()
                    }
                    "update_config" -> {
                        if (payload.length() > 0) {
                            learning.saveConfig(payload)
                            GateService.refreshNow()
                        }
                    }
                    "start_quiz" -> {
                        main.post {
                            try {
                                appContext.startActivity(
                                    Intent(appContext, GateActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                        .putExtra("reason", "manual")
                                )
                            } catch (e: Throwable) {
                                Diag.warn("NasRelay", "start_quiz failed: ${e.message}")
                            }
                        }
                    }
                }
                Diag.info("NasRelay", "executed command: $action")
            } catch (e: Throwable) {
                Diag.warn("NasRelay", "command $action failed: ${e.message}")
            }
        }
    }
}

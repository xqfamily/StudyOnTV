package com.studyontv.tv

import android.content.Context
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 教学板卡片本地持久化、音频预下载缓存、推屏状态机与奖励截断器。
 */
object LessonStore {
    private const val FILE_NAME = "lesson_current.json"
    private const val AUDIO_DIR = "lesson_audio"
    @Volatile private var queuedLesson: JSONObject? = null
    @Volatile private var queuedQuiz: JSONObject? = null
    @Volatile private var activePushQuiz: JSONObject? = null

    private fun lessonFile(context: Context): File = File(context.filesDir, FILE_NAME)
    fun audioDir(context: Context): File = File(context.cacheDir, AUDIO_DIR).apply { mkdirs() }

    @Synchronized
    fun saveCurrent(context: Context, payload: JSONObject) {
        val localized = localizeAudios(context, payload)
        lessonFile(context).writeText(localized.toString(), Charsets.UTF_8)
    }

    @Synchronized
    fun current(context: Context): JSONObject {
        val f = lessonFile(context)
        if (!f.exists()) return JSONObject()
        return try {
            JSONObject(f.readText(Charsets.UTF_8))
        } catch (_: Throwable) {
            JSONObject()
        }
    }

    @Synchronized
    fun enqueuePendingLesson(payload: JSONObject) {
        queuedLesson = payload
    }

    @Synchronized
    fun popPendingLesson(): JSONObject? {
        val v = queuedLesson
        queuedLesson = null
        return v
    }

    @Synchronized
    fun setPendingPushQuiz(payload: JSONObject?) {
        queuedQuiz = payload
    }

    @Synchronized
    fun popPendingPushQuiz(): JSONObject? {
        val v = queuedQuiz
        queuedQuiz = null
        return v
    }

    @Synchronized
    fun setActivePushQuiz(payload: JSONObject?) {
        activePushQuiz = payload
    }

    @Synchronized
    fun activePushQuiz(): JSONObject? = activePushQuiz

    /** 将教学卡片里的相对音频路径下载到本地缓存并替换为本机回环地址，确保断网/秒播且绕开系统 MediaPlayer HTTPS 限制。 */
    private fun localizeAudios(context: Context, root: JSONObject): JSONObject {
        val copy = JSONObject(root.toString())
        val lesson = copy.optJSONObject("lesson") ?: return copy
        val audioOrigin = Prefs.audioBaseUrl(context)
        val port = Prefs.localPort(context)

        fun localizeUrl(raw: String): String {
            if (raw.isBlank()) return ""
            val full = when {
                raw.startsWith("http://") || raw.startsWith("https://") -> raw
                audioOrigin.isNotEmpty() -> "$audioOrigin${if (raw.startsWith("/")) raw else "/$raw"}"
                else -> return ""
            }
            return try {
                val safeName = full.substringAfterLast("/").substringBefore("?").replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    .ifBlank { "a_${full.hashCode()}.mp3" }
                val target = File(audioDir(context), safeName)
                if (!target.exists() || target.length() < 256) {
                    val conn = URL(full).openConnection() as HttpURLConnection
                    if (conn is javax.net.ssl.HttpsURLConnection) {
                        conn.sslSocketFactory = CloudCerts.sslSocketFactory
                    }
                    conn.connectTimeout = 5000
                    conn.readTimeout = 10000
                    try {
                        if (conn.responseCode in 200..299) {
                            val tmp = File(target.parentFile, "$safeName.tmp")
                            conn.inputStream.use { inp -> tmp.outputStream().use { out -> inp.copyTo(out) } }
                            if (tmp.length() > 256) tmp.renameTo(target) else tmp.delete()
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
                if (target.exists() && target.length() > 256) "http://127.0.0.1:$port/lesson-audio/$safeName" else full
            } catch (_: Throwable) {
                full
            }
        }

        val fullAudio = lesson.optString("fullAudio")
        if (fullAudio.isNotEmpty()) lesson.put("fullAudio", localizeUrl(fullAudio))
        val secs = lesson.optJSONArray("sections")
        if (secs != null) {
            for (i in 0 until secs.length()) {
                val sec = secs.optJSONObject(i) ?: continue
                val a = sec.optString("audio")
                if (a.isNotEmpty()) sec.put("audio", localizeUrl(a))
            }
        }
        return copy
    }

    /** 推屏状态机检查（规划 4.4.2）。返回 null 表示可立即展示，否则返回 reject/queued 状态码。 */
    fun checkDisplayPolicy(context: Context, isQuizBusy: Boolean): String? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val interactive = pm?.isInteractive ?: true
        if (!interactive) return "rejected:standby"

        // 免打扰时段检查（默认 21:00–07:00）
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val cfg = try { DeviceRuntime.store.config() } catch (_: Throwable) { JSONObject() }
        val qStart = TimePointsMath.minutes(cfg.optString("quietStart", "21:00")) ?: (21 * 60)
        val qEnd = TimePointsMath.minutes(cfg.optString("quietEnd", "07:00")) ?: (7 * 60)
        if (TimePointsMath.inRange(qStart, qEnd, nowMin)) return "rejected:quiet"

        if (isQuizBusy) return "queued"
        return null
    }

    /** 推屏奖励测核算（按首次正确率 >=80% 且受电视本地配置单次/每日上限严格截断）。 */
    fun settlePushQuizReward(
        context: Context,
        store: LocalStore,
        learning: LearningEngine,
        correctFirstTry: Int,
        total: Int,
        requestedMinutes: Int,
    ): Int {
        if (requestedMinutes <= 0 || total <= 0) return 0
        if (correctFirstTry * 100 < total * 80) return 0
        val cfg = store.config()
        val maxOnce = cfg.optInt("pushRewardMaxOnce", 0).coerceIn(0, 60)
        val maxDaily = cfg.optInt("pushRewardMaxDaily", 0).coerceIn(0, 240)
        if (maxOnce <= 0 || maxDaily <= 0) return 0

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val used = Prefs.pushRewardUsedToday(context, today)
        val remainDaily = (maxDaily - used).coerceAtLeast(0)
        val granted = minOf(requestedMinutes, maxOnce, remainDaily)
        if (granted > 0) {
            Prefs.addPushRewardUsed(context, today, granted)
            learning.remote("credit", granted, "push-quiz")
            GateService.refreshNow()
        }
        return granted
    }
}

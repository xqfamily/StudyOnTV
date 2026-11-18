package com.studyontv.tv

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

object Prefs {
    private const val FILE = "sotv"
    /** 预置默认云端地址（可在配对页修改） */
    const val DEFAULT_CLOUD_ORIGIN = ""
    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun localPort(c: Context): Int = sp(c).getInt("local_port", 8099)
    fun setLocalPort(c: Context, port: Int) = sp(c).edit().putInt("local_port", port).apply()

    fun localBase(c: Context): String = "http://127.0.0.1:" + localPort(c) + "/"

    fun onboarded(c: Context): Boolean = sp(c).getBoolean("onboarded", false)
    fun setOnboarded(c: Context, v: Boolean) = sp(c).edit().putBoolean("onboarded", v).apply()

    fun deviceName(c: Context): String =
        sp(c).getString("device_name", android.os.Build.MODEL ?: "客厅电视") ?: "客厅电视"
    fun setDeviceName(c: Context, v: String) = sp(c).edit().putString("device_name", v).apply()

    // ===== 云端配对与长连接凭证 =====
    fun cloudOrigin(c: Context): String = (sp(c).getString("cloud_origin", DEFAULT_CLOUD_ORIGIN) ?: "").trim().trimEnd('/')
    fun setCloudOrigin(c: Context, v: String) = sp(c).edit().putString("cloud_origin", v.trim().trimEnd('/')).apply()

    fun audioBaseUrl(c: Context): String {
        val explicit = (sp(c).getString("audio_base_url", "") ?: "").trim().trimEnd('/')
        if (explicit.isNotEmpty()) return explicit
        return cloudOrigin(c)
    }
    fun setAudioBaseUrl(c: Context, v: String) = sp(c).edit().putString("audio_base_url", v.trim().trimEnd('/')).apply()

    @Synchronized
    fun ensureDeviceId(c: Context): String {
        val saved = sp(c).getString("cloud_device_id", "") ?: ""
        if (saved.length in 16..32 && saved.all { it in '0'..'9' || it in 'a'..'f' }) return saved
        val id = UUID.randomUUID().toString().replace("-", "").lowercase()
        sp(c).edit().putString("cloud_device_id", id).commit()
        return id
    }

    @Synchronized
    fun ensureDeviceSecret(c: Context): String {
        val saved = sp(c).getString("cloud_device_secret", "") ?: ""
        if (saved.length >= 32) return saved
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val secret = bytes.joinToString("") { "%02x".format(it) }
        sp(c).edit().putString("cloud_device_secret", secret).commit()
        return secret
    }

    fun deviceSecretHash(c: Context): String {
        val sec = ensureDeviceSecret(c)
        return MessageDigest.getInstance("SHA-256").digest(sec.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun familyKey(c: Context): String = sp(c).getString("cloud_family_key", "") ?: ""
    fun setFamilyKey(c: Context, v: String) = sp(c).edit().putString("cloud_family_key", v).commit()

    fun familyName(c: Context): String = sp(c).getString("cloud_family_name", "") ?: ""
    fun setFamilyName(c: Context, v: String) = sp(c).edit().putString("cloud_family_name", v).commit()

    fun bankToken(c: Context): String = sp(c).getString("cloud_bank_token", "") ?: ""
    fun setBankToken(c: Context, v: String) = sp(c).edit().putString("cloud_bank_token", v).commit()

    fun isCloudPaired(c: Context): Boolean = cloudOrigin(c).isNotEmpty() && familyKey(c).isNotEmpty()

    fun clearCloudPairing(c: Context) {
        sp(c).edit()
            .remove("cloud_family_key")
            .remove("cloud_family_name")
            .remove("cloud_bank_token")
            .commit()
    }

    fun wsBreakerUntil(c: Context): Long = sp(c).getLong("ws_breaker_until", 0L)
    fun setWsBreakerUntil(c: Context, ts: Long) = sp(c).edit().putLong("ws_breaker_until", ts).apply()

    fun bootPoemDate(c: Context): String = sp(c).getString("boot_poem_date", "") ?: ""
    fun setBootPoemDate(c: Context, day: String) = sp(c).edit().putString("boot_poem_date", day).apply()

    fun pushRewardUsedToday(c: Context, today: String): Int {
        val savedDay = sp(c).getString("push_reward_day", "") ?: ""
        return if (savedDay == today) sp(c).getInt("push_reward_used", 0) else 0
    }

    fun addPushRewardUsed(c: Context, today: String, minutes: Int) {
        val cur = pushRewardUsedToday(c, today)
        sp(c).edit().putString("push_reward_day", today).putInt("push_reward_used", cur + minutes).commit()
    }

    fun updateInfo(c: Context): String = sp(c).getString("update_info", "") ?: ""
    fun setUpdateInfo(c: Context, v: String) = sp(c).edit().putString("update_info", v).apply()
    fun updateCheckedAt(c: Context): Long = sp(c).getLong("update_checked_at", 0L)
    fun setUpdateCheckedAt(c: Context, v: Long) = sp(c).edit().putLong("update_checked_at", v).apply()
    fun updateApi(c: Context): String = sp(c).getString("update_api", "") ?: ""
    fun setUpdateApi(c: Context, v: String) = sp(c).edit().putString("update_api", v).apply()
    fun updateMirrors(c: Context): String? = sp(c).getString("update_mirrors", null)
    fun setUpdateMirrors(c: Context, v: String?) = sp(c).edit().apply { if (v == null) remove("update_mirrors") else putString("update_mirrors", v) }.apply()
    fun updateLastSource(c: Context): String = sp(c).getString("update_last_source", "") ?: ""
    fun setUpdateLastSource(c: Context, v: String) = sp(c).edit().putString("update_last_source", v).apply()
    fun updateLog(c: Context): String = sp(c).getString("update_log", "") ?: ""
    fun setUpdateLog(c: Context, v: String) = sp(c).edit().putString("update_log", v).apply()

    fun updateInProgress(c: Context): Boolean = sp(c).getLong("update_started_at", 0L).let { it > 0 && System.currentTimeMillis() - it < 10 * 60 * 1000 }
    fun setUpdateInProgress(c: Context, v: Boolean) = sp(c).edit().putLong("update_started_at", if (v) System.currentTimeMillis() else 0L).apply()

    fun serviceRunning(c: Context): Boolean = sp(c).getBoolean("service_running", false)
    fun setServiceRunning(c: Context, v: Boolean) = sp(c).edit().putBoolean("service_running", v).apply()

    fun accessibilityLostCount(c: Context): Int = sp(c).getInt("accessibility_lost_count", 0)
    fun setAccessibilityLostCount(c: Context, v: Int) = sp(c).edit().putInt("accessibility_lost_count", v).apply()
    fun accessibilityLostAt(c: Context): Long = sp(c).getLong("accessibility_lost_at", 0L)
    fun setAccessibilityLostAt(c: Context, v: Long) = sp(c).edit().putLong("accessibility_lost_at", v).apply()

    fun notificationAsked(c: Context): Boolean = sp(c).getBoolean("notification_asked", false)
    fun setNotificationAsked(c: Context, v: Boolean) = sp(c).edit().putBoolean("notification_asked", v).apply()

    fun buglyConsent(c: Context): Boolean = sp(c).getBoolean("bugly_consent", false)
    fun setBuglyConsent(c: Context, v: Boolean) = sp(c).edit().putBoolean("bugly_consent", v).apply()
    fun buglyConsentAsked(c: Context): Boolean = sp(c).getBoolean("bugly_consent_asked", false)
    fun setBuglyConsentAsked(c: Context, v: Boolean) = sp(c).edit().putBoolean("bugly_consent_asked", v).apply()
}

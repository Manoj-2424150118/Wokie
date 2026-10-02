package com.wokie.message.security

import android.content.Context
import android.content.SharedPreferences

class PrivacyManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("wokie_privacy_prefs", Context.MODE_PRIVATE)

    var hideNotificationContent: Boolean
        get() = prefs.getBoolean("hide_notifications", false)
        set(value) = prefs.edit().putBoolean("hide_notifications", value).apply()

    var blockScreenshots: Boolean
        get() = prefs.getBoolean("block_screenshots", false)
        set(value) = prefs.edit().putBoolean("block_screenshots", value).apply()

    var clipboardProtection: Boolean
        get() = prefs.getBoolean("clipboard_protection", true)
        set(value) = prefs.edit().putBoolean("clipboard_protection", value).apply()
}

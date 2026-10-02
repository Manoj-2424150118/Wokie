package com.wokie.message

import android.content.SharedPreferences
import android.content.SharedPreferences.Editor
import android.content.SharedPreferences.OnSharedPreferenceChangeListener

class FakeSharedPreferences : SharedPreferences {
    val map = mutableMapOf<String, Any>()

    override fun getAll(): Map<String, *> = map

    override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = map[key] as? Set<String> ?: defValues

    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = map.containsKey(key)

    override fun edit(): Editor = FakeEditor(this)

    override fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {}
}

class FakeEditor(private val prefs: FakeSharedPreferences) : Editor {
    private val changes = mutableMapOf<String, Any?>()
    private var clear = false

    override fun putString(key: String, value: String?): Editor { changes[key] = value; return this }
    override fun putStringSet(key: String, values: Set<String>?): Editor { changes[key] = values; return this }
    override fun putInt(key: String, value: Int): Editor { changes[key] = value; return this }
    override fun putLong(key: String, value: Long): Editor { changes[key] = value; return this }
    override fun putFloat(key: String, value: Float): Editor { changes[key] = value; return this }
    override fun putBoolean(key: String, value: Boolean): Editor { changes[key] = value; return this }
    override fun remove(key: String): Editor { changes[key] = null; return this }
    override fun clear(): Editor { clear = true; return this }
    
    override fun commit(): Boolean {
        apply()
        return true
    }
    
    override fun apply() {
        if (clear) prefs.map.clear()
        for ((k, v) in changes) {
            if (v == null) prefs.map.remove(k) else prefs.map[k] = v
        }
    }
}

package app.kavach.android.core.data.session

import android.content.SharedPreferences

/** Fail-closed fallback: usable for this process only. No session bytes reach disk. */
internal class MemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    @Synchronized override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    @Synchronized override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    @Synchronized override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues?.toMutableSet()
    @Synchronized override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
    @Synchronized override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
    @Synchronized override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
    @Synchronized override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
    @Synchronized override fun contains(key: String?) = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false
        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        private fun put(key: String?, value: Any?): SharedPreferences.Editor { if (key != null) changes[key] = value; return this }
        override fun remove(key: String?) = put(key, null)
        override fun clear(): SharedPreferences.Editor { clear = true; return this }
        override fun commit(): Boolean {
            synchronized(this@MemoryPreferences) {
                if (clear) values.clear()
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            }
            return true
        }
        override fun apply() { commit() }
    }
}

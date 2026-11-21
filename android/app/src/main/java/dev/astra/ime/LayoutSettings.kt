package dev.astra.ime

import android.content.Context

/**
 * Which layout the English and number screens use: the 12-key flick layout or the full-width rows (QWERTY / the
 * 123 rows). Only these two booleans are stored, in private preferences; never any typed text.
 */
internal object LayoutSettings {
    private const val PREFS = "astra_layout"
    private const val ENGLISH = "english_12key"
    private const val NUMERIC = "numeric_12key"

    fun english12Key(context: Context): Boolean = read(context, ENGLISH)

    fun setEnglish12Key(context: Context, twelveKey: Boolean) = write(context, ENGLISH, twelveKey)

    fun numeric12Key(context: Context): Boolean = read(context, NUMERIC)

    fun setNumeric12Key(context: Context, twelveKey: Boolean) = write(context, NUMERIC, twelveKey)

    /**
     * Default before any choice is stored: a fresh install starts with 12 keys; an update from a version that only
     * had the full-width rows keeps them, so an existing QWERTY habit is not switched silently.
     */
    fun defaultTwelveKey(firstInstallTime: Long, lastUpdateTime: Long): Boolean = lastUpdateTime <= firstInstallTime

    private fun read(context: Context, key: String): Boolean {
        val prefs = prefs(context)
        if (prefs.contains(key)) return prefs.getBoolean(key, true)
        val value = try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            defaultTwelveKey(info.firstInstallTime, info.lastUpdateTime)
        } catch (e: Exception) {
            true
        }
        // Stored once, so a later update does not change a layout the user has been using.
        write(context, key, value)
        return value
    }

    private fun write(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

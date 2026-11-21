package dev.astra.ime

import android.content.Context
import android.content.SharedPreferences

/**
 * User switches, stored as plain booleans in private preferences; no input text is ever stored here.
 * The dictionary, zenz and correction are on in debug and release builds alike (1.0.0 removed the `BuildConfig.DEBUG`
 * gate); each still needs its bundled assets, and zenz a CPU that can run it ([ZenzCpu]).
 */
internal object DebugSettings {
    private const val PREFS = "astra_debug"
    /** Tests only: the full dictionary off, so the small built-in lexicon converts. Users have no such switch. */
    private const val OSS_OFF = "oss_off_for_tests"
    private const val LIVE_CONVERSION = "live_conversion"
    private const val ZENZ = "zenz_judge"
    private const val COMMITTED_CONTEXT = "committed_context"
    private const val CORRECTION = "flick_correction"
    private const val DICTIONARY_FAILED = "dictionary_failed"

    /**
     * Always on since 0.1.23: the setup screen no longer offers a switch, and the old `oss_converter` choice is ignored
     * so nobody is left on the small lexicon by a setting they can no longer see.
     */
    fun ossEnabled(context: Context): Boolean = !prefs(context).getBoolean(OSS_OFF, false)

    /** Tests only (see [OSS_OFF]). */
    fun setOssEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(OSS_OFF, !enabled).apply()
    }

    /** zenz judge over the whole composition (needs the bundled model and arm64 library, and a CPU for it). */
    fun zenzEnabled(context: Context): Boolean = prefs(context).getBoolean(ZENZ, true)

    fun setZenzEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(ZENZ, enabled).apply()
    }

    /** The zenz judge may use the committed text before a composition, in memory ([CommittedContext]). Not learning. */
    fun committedContext(context: Context): Boolean = prefs(context).getBoolean(COMMITTED_CONTEXT, true)

    fun setCommittedContext(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(COMMITTED_CONTEXT, enabled).apply()
    }

    /** Flick-slip correction on the OSS dictionary path. */
    fun correctionEnabled(context: Context): Boolean = prefs(context).getBoolean(CORRECTION, true)

    fun setCorrectionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(CORRECTION, enabled).apply()
    }

    /** Live conversion: the composition shows the best candidate; it is committed only on explicit action. */
    fun liveConversion(context: Context): Boolean = prefs(context).getBoolean(LIVE_CONVERSION, true)

    fun setLiveConversion(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(LIVE_CONVERSION, enabled).apply()
    }

    /**
     * Outcome of the last dictionary load, kept for the setup screen: the keyboard shows no status. Only this one
     * boolean is stored. States other than a finished load change nothing.
     */
    fun recordDictionaryLoad(context: Context, state: ConversionWorker.OssState) {
        val failed = when (state) {
            ConversionWorker.OssState.READY -> false
            ConversionWorker.OssState.MISSING, ConversionWorker.OssState.FAILED -> true
            else -> return
        }
        prefs(context).edit().putBoolean(DICTIONARY_FAILED, failed).apply()
    }

    fun dictionaryFailed(context: Context): Boolean = prefs(context).getBoolean(DICTIONARY_FAILED, false)

    /** The caller keeps [listener] strongly: preferences hold only a weak reference. */
    fun listen(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).registerOnSharedPreferenceChangeListener(listener)

    fun unlisten(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

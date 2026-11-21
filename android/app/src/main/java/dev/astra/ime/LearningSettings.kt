package dev.astra.ime

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/**
 * Process-wide [LearningStore] shared by the IME and the setup screen (same process), plus its on/off switch.
 * Files live in no-backup app storage (not included in cloud backups).
 */
internal object Learning {
    private const val PREFS = "astra_learning"
    private const val ENABLED = "enabled"
    private const val DIR = "learning"

    private var store: LearningStore? = null // main thread only
    private var storeDir: File? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "astra-learning-io").apply { isDaemon = true } }

    /** Loads once per storage directory on first use (small files). Main thread only. */
    fun store(context: Context): LearningStore {
        val dir = File(context.applicationContext.noBackupFilesDir, DIR)
        store?.let { if (dir == storeDir) return it }
        return LearningStore(dir, io).also {
            it.load()
            store = it
            storeDir = dir
        }
    }

    /** Tests only: returns once every learning file write queued so far has finished. */
    internal fun awaitWritesForTests() {
        io.submit {}.get()
    }

    /** On by default: the user asked for learning; it can be turned off and erased in the setup screen. */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

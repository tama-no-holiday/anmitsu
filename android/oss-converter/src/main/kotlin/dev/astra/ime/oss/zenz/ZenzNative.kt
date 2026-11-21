package dev.astra.ime.oss.zenz

import java.io.Closeable

/**
 * zenz (Apache-2.0 GGUF) candidate scorer through llama.cpp, via the `astra_zenz` JNI library built from
 * android/zenz-native. Strings cross as UTF-8 bytes. Calls are serialized on the instance, so [close] waits
 * for a running score and later calls return null instead of touching freed native memory.
 */
class ZenzNative private constructor(private var handle: Long) : Closeable {

    /**
     * log P(candidate + end | [leftContext], reading) in nats per candidate, or null when closed or llama.cpp
     * failed. zenz-v3 prompt: optional "" + left context, then "" + katakana reading + "".
     */
    @Synchronized
    fun score(katakanaReading: String, candidates: List<String>, leftContext: String = ""): FloatArray? {
        if (handle == 0L) return null
        val context = if (leftContext.isEmpty()) "" else "$leftContext"
        val prompt = "$context$katakanaReading"
        return score(handle, prompt.toByteArray(Charsets.UTF_8), utf8(candidates), true)
    }

    /**
     * Fluency of each text read as zenz's left context: log P(text + U+EE00 | U+EE02). Independent of any
     * reading, so it can compare candidates converted from different (corrected) readings.
     */
    @Synchronized
    fun contextScore(texts: List<String>): FloatArray? {
        if (handle == 0L) return null
        return score(handle, "".toByteArray(Charsets.UTF_8), utf8(texts.map { "$it" }), false)
    }

    /** Tokens for [text] (no special tokens); equals the code point count when tokenization is per character. */
    @Synchronized
    fun tokenCount(text: String): Int = if (handle == 0L) -1 else tokenCount(handle, text.toByteArray(Charsets.UTF_8))

    @Synchronized
    override fun close() {
        if (handle != 0L) close(handle)
        handle = 0L
    }

    private fun utf8(list: List<String>) = list.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray()

    companion object {
        /** [load] loads the native library (System.loadLibrary on Android, System.load with a path on a host). */
        fun open(modelPath: String, threads: Int, load: () -> Unit = { System.loadLibrary("astra_zenz") }): ZenzNative? {
            load()
            val h = open(modelPath.toByteArray(Charsets.UTF_8), threads)
            return if (h == 0L) null else ZenzNative(h)
        }

        @JvmStatic private external fun open(path: ByteArray, threads: Int): Long
        @JvmStatic private external fun close(handle: Long)
        @JvmStatic private external fun tokenCount(handle: Long, text: ByteArray): Int
        @JvmStatic private external fun score(handle: Long, prompt: ByteArray, candidates: Array<ByteArray>, appendEnd: Boolean): FloatArray?
    }
}

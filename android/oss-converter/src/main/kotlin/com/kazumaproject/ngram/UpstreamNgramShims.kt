package com.kazumaproject.ngram

// Compile-only stand-ins for two symbols that the pinned SystemNgramDictionary.kt references but that
// live in upstream files deliberately left out of the snapshot (packed n-gram assets are not used).
// KanaKanjiEngine/FindPath are only ever given the Empty* dictionaries here, so the Packed* readers
// that would reach these are never constructed. Any attempt fails closed instead of misreading data.

internal class SystemNgramBinaryReader(@Suppress("unused") private val bytes: ByteArray) {
    fun verify() {
        throw UnsupportedOperationException("Packed n-gram assets are not part of this OSS integration")
    }
}

internal object NgramV3 {
    fun writeUVarint(@Suppress("unused") output: java.io.OutputStream, @Suppress("unused") value: Int) {
        throw UnsupportedOperationException("Packed n-gram assets are not part of this OSS integration")
    }
}

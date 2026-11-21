package dev.astra.ime.oss

import com.kazumaproject.Louds.LOUDS
import com.kazumaproject.Louds.with_term_id.LOUDSWithTermId
import com.kazumaproject.dictionary.TokenArray
import com.kazumaproject.mozc.ConnectionMatrix
import com.kazumaproject.mozc.ConnectionMatrixIO
import java.io.InputStream
import java.io.InvalidClassException
import java.io.ObjectInputStream
import java.io.ObjectStreamClass
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.BitSet

/**
 * Compiled upstream resources: reading LOUDS with term IDs, surface LOUDS, token array, POS table and
 * the raw connection matrix. File layouts are exactly what upstream KanaKanjiEngine reads.
 *
 * Loading mirrors upstream's `readExternalNotCompress` field order but, unlike upstream, fails on any
 * error instead of printing and continuing, closes every stream, and only resolves the primitive-array
 * and BitSet classes these files contain. Loaded labels, token columns and POS IDs are read-only [LoadedList] views
 * over the deserialized primitive arrays instead of boxed copies. Java serialization is still only appropriate for
 * trusted, locally built assets.
 */
class OssResources internal constructor(
    internal val yomi: LOUDSWithTermId,
    internal val tango: LOUDS,
    internal val tokens: TokenArray,
    internal val matrix: ConnectionMatrix,
) {
    companion object {
        const val FORMAT_VERSION = 1
        const val MANIFEST = "oss-manifest.properties"
        const val YOMI = "yomi.dat"
        const val TANGO = "tango.dat"
        const val TOKEN = "token.dat"
        const val POS_TABLE = "pos_table.dat"
        const val CONNECTION = "connectionId.dat"

        fun load(dir: Path): OssResources = load { name -> Files.newInputStream(dir.resolve(name)) }

        /** [open] returns a fresh stream for a resource name (e.g. Android AssetManager::open); it is always closed here. */
        fun load(open: (String) -> InputStream): OssResources {
            val manifest = open(MANIFEST).use { readManifest(it) }
            val format = manifest["format"]
            if (format != FORMAT_VERSION.toString()) throw IllegalStateException("Unsupported OSS resource format: $format")
            return loadUnchecked(open)
        }

        internal fun loadUnchecked(open: (String) -> InputStream): OssResources {
            // Field order as written by upstream writeExternalNotCompress: LBS, isLeaf, labels[, termIds].
            // Deserialized arrays are wrapped, not copied into boxed lists, and no other reference to them is kept.
            val yomi = objects(open, YOMI) {
                val lbs = readAs<BitSet>()
                val isLeaf = readAs<BitSet>()
                val labels = readAs<CharArray>()
                LOUDSWithTermId(lbs, LoadedCharList(labels), isLeaf, readAs<IntArray>())
            }
            val tango = objects(open, TANGO) {
                val lbs = readAs<BitSet>()
                val isLeaf = readAs<BitSet>()
                LOUDS(lbs, LoadedCharList(readAs<CharArray>()), isLeaf)
            }
            // Field order as written by upstream TokenArray.writeExternalNotCompress: POS indexes, costs, node IDs, postings.
            val tokens = objects(open, TOKEN) {
                val posTableIndexes = readAs<ShortArray>()
                val wordCosts = readAs<ShortArray>()
                val nodeIds = readAs<IntArray>()
                TokenArray().also {
                    it.setLoadedColumns(LoadedShortList(posTableIndexes), LoadedShortList(wordCosts), LoadedIntList(nodeIds), readAs<BitSet>())
                }
            }
            objects(open, POS_TABLE) {
                tokens.leftIds = LoadedShortList(readAs<ShortArray>())
                tokens.rightIds = LoadedShortList(readAs<ShortArray>())
            }
            val matrix = open(CONNECTION).use { ConnectionMatrixIO.read(it, CONNECTION) }

            check(yomi.termIdsSave.isNotEmpty()) { "$YOMI has no readings" }
            check(tokens.leftIds.isNotEmpty() && tokens.leftIds.size == tokens.rightIds.size) { "$POS_TABLE is inconsistent" }
            // Every dictionary node's IDs must be addressable, so ConnectionMatrix.getCost cannot throw mid-search.
            check((tokens.leftIds + tokens.rightIds).all { it.toInt() in 0 until matrix.size }) {
                "$POS_TABLE has IDs outside the ${matrix.size}x${matrix.size} connection matrix"
            }
            return OssResources(yomi, tango, tokens, matrix)
        }

        internal fun readManifest(input: InputStream): Map<String, String> =
            input.bufferedReader(StandardCharsets.UTF_8).readLines()
                .filter { it.isNotBlank() && !it.startsWith('#') }
                .associate { line ->
                    val eq = line.indexOf('=')
                    check(eq > 0) { "Malformed $MANIFEST line" }
                    line.substring(0, eq) to line.substring(eq + 1)
                }

        /** Reads [name] through the class allowlist; also used by [MappedLexicon.pack]. */
        internal fun <T> objects(open: (String) -> InputStream, name: String, block: ObjectInputStream.() -> T): T =
            open(name).use { raw ->
                try {
                    AllowlistObjectInputStream(raw.buffered()).use { it.block() }
                } catch (e: Exception) {
                    throw IllegalStateException("Failed to read $name: ${e.javaClass.simpleName}", e)
                }
            }
    }
}

internal inline fun <reified T> ObjectInputStream.readAs(): T =
    readObject() as? T ?: throw IllegalStateException("Unexpected object, expected ${T::class.java.simpleName}")

/** Resolves only the classes upstream's not-compressed resource files contain. Works on Android (no ObjectInputFilter). */
private class AllowlistObjectInputStream(input: InputStream) : ObjectInputStream(input) {
    override fun resolveClass(desc: ObjectStreamClass): Class<*> {
        if (desc.name !in ALLOWED) throw InvalidClassException(desc.name, "not allowed in OSS converter resources")
        return super.resolveClass(desc)
    }

    companion object {
        private val ALLOWED = setOf("java.util.BitSet", "[J", "[C", "[I", "[S")
    }
}

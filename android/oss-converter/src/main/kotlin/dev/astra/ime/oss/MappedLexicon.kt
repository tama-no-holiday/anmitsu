package dev.astra.ime.oss

import com.kazumaproject.hiraToKata
import com.kazumaproject.mozc.ConnectionMatrix
import com.kazumaproject.mozc.ConnectionMatrixIO
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.BitSet

/**
 * Read-only bit vector over a mapped section, answering exactly what upstream `SuccinctBitVector` answers for the
 * `java.util.BitSet` it was built from (including `size` = `BitSet.size()`, a multiple of 64) and `BitSet.get`.
 * The rank directory (ones before each [BLOCK_BITS]-bit block) is precomputed in the file, so nothing is built at
 * load and nothing is copied to the heap.
 *
 * Section layout (little endian): size, ones, wordCount, blockCount (4 longs), words (longs), block ranks (ints).
 */
internal class MappedBits(section: ByteBuffer) {
    val size: Int
    val ones: Int
    private val words: LongBuffer
    private val blocks: IntBuffer

    init {
        val b = section.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val sizeL = b.getLong(0)
        val onesL = b.getLong(8)
        val wordCount = b.getLong(16)
        val blockCount = b.getLong(24)
        check(sizeL in 0..Int.MAX_VALUE && sizeL % 64 == 0L && wordCount == sizeL / 64) { "Bad bit vector size" }
        check(blockCount == (wordCount + WORDS_PER_BLOCK - 1) / WORDS_PER_BLOCK) { "Bad bit vector directory" }
        check(onesL in 0..sizeL) { "Bad bit vector count" }
        check(b.capacity().toLong() == HEADER + wordCount * 8 + blockCount * 4) { "Bad bit vector length" }
        size = sizeL.toInt()
        ones = onesL.toInt()
        words = slice(b, HEADER, (wordCount * 8).toInt()).asLongBuffer()
        blocks = slice(b, HEADER + (wordCount * 8).toInt(), (blockCount * 4).toInt()).asIntBuffer()
    }

    /** `BitSet.get`: false at and beyond [size], IndexOutOfBoundsException for a negative index. */
    operator fun get(index: Int): Boolean {
        if (index < 0) throw IndexOutOfBoundsException("bitIndex < 0: $index")
        if (index >= size) return false
        return (words.get(index ushr 6) ushr (index and 63)) and 1L != 0L
    }

    /** Ones in [0, index]; 0 below 0 and [ones] at or beyond [size]. */
    fun rank1(index: Int): Int {
        if (index < 0) return 0
        if (index >= size) return ones
        val word = index ushr 6
        var rank = blocks.get(word / WORDS_PER_BLOCK)
        for (w in (word / WORDS_PER_BLOCK) * WORDS_PER_BLOCK until word) rank += java.lang.Long.bitCount(words.get(w))
        val bit = index and 63
        val mask = if (bit == 63) -1L else (1L shl (bit + 1)) - 1
        return rank + java.lang.Long.bitCount(words.get(word) and mask)
    }

    fun rank0(index: Int): Int {
        if (index < 0) return 0
        if (index >= size) return size - ones
        return index + 1 - rank1(index)
    }

    /** Position of the [k]-th one (1-based), or -1. */
    fun select1(k: Int): Int {
        if (k < 1 || k > ones || size == 0) return -1
        var lo = 0
        var hi = blocks.limit() - 1
        var block = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (blocks.get(mid) < k) { block = mid; lo = mid + 1 } else hi = mid - 1
        }
        var left = k - blocks.get(block)
        for (w in block * WORDS_PER_BLOCK until words.limit()) {
            val x = words.get(w)
            val c = java.lang.Long.bitCount(x)
            if (left <= c) return w * 64 + nthSetBit(x, left)
            left -= c
        }
        return -1
    }

    /** Position of the [k]-th zero below [size] (1-based), or -1. */
    fun select0(k: Int): Int {
        if (k < 1 || k > size - ones || size == 0) return -1
        var lo = 0
        var hi = blocks.limit() - 1
        var block = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (mid * BLOCK_BITS - blocks.get(mid) < k) { block = mid; lo = mid + 1 } else hi = mid - 1
        }
        var left = k - (block * BLOCK_BITS - blocks.get(block))
        for (w in block * WORDS_PER_BLOCK until words.limit()) {
            val x = words.get(w).inv()
            val c = java.lang.Long.bitCount(x)
            if (left <= c) return w * 64 + nthSetBit(x, left)
            left -= c
        }
        return -1
    }

    companion object {
        const val HEADER = 32
        const val WORDS_PER_BLOCK = 8
        const val BLOCK_BITS = WORDS_PER_BLOCK * 64

        private fun nthSetBit(word: Long, n: Int): Int {
            var x = word
            repeat(n - 1) { x = x and (x - 1) }
            return java.lang.Long.numberOfTrailingZeros(x)
        }

        /** Encodes [bits] with `size` = `bits.size()`, as upstream SuccinctBitVector sees it. */
        fun encode(bits: BitSet): ByteBuffer {
            val size = bits.size()
            val wordCount = size / 64
            val raw = bits.toLongArray()
            val blockCount = (wordCount + WORDS_PER_BLOCK - 1) / WORDS_PER_BLOCK
            val out = ByteBuffer.allocate(HEADER + wordCount * 8 + blockCount * 4).order(ByteOrder.LITTLE_ENDIAN)
            out.putLong(size.toLong()).putLong(bits.cardinality().toLong()).putLong(wordCount.toLong()).putLong(blockCount.toLong())
            for (w in 0 until wordCount) out.putLong(if (w < raw.size) raw[w] else 0L)
            var ones = 0
            for (w in 0 until wordCount) {
                if (w % WORDS_PER_BLOCK == 0) out.putInt(HEADER + wordCount * 8 + (w / WORDS_PER_BLOCK) * 4, ones)
                if (w < raw.size) ones += java.lang.Long.bitCount(raw[w])
            }
            out.position(0)
            return out
        }
    }
}

/**
 * [Lexicon] over a memory-mapped `lexicon.bin`: the upstream LOUDS/TokenArray lookups ported line by line
 * (commonPrefixSearch, getNodeIndex/search, getTermId, getLetter, getListDictionaryByYomiTermId) over the same arrays
 * upstream deserializes, read in place. Results are identical to [UpstreamLexicon] for the files it was packed from.
 */
internal class MappedLexicon private constructor(s: List<ByteBuffer>) : Lexicon {
    private val yomiLbs = MappedBits(s[Section.YOMI_LBS.ordinal])
    private val yomiLeaf = MappedBits(s[Section.YOMI_LEAF.ordinal])
    private val yomiLabels: CharBuffer = s[Section.YOMI_LABELS.ordinal].asCharBuffer()
    private val termIds: IntBuffer = s[Section.YOMI_TERM_IDS.ordinal].asIntBuffer()
    private val tangoLbs = MappedBits(s[Section.TANGO_LBS.ordinal])
    private val tangoLabels: CharBuffer = s[Section.TANGO_LABELS.ordinal].asCharBuffer()
    private val posIndexes: ShortBuffer = s[Section.TOKEN_POS.ordinal].asShortBuffer()
    private val costs: ShortBuffer = s[Section.TOKEN_COST.ordinal].asShortBuffer()
    private val nodeIds: IntBuffer = s[Section.TOKEN_NODE.ordinal].asIntBuffer()
    private val postings = MappedBits(s[Section.POSTINGS.ordinal])
    private val leftIds: ShortBuffer = s[Section.LEFT_IDS.ordinal].asShortBuffer()
    private val rightIds: ShortBuffer = s[Section.RIGHT_IDS.ordinal].asShortBuffer()

    val entryCount: Int get() = costs.limit()
    val posCount: Int get() = leftIds.limit()

    init {
        check(termIds.limit() > 0) { "lexicon has no readings" }
        check(posIndexes.limit() == costs.limit() && nodeIds.limit() == costs.limit()) { "Token array column lengths differ" }
        check(leftIds.limit() > 0 && leftIds.limit() == rightIds.limit()) { "POS table is inconsistent" }
    }

    // --- LOUDSWithTermId (readings) ---

    override fun prefixes(text: String): List<String> {
        val resultTemp = StringBuilder()
        val result = ArrayList<String>()
        var n = 0
        for (c in text) {
            n = traverse(n, c)
            val index = yomiLbs.rank1(n)
            if (n == -1) continue
            if (index >= yomiLabels.limit()) return result
            resultTemp.append(yomiLabels.get(index))
            if (yomiLeaf[n]) {
                val tempStr = resultTemp.toString()
                if (result.size >= 1) {
                    result.add(result[0] + tempStr)
                } else {
                    result.add(tempStr)
                    resultTemp.setLength(0)
                }
            }
        }
        return result
    }

    private fun firstChild(pos: Int): Int {
        val y = yomiLbs.select0(yomiLbs.rank1(pos)) + 1
        return if (y < 0 || !yomiLbs[y]) -1 else y
    }

    private fun traverse(pos: Int, c: Char): Int {
        var childPos = firstChild(pos)
        if (childPos == -1) return -1
        while (yomiLbs[childPos]) {
            if (c == yomiLabels.get(yomiLbs.rank1(childPos))) return childPos
            childPos += 1
        }
        return -1
    }

    override fun termId(reading: String): Int = termIdAt(if (reading.isEmpty()) -1 else search(2, reading, 0))

    private fun search(index: Int, chars: String, wordOffset: Int): Int {
        var index2 = index
        var charIndex = yomiLbs.rank1(index2)
        while (yomiLbs[index2]) {
            if (chars[wordOffset] == yomiLabels.get(charIndex)) {
                if (wordOffset + 1 == chars.length) return if (yomiLeaf[index2]) index2 else -1
                return search(yomiLbs.select0(charIndex) + 1, chars, wordOffset + 1)
            } else {
                index2++
            }
            charIndex++
        }
        return -1
    }

    private fun termIdAt(nodeIndex: Int): Int {
        if (!(nodeIndex in 0 until yomiLbs.size && yomiLeaf[nodeIndex])) return -1
        val first = yomiLeaf.rank1(nodeIndex) - 1
        if (first !in 0 until termIds.limit()) return -1
        return termIds.get(first)
    }

    /** A dictionary word found by [predictions]: its full reading, surface and word cost. */
    class Prediction(val reading: String, val surface: String, val cost: Int)

    /**
     * Words whose reading starts with [prefix] and is longer (completions), cheapest word cost first, at most [limit]
     * distinct surfaces. The reading trie below [prefix] is walked breadth first over at most [maxNodes] nodes, so a
     * short prefix costs a bounded amount of work (and may miss long, rare readings).
     */
    override fun predictions(prefix: String, limit: Int, maxNodes: Int): List<Prediction> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        var n = 0
        for (c in prefix) {
            n = traverse(n, c)
            if (n == -1) return emptyList()
        }
        val best = HashMap<String, Prediction>()
        val queue = ArrayDeque<Pair<Int, String>>()
        queue.addLast(n to prefix)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val (pos, reading) = queue.removeFirst()
            var child = firstChild(pos)
            while (child != -1 && yomiLbs[child] && visited < maxNodes) {
                visited++
                val r = reading + yomiLabels.get(yomiLbs.rank1(child))
                if (yomiLeaf[child]) {
                    val termId = termIdAt(child)
                    if (termId >= 1) forEachEntry(termId) { _, cost, nodeId ->
                        val surface = when (nodeId) {
                            -2 -> r
                            -1 -> r.hiraToKata()
                            else -> surface(nodeId)
                        }
                        val old = best[surface]
                        if (old == null || cost < old.cost) best[surface] = Prediction(r, surface, cost)
                    }
                }
                queue.addLast(child to r)
                child++
            }
        }
        return best.values.sortedWith(compareBy({ it.cost }, { it.reading.length })).take(limit)
    }

    /** Every reading in the trie with its term ID, breadth first. A whole-dictionary walk: tools and audits only. */
    fun forEachReading(action: (reading: String, termId: Int) -> Unit) {
        val queue = ArrayDeque<Pair<Int, String>>()
        queue.addLast(0 to "")
        while (queue.isNotEmpty()) {
            val (pos, reading) = queue.removeFirst()
            var child = firstChild(pos)
            while (child != -1 && yomiLbs[child]) {
                val r = reading + yomiLabels.get(yomiLbs.rank1(child))
                if (yomiLeaf[child]) termIdAt(child).let { if (it >= 1) action(r, it) }
                queue.addLast(child to r)
                child++
            }
        }
    }

    // --- TokenArray ---

    override fun forEachEntry(termId: Int, action: (posIndex: Int, cost: Int, nodeId: Int) -> Unit) {
        val b = postings.rank1(postings.select0(termId))
        val endSeparator = postings.select0(termId + 1)
        val c = if (endSeparator < 0) entryCount else postings.rank1(endSeparator)
        for (i in b until c) action(posIndexes.get(i).toInt(), costs.get(i).toInt(), nodeIds.get(i))
    }

    override fun leftId(posIndex: Int): Short = leftIds.get(posIndex)
    override fun rightId(posIndex: Int): Short = rightIds.get(posIndex)

    internal fun leftIds(): ShortArray = ShortArray(posCount) { leftIds.get(it) }
    internal fun rightIds(): ShortArray = ShortArray(posCount) { rightIds.get(it) }

    // --- LOUDS (surfaces) ---

    override fun surface(nodeId: Int): String {
        val list = StringBuilder()
        list.append(tangoLabels.get(tangoLbs.rank1(nodeId)))
        var parentNodeIndex = tangoLbs.select1(tangoLbs.rank0(nodeId))
        while (parentNodeIndex != 0) {
            val parentNodeId = tangoLbs.rank1(parentNodeIndex)
            list.append(tangoLabels.get(parentNodeId))
            parentNodeIndex = tangoLbs.select1(tangoLbs.rank0(parentNodeIndex))
            if (parentNodeId == 0) return ""
        }
        // Char by char like upstream's reversed list; StringBuilder.reverse would treat surrogate pairs as units.
        val chars = CharArray(list.length) { list[list.length - 1 - it] }
        return String(chars)
    }

    /** Sections in file order. */
    internal enum class Section {
        YOMI_LBS, YOMI_LEAF, YOMI_LABELS, YOMI_TERM_IDS, TANGO_LBS, TANGO_LABELS,
        TOKEN_POS, TOKEN_COST, TOKEN_NODE, POSTINGS, LEFT_IDS, RIGHT_IDS,
    }

    companion object {
        const val FILE = "lexicon.bin"
        private val MAGIC = "ASTRALEX".toByteArray(StandardCharsets.US_ASCII)
        private const val VERSION = 1
        private val HEADER = MAGIC.size + 8 + Section.entries.size * 16

        /**
         * Parses [buffer] (typically a read-only mapping). Section bounds, alignment and lengths are checked; the
         * contents are trusted as built and hash-checked by the caller.
         */
        fun open(buffer: ByteBuffer): MappedLexicon {
            val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            check(b.capacity() >= HEADER) { "$FILE is truncated" }
            val magic = ByteArray(MAGIC.size).also { b.position(0); b.get(it) }
            check(magic.contentEquals(MAGIC)) { "$FILE has a bad magic" }
            check(b.getInt(MAGIC.size) == VERSION) { "Unsupported $FILE version" }
            check(b.getInt(MAGIC.size + 4) == Section.entries.size) { "Unexpected $FILE section count" }
            val sections = Section.entries.map { section ->
                val at = MAGIC.size + 8 + section.ordinal * 16
                val offset = b.getLong(at)
                val length = b.getLong(at + 8)
                check(offset >= HEADER && offset % 8 == 0L && length >= 0 && offset + length <= b.capacity()) { "Bad $FILE section $section" }
                val width = when (section) {
                    Section.YOMI_LABELS, Section.TANGO_LABELS, Section.TOKEN_POS, Section.TOKEN_COST, Section.LEFT_IDS, Section.RIGHT_IDS -> 2
                    Section.YOMI_TERM_IDS, Section.TOKEN_NODE -> 4
                    else -> 1
                }
                check(length % width == 0L) { "Bad $FILE section $section length" }
                slice(b, offset.toInt(), length.toInt())
            }
            return MappedLexicon(sections)
        }

        /** Maps [file] read-only; the mapping stays valid after the channel is closed. */
        fun map(file: Path): ByteBuffer =
            FileChannel.open(file, StandardOpenOption.READ).use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }

        /**
         * Writes [FILE] into [dir] from the upstream files there (yomi.dat, tango.dat, token.dat, pos_table.dat),
         * copying their arrays unchanged, then checks it against [UpstreamLexicon] for every term ID and node ID.
         */
        fun pack(dir: Path): Path {
            val sections = arrayOfNulls<ByteBuffer>(Section.entries.size)
            fun put(s: Section, b: ByteBuffer) { sections[s.ordinal] = b }
            val input = { name: String -> Files.newInputStream(dir.resolve(name)) as InputStream }
            OssResources.objects(input, OssResources.YOMI) {
                put(Section.YOMI_LBS, MappedBits.encode(readAs<BitSet>()))
                put(Section.YOMI_LEAF, MappedBits.encode(readAs<BitSet>()))
                put(Section.YOMI_LABELS, chars(readAs<CharArray>()))
                put(Section.YOMI_TERM_IDS, ints(readAs<IntArray>()))
            }
            OssResources.objects(input, OssResources.TANGO) {
                put(Section.TANGO_LBS, MappedBits.encode(readAs<BitSet>()))
                readAs<BitSet>() // isLeaf: getLetter does not read it
                put(Section.TANGO_LABELS, chars(readAs<CharArray>()))
            }
            OssResources.objects(input, OssResources.TOKEN) {
                put(Section.TOKEN_POS, shorts(readAs<ShortArray>()))
                put(Section.TOKEN_COST, shorts(readAs<ShortArray>()))
                put(Section.TOKEN_NODE, ints(readAs<IntArray>()))
                put(Section.POSTINGS, MappedBits.encode(readAs<BitSet>()))
            }
            OssResources.objects(input, OssResources.POS_TABLE) {
                put(Section.LEFT_IDS, shorts(readAs<ShortArray>()))
                put(Section.RIGHT_IDS, shorts(readAs<ShortArray>()))
            }
            val out = dir.resolve(FILE)
            val tmp = dir.resolve("$FILE.tmp")
            FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { ch ->
                val header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
                header.put(MAGIC).putInt(VERSION).putInt(Section.entries.size)
                var offset = HEADER.toLong()
                val padded = sections.map { s ->
                    offset = (offset + 7) / 8 * 8
                    header.putLong(offset).putLong(s!!.capacity().toLong())
                    (offset to s).also { offset += s.capacity() }
                }
                header.flip()
                writeFully(ch, header, 0)
                for ((at, s) in padded) writeFully(ch, s.duplicate().also { it.position(0) }, at)
            }
            // Audited before it is published under its final name. Read onto the heap, not mapped: Windows cannot
            // move or delete a file while a mapping of it is alive.
            try {
                audit(OssResources.loadUnchecked(input), open(ByteBuffer.wrap(Files.readAllBytes(tmp))))
            } catch (e: Throwable) {
                Files.deleteIfExists(tmp)
                throw e
            }
            Files.move(tmp, out)
            return out
        }

        /** Every term ID's entries, every surface node and the POS table must match the upstream lookups. */
        internal fun audit(upstream: OssResources, mapped: MappedLexicon) {
            val reference = UpstreamLexicon(upstream)
            check(upstream.tokens.leftIds == mapped.leftIds().toList() && upstream.tokens.rightIds == mapped.rightIds().toList()) { "POS table mismatch" }
            check(upstream.tokens.entryCount == mapped.entryCount) { "Entry count mismatch" }
            val maxTermId = upstream.yomi.termIdsSave.max()
            var entries = 0
            for (termId in 1..maxTermId) {
                val a = ArrayList<Long>()
                val b = ArrayList<Long>()
                reference.forEachEntry(termId) { p, c, n -> a += entryKey(p, c, n) }
                mapped.forEachEntry(termId) { p, c, n -> b += entryKey(p, c, n) }
                check(a == b) { "Token entries differ for term $termId" }
                entries += a.size
                mapped.forEachEntry(termId) { _, _, n -> if (n >= 0) check(reference.surface(n) == mapped.surface(n)) { "Surface differs for node $n" } }
            }
            check(entries == mapped.entryCount) { "Entries not covered by term IDs" }
        }

        private fun entryKey(p: Int, c: Int, n: Int): Long = (p.toLong() shl 48) xor ((c.toLong() and 0xFFFF) shl 32) xor (n.toLong() and 0xFFFFFFFFL)

        private fun writeFully(ch: FileChannel, b: ByteBuffer, at: Long) {
            var pos = at
            while (b.hasRemaining()) pos += ch.write(b, pos)
        }

        private fun chars(a: CharArray) = ByteBuffer.allocate(a.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { it.asCharBuffer().put(a) }
        private fun shorts(a: ShortArray) = ByteBuffer.allocate(a.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { it.asShortBuffer().put(a) }
        private fun ints(a: IntArray) = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { it.asIntBuffer().put(a) }
    }
}

/** A little-endian slice [offset, offset + length) of [b]. */
private fun slice(b: ByteBuffer, offset: Int, length: Int): ByteBuffer {
    val d = b.duplicate()
    d.limit(offset + length).position(offset)
    return d.slice().order(ByteOrder.LITTLE_ENDIAN)
}

/**
 * What [OssConverter] reads from a mapped build: [MappedLexicon] plus the connection matrix (still on the heap,
 * `connectionId.dat` as upstream reads it). Every POS ID must address the matrix.
 */
class MappedDictionary internal constructor(internal val lexicon: MappedLexicon, internal val matrix: ConnectionMatrix) {
    init {
        val ids = lexicon.leftIds() + lexicon.rightIds()
        check(ids.all { it.toInt() in 0 until matrix.size }) { "POS table has IDs outside the ${matrix.size}x${matrix.size} connection matrix" }
    }

    companion object {
        const val FILE = MappedLexicon.FILE

        /**
         * [lexicon] is the mapped `lexicon.bin`; [connection] and [manifest] are fresh streams of `connectionId.dat` and
         * `oss-manifest.properties`, closed here. The manifest must name the supported resource format.
         */
        fun open(lexicon: ByteBuffer, connection: InputStream, manifest: InputStream): MappedDictionary {
            val format = manifest.use { OssResources.readManifest(it) }["format"]
            if (format != OssResources.FORMAT_VERSION.toString()) {
                connection.close()
                throw IllegalStateException("Unsupported OSS resource format: $format")
            }
            return open(lexicon, connection)
        }

        internal fun open(lexicon: ByteBuffer, connection: InputStream): MappedDictionary =
            MappedDictionary(MappedLexicon.open(lexicon), connection.use { ConnectionMatrixIO.read(it, OssResources.CONNECTION) })

        fun open(dir: Path): MappedDictionary = open(
            MappedLexicon.map(dir.resolve(FILE)),
            Files.newInputStream(dir.resolve(OssResources.CONNECTION)),
            Files.newInputStream(dir.resolve(OssResources.MANIFEST)),
        )

        /** Writes and audits [FILE] in a built resource directory (`ossCli pack`). */
        fun pack(dir: Path): Path = MappedLexicon.pack(dir)
    }
}

package dev.astra.ime.oss

import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import dev.astra.ime.oss.zenz.ZenzRanking
import kotlin.random.Random
import kotlin.system.exitProcess

private const val USAGE = """Usage (no network access; all paths explicit):
  build --mozc-dir <dictionary_oss dir> --out <empty dir> [--extra <mozc-format file>]... [--no-suffix] [--no-verify]
  english --sudachi <SudachiDict raw lex csv>... --mozc-dir <dictionary_oss dir> --out <file>
        Writes Latin spellings of SudachiDict nouns (Google, Claude, ...) as an extra Mozc-format source.
  english-index --source <mozc-format file>... --out <file>
        Case-insensitive index of single-word ASCII spellings (openai -> OpenAI) for English typing (EnglishIndex).
  ut-prepare --mozc-dir <dictionary_oss dir> --id-def-sha256 <pinned sha256> --source <fetched UT dir>...
        [--prior-extra <mozc-format file>]... (--out <new or empty dir> | --check-only)
        [--cost-floor N] [--relative-penalty N] [--max-reject-percent N]
        Resolves Mozc-UT placeholder ids by POS name, drops pairs Mozc/prior extras already have and writes
        ut-extra.txt for build --extra, with provenance, notices and a summary (docs/dictionary-update.md).
  compare --old <resources dir> --new <resources dir> --fixtures <tsv>... [--limit N] [--max-regressions N] [--new-mapped]
        Top-1/top-N per fixture and category on both builds; exits 3 if more fixtures regress than allowed.
        --new-mapped loads the new side from its lexicon.bin (see pack).
  pack  --resources <dir>
        Writes lexicon.bin (memory-mappable copy of yomi/tango/token/pos_table) into a built directory and checks it
        against the upstream lookups for every term and surface node.
  supplement-prepare --data-dir <dir with Mozc rewriter data> --out <file>
        Symbols, emoticons, emoji and single kanji keyed by reading (third_party/mozc/supplement-lock.json) as the
        app's supplementary candidate table (assets/supplement/mozc-supplement.tsv).
  predict-eval --resources <dir> --fixtures <tsv>... [--top N] [--min-prefix N] [--max-nodes N]
        Keystroke estimate for dictionary predictions (needs lexicon.bin): each word of the expected conversion is
        typed kana by kana; once it is among the top N completions of the typed prefix, one tap finishes it.
  demo  --resources <dir> [--mapped] [--fixtures <feedback.tsv>]... [--text <kana>]... [--limit N]
  bench --resources <dir> [--mapped] [--fixtures <feedback.tsv>]... [--synthetic N] [--synthetic-max-len N]
        [--seed N] [--warmup N] [--iterations N] [--limit N]
  typo-eval --resources <dir> --fixtures <tsv>... [--scale F]... [--limit N]
        [--emit-zenz <tsv>] [--zenz-scores <tsv>] [--zenz-weight F] [--rank-weight F]
        Converts the typed column with flick-slip correction (typo restoration) and compares with expected.
        --emit-zenz writes key/reading/surface rows for an external zenz scorer; --zenz-scores reranks with them.
  zenz-score --lib <astra_zenz library> --model <zenz.gguf> --in <rows.tsv> --out <scores.tsv> [--threads N]
        Scores emitted rows through the JNI scorer (host build of android/zenz-native).
  exact-probe --resources <dir> --readings <file> --out <utf8 report> [--census] [--limit N]
        Ranked candidates against the dictionary's exact surfaces for each reading (needs lexicon.bin).
  exact-eval --resources <dir> --readings <file> --out <utf8 report> [--stride N] [--rare N] [--iterations N]
        Exact list recall against the dictionary read directly: the readings given, every reading with over 20 surfaces,
        every Nth other reading and the N rarest single-kanji entries; single-kanji coverage; host p50/p95.
  zenz-lead-eval --resources <dir> --fixtures <tsv>... --readings <file> --lib <astra_zenz library> --model <zenz.gguf>
        [--scale F] [--lead-margin N] [--threads N] [--no-guard]
        The app's correction + zenz path on fixtures and exact-reading probes, with or without the lead guard.
  context-eval --resources <dir> --cases <tsv> --lib <astra_zenz library> --model <zenz.gguf> --out <utf8 tsv>
        [--fixtures <tsv>]... [--neutral <file of lines>] [--repeats N] [--scored N] [--threads N] [--no-pairs] [--per-rank F]
        The app's ranking with and without a committed left context: recall, ranks, scores and host latency for each
        case (data/context-eval.tsv); with --fixtures, top-1 changes when an unrelated line of --neutral comes first.
  context-policy-eval --resources <dir> --cases <tsv>... --lib <astra_zenz library> --model <zenz.gguf> --out <utf8 tsv> --summary <file>
        [--policy NAME]... [--fixtures <tsv>]... [--neutral <file of lines>] [--warmup N] [--repeats N] [--scored N] [--threads N] [--cold-check]
        The ranking options of data/context-holdout/protocol.md against the shipped weights.
  context-stability --resources <dir> --lib <astra_zenz library> --model <zenz.gguf> --fixtures <tsv>... [--neutral <file of lines>] [--scored N]
        Whether candidates and scores change with what ran before: forwards, backwards, cold, and forwards again.
  context-learning-diag --resources <dir> --cases <tsv> --lib <astra_zenz library> --model <zenz.gguf> --out <utf8 tsv> [--scale F]
        Separates missing context, learned phrases and the model on synthetic rows (data/context-learning-diag.tsv: id,
        reading, surface A, surface B, context A, context B): every context against every learned state.
  prefix-lead --resources <dir> [--fixtures <tsv>]... [--text <kana>]... [--scale F] [--mapped] [--list] [--out <utf8 report>]
        Correctly typed readings entered one kana at a time, with correction as the app uses it: how many prefixes
        lead with a correction and how many of those drop the final kana. Every --text prefix is printed.
Fixtures: tab-separated id, category, typed, reading, expected surface (android/core/src/test/resources/feedback.tsv);
the expected column may list accepted alternatives separated by '|'.
The reading column is converted (correct-reading conversion). Upstream does no typo correction, so the typed
column is not used and these results say nothing about typo restoration."""

fun main(args: Array<String>) {
    // UTF-8 regardless of the platform default (CP932 on Japanese Windows), so logs stay readable.
    val out = PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8")
    val err = PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true, "UTF-8")
    exitProcess(runCli(args.toList(), out, err))
}

internal fun runCli(args: List<String>, out: PrintStream, err: PrintStream): Int {
    val command = args.firstOrNull()
    return try {
        val options = Options.parse(args.drop(1))
        when (command) {
            "build" -> build(options, out)
            "english" -> english(options, out)
            "english-index" -> englishIndex(options, out)
            "ut-prepare" -> utPrepare(options, out)
            "compare" -> compare(options, out)
            "pack" -> pack(options, out)
            "supplement-prepare" -> supplementPrepare(options, out)
            "predict-eval" -> predictEval(options, out)
            "demo" -> demo(options, out)
            "bench" -> bench(options, out)
            "typo-eval" -> typoEval(options, out)
            "zenz-score" -> zenzScore(options, out)
            "exact-probe" -> exactProbe(options, out)
            "exact-eval" -> exactEval(options, out)
            "zenz-lead-eval" -> zenzLeadEval(options, out)
            "context-eval" -> contextEval(options, out)
            "context-policy-eval" -> contextPolicyEval(options, out)
            "context-stability" -> contextStability(options, out)
            "context-learning-diag" -> contextLearningDiag(options, out)
            "ranking-policy-diag" -> rankingPolicyDiag(options, out)
            "prefix-lead" -> prefixLead(options, out)
            else -> {
                err.println(USAGE)
                2
            }
        }
    } catch (e: IllegalArgumentException) {
        err.println("error: ${e.message}")
        err.println(USAGE)
        2
    }
}

private class Options(private val values: Map<String, List<String>>, private val flags: Set<String>) {
    fun path(name: String): Path = Paths.get(single(name) ?: throw IllegalArgumentException("missing --$name"))
    fun int(name: String, default: Int): Int =
        single(name)?.let { it.toIntOrNull() ?: throw IllegalArgumentException("--$name must be an integer") } ?: default
    fun all(name: String): List<String> = values[name].orEmpty()
    fun flag(name: String): Boolean = name in flags
    private fun single(name: String): String? = values[name]?.last()

    companion object {
        private val FLAGS = setOf("no-suffix", "no-verify", "check-only", "mapped", "new-mapped", "census", "no-guard", "no-pairs", "cold-check", "list")

        fun parse(args: List<String>): Options {
            val values = LinkedHashMap<String, MutableList<String>>()
            val flags = HashSet<String>()
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                require(arg.startsWith("--")) { "unexpected argument at position ${i + 1}" }
                val name = arg.removePrefix("--")
                if (name in FLAGS) {
                    flags += name
                    i++
                } else {
                    require(i + 1 < args.size) { "missing value for $arg" }
                    values.getOrPut(name) { ArrayList() } += args[i + 1]
                    i += 2
                }
            }
            return Options(values, flags)
        }
    }
}

private fun supplementPrepare(options: Options, out: PrintStream): Int {
    val result = SupplementImport.write(options.path("data-dir"), options.path("out"))
    out.println("supplement: keys=${result.keys} " + result.counts.entries.joinToString(" ") { "${it.key}=${it.value}" })
    return 0
}

private fun english(options: Options, out: PrintStream): Int {
    val lexicons = options.all("sudachi").map { Paths.get(it) }
    require(lexicons.isNotEmpty()) { "english needs --sudachi" }
    val mozc = MozcInputs.fromDirectory(options.path("mozc-dir"), includeSuffix = !options.flag("no-suffix"))
    val stats = SudachiEnglish.extract(lexicons, mozc, options.path("out"))
    out.println(
        "sudachiRows=${stats.rowsRead} latinNounRows=${stats.candidates} written=${stats.written} readings=${stats.readings} " +
            "(costOffset=${SudachiEnglish.COST_OFFSET} maxPerReading=${SudachiEnglish.MAX_PER_READING})",
    )
    return 0
}

private fun englishIndex(options: Options, out: PrintStream): Int {
    val sources = options.all("source").map { Paths.get(it) }
    require(sources.isNotEmpty()) { "english-index needs --source" }
    val (bytes, stats) = EnglishIndex.build(sources)
    val target = options.path("out")
    target.parent?.let { Files.createDirectories(it) }
    Files.write(target, bytes)
    for (s in stats.sources) out.println("source ${s.name} sha256=${s.sha256} rows=${s.rows} keptSurfaces=${s.kept}")
    out.println("keys=${stats.keys} surfaces=${stats.surfaces} bytes=${stats.bytes} sha256=${stats.sha256}")
    return 0
}

private fun utPrepare(options: Options, out: PrintStream): Int {
    val sources = options.all("source").map { UtDictionaryImport.readSource(Paths.get(it)) }
    require(sources.isNotEmpty()) { "ut-prepare needs --source" }
    val checkOnly = options.flag("check-only")
    val target = options.all("out").lastOrNull()?.let { Paths.get(it) }
    require(checkOnly != (target != null)) { "ut-prepare needs exactly one of --out and --check-only" }
    val defaults = UtDictionaryImport.Policy()
    val policy = UtDictionaryImport.Policy(
        costFloor = options.int("cost-floor", defaults.costFloor),
        relativePenalty = options.int("relative-penalty", defaults.relativePenalty),
        maxRejectRatio = options.int("max-reject-percent", (defaults.maxRejectRatio * 100).toInt()) / 100.0,
    )
    val result = UtDictionaryImport.prepare(
        mozc = MozcInputs.fromDirectory(options.path("mozc-dir"), includeSuffix = !options.flag("no-suffix")),
        idDefSha256 = options.all("id-def-sha256").lastOrNull() ?: throw IllegalArgumentException("missing --id-def-sha256"),
        priorExtras = options.all("prior-extra").map { Paths.get(it) },
        sources = sources,
        out = target,
        policy = policy,
    )
    out.print(result.summary)
    out.println("outputSha256=${result.sha256 ?: "-"}")
    return 0
}

/**
 * Old vs new resources on the same fixtures (correct-reading conversion). A regression is a fixture whose expected
 * surface was top-1 (or within top-N) before and is not now. Also prints load time and warm per-call latency.
 */
private fun compare(options: Options, out: PrintStream): Int {
    val limit = options.int("limit", 5)
    val maxRegressions = options.int("max-regressions", 0)
    val fixtures = options.all("fixtures").flatMap { p -> readFixtures(Paths.get(p)).map { Paths.get(p).fileName.toString() to it } }
    require(fixtures.isNotEmpty()) { "compare needs --fixtures" }
    // Only numbers are kept, so the old converter can be collected before the new one loads.
    class Side(val name: String, val loadMillis: Long, val heapMb: Long, val results: List<List<String>>, val p50: Double, val p95: Double)
    fun run(name: String, dir: String, mapped: Boolean = false): Side {
        val loaded = load(Options(mapOf("resources" to listOf(dir)), if (mapped) setOf("mapped") else emptySet()))
        repeat(2) { fixtures.forEach { loaded.converter.convert(it.second.reading, limit) } }
        val nanos = LongArray(fixtures.size)
        val results = fixtures.mapIndexed { i, (_, f) ->
            val started = System.nanoTime()
            val r = loaded.converter.convert(f.reading, limit).candidates
            nanos[i] = System.nanoTime() - started
            r
        }
        nanos.sort()
        fun pct(p: Double) = nanos[(Math.ceil(p * nanos.size).toInt() - 1).coerceIn(0, nanos.lastIndex)] / 1e6
        return Side(name, loaded.loadMillis, loaded.heapMb, results, pct(0.5), pct(0.95))
    }
    val old = run("old", options.all("old").lastOrNull() ?: throw IllegalArgumentException("missing --old"))
    val new = run("new", options.all("new").lastOrNull() ?: throw IllegalArgumentException("missing --new"), options.flag("new-mapped"))
    class Tally { var n = 0; var oldTop1 = 0; var newTop1 = 0; var oldTopN = 0; var newTopN = 0 }
    val tallies = java.util.TreeMap<String, Tally>()
    var regressions = 0
    var improvements = 0
    fixtures.forEachIndexed { i, (file, f) ->
        val o = old.results[i]
        val n = new.results[i]
        val o1 = o.first() in f.expected
        val n1 = n.first() in f.expected
        val oN = o.any { it in f.expected }
        val nN = n.any { it in f.expected }
        for (key in listOf("$file:${f.category}", "$file:*")) {
            val t = tallies.getOrPut(key) { Tally() }
            t.n++
            if (o1) t.oldTop1++
            if (n1) t.newTop1++
            if (oN) t.oldTopN++
            if (nN) t.newTopN++
        }
        val change = when {
            (o1 && !n1) || (oN && !nN) -> "REGRESSED".also { regressions++ }
            (!o1 && n1) || (!oN && nN) -> "improved".also { improvements++ }
            o != n -> "changed"
            else -> null
        }
        if (change != null) out.println("${f.id}\t${f.category}\t$change\told=${o.take(3).joinToString(" | ")}\tnew=${n.take(3).joinToString(" | ")}")
    }
    for ((key, t) in tallies) {
        out.println("$key: fixtures=${t.n} top1 ${t.oldTop1} -> ${t.newTop1} top$limit ${t.oldTopN} -> ${t.newTopN}")
    }
    for (s in listOf(old, new)) {
        out.println(
            "${s.name}: loadMillis=${s.loadMillis} approxHeapMbAfterLoad=${s.heapMb} " +
                "p50Ms=${"%.3f".format(s.p50)} p95Ms=${"%.3f".format(s.p95)} (host JVM, warm, not a device measurement)",
        )
    }
    out.println("regressions=$regressions improvements=$improvements maxRegressions=$maxRegressions (correct-reading conversion only)")
    return if (regressions > maxRegressions) 3 else 0
}

private fun build(options: Options, out: PrintStream): Int {
    val inputs = MozcInputs.fromDirectory(options.path("mozc-dir"), includeSuffix = !options.flag("no-suffix"))
        .copy(extras = options.all("extra").map { Paths.get(it) })
    val started = System.nanoTime()
    val report = OssDictionaryBuilder.build(inputs, options.path("out"), verify = !options.flag("no-verify"))
    out.print(OssDictionaryBuilder.formatReport(report))
    out.println("buildMillis=${(System.nanoTime() - started) / 1_000_000}")
    out.println("peakHeapMbUpperBound=${peakHeapMb()} maxHeapMb=${Runtime.getRuntime().maxMemory() / (1024 * 1024)} (JVM, sum of per-pool peaks)")
    return 0
}

/** Sum of each heap pool's peak; pools peak at different times, so this is an upper bound. */
private fun peakHeapMb(): Long =
    java.lang.management.ManagementFactory.getMemoryPoolMXBeans()
        .filter { it.type == java.lang.management.MemoryType.HEAP }
        .sumOf { it.peakUsage.used } / (1024 * 1024)

/** [expected] holds the accepted surfaces; any of them counts as a match. */
private class Fixture(val id: String, val category: String, val typed: String, val reading: String, val expected: List<String>)

private fun readFixtures(path: Path): List<Fixture> =
    Files.readAllLines(path, StandardCharsets.UTF_8).withIndex().filter { it.value.isNotBlank() }.map { (index, line) ->
        val fields = line.split('\t')
        val expected = fields.getOrNull(4)?.split('|').orEmpty()
        require(fields.size >= 5 && fields[3].isNotEmpty() && expected.none { it.isEmpty() }) {
            "${path.fileName}:${index + 1}: expected id, category, typed, reading, surface[|surface...]"
        }
        Fixture(fields[0], fields[1], fields[2], fields[3], expected)
    }

private class Loaded(val converter: OssConverter, val loadMillis: Long, val heapMb: Long)

/** With --mapped, lexicon.bin is memory-mapped ([MappedDictionary]); its pages are not Java heap. */
private fun load(options: Options): Loaded {
    val started = System.nanoTime()
    val dir = options.path("resources")
    val limits = OssLimits(correctionLeadMargin = options.int("lead-margin", OssLimits().correctionLeadMargin))
    val converter = if (options.flag("mapped")) OssConverter(MappedDictionary.open(dir), limits) else OssConverter(OssResources.load(dir), limits)
    val loadMillis = (System.nanoTime() - started) / 1_000_000
    val runtime = Runtime.getRuntime()
    System.gc()
    val heapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    return Loaded(converter, loadMillis, heapMb)
}

/**
 * Word-by-word estimate: a word costs its kana count, or (prefix length + 1 tap) once the prefix ranks it within the
 * top N completions. Words come from the expected candidate of each fixture (fixtures without it are skipped).
 */
private fun predictEval(options: Options, out: PrintStream): Int {
    val dictionary = MappedDictionary.open(options.path("resources"))
    val converter = OssConverter(dictionary)
    val top = options.int("top", 3)
    val minPrefix = options.int("min-prefix", 2)
    val maxNodes = options.int("max-nodes", 20_000)
    val fixtures = options.all("fixtures").flatMap { readFixtures(Paths.get(it)) }
    require(fixtures.isNotEmpty()) { "predict-eval needs --fixtures" }
    var used = 0
    var words = 0
    var predicted = 0
    var baseline = 0
    var withPrediction = 0
    val nanos = ArrayList<Long>()
    for (f in fixtures) {
        val r = converter.convert(f.reading, limit = 20)
        val i = r.candidates.indexOfFirst { it in f.expected }
        if (i < 0 || r.words[i].isEmpty()) continue
        used++
        for (w in r.words[i]) {
            words++
            val n = w.reading.length
            baseline += n
            var cost = n
            for (k in minPrefix until n) {
                // Finishing with a tap must save at least one key.
                if (k + 1 >= n) break
                val started = System.nanoTime()
                val p = dictionary.lexicon.predictions(w.reading.substring(0, k), top, maxNodes)
                nanos += System.nanoTime() - started
                if (p.any { it.surface == w.surface && it.reading == w.reading }) {
                    cost = k + 1
                    predicted++
                    break
                }
            }
            withPrediction += cost
        }
    }
    nanos.sort()
    fun pct(q: Double) = if (nanos.isEmpty()) 0.0 else nanos[(Math.ceil(q * nanos.size).toInt() - 1).coerceIn(0, nanos.lastIndex)] / 1e6
    out.println("fixtures=${fixtures.size} used=$used words=$words predictedWords=$predicted top=$top minPrefix=$minPrefix")
    out.println("keys baseline=$baseline withPrediction=$withPrediction saved=${"%.1f".format(100.0 * (baseline - withPrediction) / baseline)}%")
    out.println("predictionCalls=${nanos.size} p50Ms=${"%.3f".format(pct(0.5))} p95Ms=${"%.3f".format(pct(0.95))} maxMs=${"%.3f".format(pct(1.0))} (host JVM)")
    return 0
}

private fun pack(options: Options, out: PrintStream): Int {
    val started = System.nanoTime()
    val file = MappedLexicon.pack(options.path("resources"))
    out.println("wrote ${file.fileName} bytes=${Files.size(file)} audit=ok packMillis=${(System.nanoTime() - started) / 1_000_000}")
    return 0
}

private fun demo(options: Options, out: PrintStream): Int {
    val loaded = load(options)
    val limit = options.int("limit", 5)
    out.println("loadMillis=${loaded.loadMillis} approxHeapMbAfterLoad=${loaded.heapMb} (JVM, not a device measurement)")
    for (text in options.all("text")) {
        val r = loaded.converter.convert(text, limit)
        out.println("$text -> ${r.candidates.joinToString(" | ")} [${r.status} nBest=${r.nBestUsed}]")
    }
    for (path in options.all("fixtures").map { Paths.get(it) }) {
        var top1 = 0
        var inTop = 0
        val fixtures = readFixtures(path)
        for (f in fixtures) {
            val r = loaded.converter.convert(f.reading, limit)
            val first = r.candidates.first() in f.expected
            val any = r.candidates.any { it in f.expected }
            if (first) top1++
            if (any) inTop++
            val shown = if (first) r.candidates.first() else r.candidates.joinToString(" | ")
            out.println("${f.id}\ttop1=$first\ttop$limit=$any\t${r.status}\t$shown")
        }
        out.println(
            "${path.fileName}: fixtures=${fixtures.size} top1=$top1 top$limit=$inTop " +
                "(correct-reading conversion, not typo restoration; exact match against any '|' alternative)",
        )
    }
    return 0
}

/**
 * Typo restoration on the typed column (dev data). Reports top-1/top-N per penalty scale, and separately the
 * cases whose typed text already equals the correct reading (any change there is a false correction).
 * zenz rows: `key<TAB>katakana reading<TAB>surface`, key = `<id>#<candidate index>#<A|B>` where A conditions on
 * the candidate's own (possibly corrected) reading and B on the typed reading.
 */
private fun typoEval(options: Options, out: PrintStream): Int {
    val loaded = load(options)
    val limit = options.int("limit", 10)
    val scales = options.all("scale").map { it.toDoubleOrNull() ?: throw IllegalArgumentException("--scale must be a number") }
        .ifEmpty { listOf(1.0) }
    val fixtures = options.all("fixtures").flatMap { readFixtures(Paths.get(it)) }
    require(fixtures.isNotEmpty()) { "typo-eval needs --fixtures" }
    val zenz = options.all("zenz-scores").lastOrNull()?.let { readZenzScores(Paths.get(it)) }
    val zenzWeight = options.all("zenz-weight").lastOrNull()?.toDouble() ?: 1.0
    val rankWeight = options.all("rank-weight").lastOrNull()?.toDouble() ?: 0.0
    // Weight of the A score (own reading); B (typed reading) gets 1 - this.
    val ownReadingWeight = options.all("own-reading-weight").lastOrNull()?.toDouble() ?: 0.0
    // Nats subtracted per kana edit between a candidate's reading and the typed text (channel cost).
    val correctionPenalty = options.all("correction-penalty").lastOrNull()?.toDouble() ?: 0.0
    val emit = options.all("emit-zenz").lastOrNull()?.let { Paths.get(it) }
    val emitted = StringBuilder()
    val contextRows = StringBuilder()
    val contextWeight = options.all("context-weight").lastOrNull()?.toDouble() ?: 0.0
    for (scale in scales) {
        val penalties = EditPenalties().scaled(scale)
        var top1 = 0
        var inTop = 0
        var cleanCases = 0
        var cleanChanged = 0
        var typoCases = 0
        var typoTop1 = 0
        var rerankTop1 = 0
        var rerankTypo = 0
        var rerankClean = 0
        var cleanTop1 = 0
        for (f in fixtures) {
            val r = loaded.converter.convert(f.typed, limit, correction = penalties)
            var ranked = r.candidates
            if (emit != null) {
                r.candidates.forEachIndexed { i, s ->
                    emitted.append("${f.id}#$i#A\t${ZenzRanking.katakana(r.readings[i])}\t$s\n")
                    emitted.append("${f.id}#$i#B\t${ZenzRanking.katakana(f.typed)}\t$s\n")
                }
                // Context-slot fluency rows share one prompt, so they are grouped after the per-case rows.
                r.candidates.forEachIndexed { i, s -> contextRows.append("${f.id}#$i#C\t$CONTEXT_ROW\t$s\n") }
            }
            if (zenz != null) {
                val scored = r.candidates.indices.mapNotNull { i ->
                    val a = zenz["${f.id}#$i#A"] ?: return@mapNotNull null
                    val b = zenz["${f.id}#$i#B"] ?: return@mapNotNull null
                    val c = if (contextWeight != 0.0) zenz["${f.id}#$i#C"] ?: return@mapNotNull null else 0.0
                    val lp = ownReadingWeight * a + (1 - ownReadingWeight) * b + contextWeight * c
                    val edits = ZenzRanking.editDistance(r.readings[i], f.typed)
                    i to (zenzWeight * lp - rankWeight * i - correctionPenalty * edits)
                }
                if (scored.size == r.candidates.size) {
                    ranked = scored.sortedByDescending { it.second }.map { r.candidates[it.first] }
                }
                if (ranked.first() in f.expected) {
                    rerankTop1++
                    if (f.typed == f.reading) rerankClean++ else rerankTypo++
                }
            }
            val first = r.candidates.first() in f.expected
            if (first) top1++
            if (r.candidates.any { it in f.expected }) inTop++
            if (f.typed == f.reading) {
                cleanCases++
                if (first) cleanTop1++
                if (r.readings.first() != f.typed) cleanChanged++
            } else {
                typoCases++
                if (first) typoTop1++
            }
            out.println("${f.id}\t${f.category}\ttop1=$first\t${r.candidates.take(3).joinToString(" | ")}" +
                if (zenz != null) "\tzenz=${ranked.first()}" else "")
        }
        out.println(
            "scale=$scale cases=${fixtures.size} top1=$top1 top$limit=$inTop typoCases=$typoCases typoTop1=$typoTop1 " +
                "cleanCases=$cleanCases cleanTop1=$cleanTop1 cleanReadingChanged=$cleanChanged" +
                if (zenz != null) {
                    " zenzRerankTop1=$rerankTop1 zenzTypoTop1=$rerankTypo zenzCleanTop1=$rerankClean " +
                        "(zenzWeight=$zenzWeight rankWeight=$rankWeight ownReadingWeight=$ownReadingWeight " +
                        "correctionPenalty=$correctionPenalty)"
                } else {
                    ""
                },
        )
    }
    emit?.let { Files.write(it, (emitted.toString() + contextRows).toByteArray(StandardCharsets.UTF_8)) }
    return 0
}

/** Reading column marker for rows scored as zenz left context (see ZenzNative.contextScore). */
private const val CONTEXT_ROW = "<CTX>"

private fun zenzScore(options: Options, out: PrintStream): Int {
    val lib = options.path("lib").toAbsolutePath().toString()
    val started = System.nanoTime()
    val scorer = dev.astra.ime.oss.zenz.ZenzNative.open(options.path("model").toAbsolutePath().toString(), options.int("threads", 4)) {
        System.load(lib)
    } ?: throw IllegalArgumentException("zenz model could not be loaded")
    val loadMs = (System.nanoTime() - started) / 1_000_000
    // Sanity check of the pre-tokenizer override: one token per character for kana, kanji and the prompt marks.
    for (probe in listOf("変換してほしい", "ヘンカンシテホシイ", "")) {
        out.println("tokenCheck chars=${probe.codePointCount(0, probe.length)} tokens=${scorer.tokenCount(probe)}")
    }
    val rows = Files.readAllLines(options.path("in"), StandardCharsets.UTF_8).filter { it.isNotBlank() }.map { it.split('\t') }
    val result = StringBuilder()
    val groupMs = ArrayList<Double>()
    scorer.use { s ->
        var i = 0
        while (i < rows.size) {
            var j = i
            while (j < rows.size && rows[j][1] == rows[i][1]) j++
            val t0 = System.nanoTime()
            val texts = rows.subList(i, j).map { it[2] }
            val scores = if (rows[i][1] == CONTEXT_ROW) s.contextScore(texts) else s.score(rows[i][1], texts)
            groupMs += (System.nanoTime() - t0) / 1e6
            for (k in i until j) result.append(rows[k][0]).append('\t').append(scores?.get(k - i) ?: -1e9f).append('\n')
            i = j
        }
    }
    Files.write(options.path("out"), result.toString().toByteArray(StandardCharsets.UTF_8))
    groupMs.sort()
    fun pct(p: Double) = "%.2f".format(groupMs[((p * groupMs.size).toInt()).coerceIn(0, groupMs.lastIndex)])
    out.println("zenz loadMs=$loadMs rows=${rows.size} groups=${groupMs.size} groupP50Ms=${pct(0.5)} groupP95Ms=${pct(0.95)} groupMaxMs=${pct(1.0)} (host JVM+JNI)")
    return 0
}



/** All distinct surfaces stored for exactly [reading], read directly from the lexicon. */
private fun dictionarySurfaces(lexicon: Lexicon, reading: String): Set<String> {
    val out = LinkedHashSet<String>()
    val termId = lexicon.termId(reading)
    if (termId >= 1) lexicon.forEachEntry(termId) { _, _, n ->
        out += when (n) { -2 -> reading; -1 -> LiteralKatakana.of(reading); else -> lexicon.surface(n) }
    }
    return out
}

private object LiteralKatakana {
    fun of(s: String) = ZenzRanking.katakana(s)
}

/**
 * What the converter returns for each reading of [--readings] against the same dictionary read directly, with
 * correction off and at both app penalty scales. Reads the real assets; prints only dictionary words.
 */
private fun exactProbe(options: Options, console: PrintStream): Int {
    val target = options.path("out")
    target.parent?.let { Files.createDirectories(it) }
    val dictionary = MappedDictionary.open(options.path("resources"))
    val lexicon = dictionary.lexicon
    val converter = OssConverter(dictionary)
    val limit = options.int("limit", 20)
    val readings = Files.readAllLines(options.path("readings"), StandardCharsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    val configs = listOf("off" to null, "scale1" to EditPenalties(), "scale2" to EditPenalties().scaled(2.0))
    PrintStream(Files.newOutputStream(target), true, "UTF-8").use { out ->
        for (reading in readings) {
            val exact = dictionarySurfaces(lexicon, reading)
            out.println("reading=$reading dictionaryExactSurfaces=${exact.size}")
            for ((name, correction) in configs) {
                val r = converter.convert(reading, limit, correction)
                val offered = r.candidates.toSet()
                val hit = exact.count { it in offered }
                out.println("  $name returned=${r.candidates.size} exactReturned=$hit/${exact.size} ${r.status}")
                out.println("  $name list=${r.candidates.zip(r.readings).joinToString(" ") { (s, k) -> if (k == reading) s else "$s<$k>" }}")
            }
            out.println("  absentAtScale1=${(exact - converter.convert(reading, limit, EditPenalties()).candidates.toSet()).take(40).joinToString(" ")}")
        }
        if (options.flag("census")) exactDistribution(lexicon, out)
    }
    console.println("exact-probe report: ${target.fileName}")
    return 0
}

/** Homophone census of the whole dictionary: distinct surfaces per reading. */
private fun exactDistribution(lexicon: MappedLexicon, out: PrintStream) {
    val sizes = java.util.TreeMap<Int, Int>()
    val top = java.util.PriorityQueue<Pair<Int, String>>(compareBy { it.first })
    var readings = 0
    var surfaces = 0L
    lexicon.forEachReading { reading, _ ->
        val n = dictionarySurfaces(lexicon, reading).size
        readings++
        surfaces += n
        sizes.merge(n, 1, Int::plus)
        top += n to reading
        if (top.size > 40) top.poll()
    }
    fun count(min: Int) = sizes.tailMap(min, true).values.sum()
    out.println("census readings=$readings surfaces=$surfaces max=${sizes.lastKey()} over20=${count(21)} over24=${count(25)} over100=${count(101)} over500=${count(501)}")
    top.toList().sortedByDescending { it.first }.forEach { (n, r) -> out.println("census $n\t$r") }
}

/**
 * Correctly typed readings entered kana by kana (every prefix of the fixtures' clean rows and of --text). A prefix
 * "drops its last kana" when the leading candidate's reading lacks the typed final kana, as しかいをつとめま -> しかいをつとめ.
 */
private fun prefixLead(options: Options, console: PrintStream): Int {
    val loaded = load(options)
    val target = options.all("out").lastOrNull()?.let { Paths.get(it) }
    target?.parent?.let { Files.createDirectories(it) }
    val out = target?.let { PrintStream(Files.newOutputStream(it), true, "UTF-8") } ?: console
    val penalties = EditPenalties().scaled((options.all("scale").lastOrNull() ?: "1").toDouble())
    val shown = options.all("text")
    val readings = (shown + options.all("fixtures").flatMap { readFixtures(Paths.get(it)) }.filter { it.typed == it.reading }.map { it.reading }).distinct()
    var prefixes = 0
    var leads = 0
    var dropped = 0
    for (text in readings) {
        for (end in 1..text.length) {
            val prefix = text.substring(0, end)
            val r = loaded.converter.convert(prefix, 20, penalties)
            if (r.candidates.isEmpty()) continue
            prefixes++
            val drop = r.correctionLeads && r.readings.first() == prefix.dropLast(1)
            if (r.correctionLeads) leads++
            if (drop) dropped++
            if (text in shown || (drop && options.flag("list"))) {
                out.println("$prefix\tleads=${r.correctionLeads}\tdrop=$drop\t${r.candidates.first()} (${r.readings.first()})")
            }
        }
    }
    val summary = "prefix-lead readings=${readings.size} prefixes=$prefixes correctionLeads=$leads lastKanaDropped=$dropped"
    out.println(summary)
    if (target != null) {
        out.close()
        console.println(summary)
    }
    return 0
}

/** First candidate of the zenz path, as the app computes it, for typed text. Numbers only are printed. */
private fun zenzLeadEval(options: Options, out: PrintStream): Int {
    val guard = !options.flag("no-guard")
    val dir = options.path("resources")
    val limits = OssLimits(correctionLeadMargin = if (guard) options.int("lead-margin", OssLimits().correctionLeadMargin) else 0)
    val converter = OssConverter(MappedDictionary.open(dir), limits)
    val penalties = EditPenalties().scaled((options.all("scale").lastOrNull() ?: "1").toDouble())
    val lib = options.path("lib").toAbsolutePath().toString()
    val fixtures = options.all("fixtures").flatMap { readFixtures(Paths.get(it)) }
    val probes = Files.readAllLines(options.path("readings"), StandardCharsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    val scorer = dev.astra.ime.oss.zenz.ZenzNative.open(options.path("model").toAbsolutePath().toString(), options.int("threads", 4)) {
        System.load(lib)
    } ?: throw IllegalArgumentException("zenz model could not be loaded")
    scorer.use { s ->
        fun lead(text: String): Pair<String, String> {
            val r = converter.convert(text, 20, penalties)
            val head = ZenzRanking.Head(r.headLength, r.headSurface, r.headReading)
            val order = ZenzRanking.rerank(text, r.candidates, r.readings, 8, head = head) { context, reading, list -> s.score(reading, list, context) }
                ?: r.candidates.indices.toList()
            val kept = if (guard) ZenzRanking.keepExactLead(order, r.readings, text, r.correctionLeads) else order
            return r.candidates[kept[0]] to r.readings[kept[0]]
        }
        var typo = 0
        var typoTop1 = 0
        var clean = 0
        var cleanTop1 = 0
        var cleanChanged = 0
        for (f in fixtures) {
            val (surface, reading) = lead(f.typed)
            val hit = surface in f.expected
            if (f.typed == f.reading) {
                clean++
                if (hit) cleanTop1++
                if (reading != f.typed) cleanChanged++
            } else {
                typo++
                if (hit) typoTop1++
            }
        }
        val probeExact = probes.count { lead(it).second == it }
        out.println(
            "guard=$guard margin=${limits.correctionLeadMargin} scale=${penalties.modifier / 1500.0} typoCases=$typo typoTop1=$typoTop1 " +
                "cleanCases=$clean cleanTop1=$cleanTop1 cleanReadingChanged=$cleanChanged probes=${probes.size} probeLeadExact=$probeExact",
        )
    }
    return 0
}

/**
 * The app's ranking (zenz over the first candidates, then the exact lead guard) of one reading after a left context.
 * [scores] receives each scored candidate's raw log probability; empty when nothing was scored.
 */
private fun rankedWithContext(
    converter: OssConverter,
    scorer: ZenzRanking.Scorer,
    text: String,
    context: String,
    scored: Int,
    correction: EditPenalties?,
    scores: MutableMap<String, Float>,
    weights: ZenzRanking.Weights = ZenzRanking.Weights(),
): Pair<OssConversion, List<Int>> {
    val r = converter.convert(text, 20, correction)
    val head = ZenzRanking.Head(r.headLength, r.headSurface, r.headReading)
    val external = ZenzRanking.promptContext(context).isNotEmpty()
    val order = ZenzRanking.rerank(text, r.candidates, r.readings, scored, weights, head = head, leftContext = context, external = external) { c, reading, list ->
        scorer.score(c, reading, list)?.also { values -> list.forEachIndexed { i, s -> scores[r.headSurface + s] = values[i] } }
    } ?: r.candidates.indices.toList()
    return r to ZenzRanking.keepExactLead(order, r.readings, text, r.correctionLeads)
}

private class ContextCase(val id: String, val group: String, val reading: String, val expected: List<String>, val prefix: String)

/** The options of data/context-holdout/protocol.md. Evaluation only; the app ranks with the default weights. */
private val CONTEXT_POLICIES = linkedMapOf(
    "baseline" to ZenzRanking.Weights(),
    "prior0.5" to ZenzRanking.Weights(contextPerRank = 0.5),
    "margin1" to ZenzRanking.Weights(contextMargin = 1.0),
    "margin2" to ZenzRanking.Weights(contextMargin = 2.0),
)

/**
 * Whether candidates and scores depend on what ran before: the same inputs forwards, backwards and with the prompt cache
 * moved off first. A comparison of ranking options means little if they do.
 */
private fun contextStability(options: Options, console: PrintStream): Int {
    val fixtures = options.all("fixtures").flatMap { readFixtures(Paths.get(it)) }
    val neutrals = listOf("") + options.all("neutral").flatMap { Files.readAllLines(Paths.get(it), StandardCharsets.UTF_8) }.filter { it.isNotBlank() }
    val converter = OssConverter(MappedDictionary.open(options.path("resources")))
    val lib = options.path("lib").toAbsolutePath().toString()
    val native = dev.astra.ime.oss.zenz.ZenzNative.open(options.path("model").toAbsolutePath().toString(), options.int("threads", 4)) {
        System.load(lib)
    } ?: throw IllegalArgumentException("zenz model could not be loaded")
    val inputs = neutrals.flatMap { n -> fixtures.map { f -> n to f.reading } }.distinct()
    native.use { s ->
        val raw = ZenzRanking.Scorer { context, reading, list -> s.score(reading, list, context) }
        fun pass(items: List<Pair<String, String>>, cold: Boolean) = items.associateWith { (context, text) ->
            if (cold) s.score("", listOf("x"))
            val scores = LinkedHashMap<String, Float>()
            val (r, order) = rankedWithContext(converter, raw, text, context, options.int("scored", 8), null, scores)
            Triple(r.candidates, order, scores)
        }
        val forward = pass(inputs, false)
        for ((name, other) in listOf("reversed" to pass(inputs.reversed(), false), "cold" to pass(inputs, true), "again" to pass(inputs, false))) {
            var lists = 0
            var orders = 0
            var worst = 0.0
            var changed = 0
            val shown = ArrayList<String>()
            for (key in inputs) {
                val a = forward.getValue(key)
                val b = other.getValue(key)
                if (a.first != b.first) lists++
                if (a.second != b.second) {
                    orders++
                    if (shown.size < 8) shown += "${key.second}@${key.first.take(6)}"
                }
                for ((surface, v) in a.third) {
                    val w = b.third[surface] ?: continue
                    val d = Math.abs((v - w).toDouble())
                    if (d > 1e-3) changed++
                    worst = maxOf(worst, d)
                }
            }
            console.println("stability $name inputs=${inputs.size} listsDiffer=$lists ordersDiffer=$orders scoresOver0.001=$changed maxScoreDelta=%.4f shown=$shown".format(worst))
        }
    }
    return 0
}

/**
 * Which of context, learning and the model decides a first candidate, on the real dictionary and model. The learned
 * states mirror the app's LearningStore (latest word bonus 1500, phrase first for its reading). Inputs are synthetic.
 */
private fun contextLearningDiag(options: Options, console: PrintStream): Int {
    val weights = ZenzRanking.Weights(contextMargin = 2.0, phraseGap = 3.0, phraseShift = 1.5) // as OssBackend.RANKING
    val scored = options.int("scored", 8)
    val correction = EditPenalties().scaled((options.all("scale").lastOrNull() ?: "1").toDouble())
    val neutral = "今日はいい天気です。"
    val converter = OssConverter(MappedDictionary.open(options.path("resources")))
    val lib = options.path("lib").toAbsolutePath().toString()
    val native = dev.astra.ime.oss.zenz.ZenzNative.open(options.path("model").toAbsolutePath().toString(), options.int("threads", 4)) {
        System.load(lib)
    } ?: throw IllegalArgumentException("zenz model could not be loaded")
    val target = options.path("out")
    target.parent?.let { Files.createDirectories(it) }
    native.use { s ->
        val memo = HashMap<List<Any>, FloatArray?>()
        val judge = ZenzRanking.Scorer { c, reading, list -> memo.getOrPut(listOf(c, reading, list)) { s.score(reading, list, c) } }
        PrintStream(Files.newOutputStream(target), true, "UTF-8").use { out ->
            out.println("id\tcontext\tlearned\tdictA\tdictB\tscoreA\tscoreB\tbaseFirst\tpinFirst\tcontextualFirst\tlist")
            for (line in Files.readAllLines(options.path("cases"), StandardCharsets.UTF_8).filter { it.isNotBlank() }) {
                val f = line.split('\t')
                val (id, text, a, b) = f
                val plain = converter.convert(text, 20, correction)
                fun userFor(surface: String): UserWords {
                    val chosen = plain.candidates.indexOf(surface)
                    val words = if (chosen >= 0) plain.words[chosen].filter { it.surface != it.reading && it !in plain.words[0] } else emptyList()
                    val bonuses = words.groupBy { it.reading }.mapValues { (_, list) -> list.associate { it.surface to 1500 } }
                    return UserWords(surface.hashCode().toLong(), bonuses, emptyMap())
                }
                for ((contextName, context) in listOf("none" to "", "A" to f[4], "B" to f[5], "neutral" to neutral)) {
                    for ((learnedName, phrase) in listOf("none" to null, "A" to a, "B" to b)) {
                        val user = phrase?.let(::userFor)
                        val r = converter.convert(text, 20, correction, user)
                        val scores = HashMap<String, Float>()
                        val head = ZenzRanking.Head(r.headLength, r.headSurface, r.headReading)
                        val recording = ZenzRanking.Scorer { c, reading, list ->
                            judge.score(c, reading, list)?.also { v -> list.forEachIndexed { i, x -> scores[r.headSurface + x] = v[i] } }
                        }
                        val learned = { i: Int -> user != null && r.words[i].any { user.hasBonus(it) } }
                        val order = ZenzRanking.rerank(
                            text, r.candidates, r.readings, scored, weights, head, learned = learned, leftContext = context,
                            external = context.isNotEmpty(), scorer = recording,
                        ) ?: r.candidates.indices.toList()
                        val kept = ZenzRanking.keepExactLead(order, r.readings, text, r.correctionLeads).map { r.candidates[it] }
                        val pinned = phrase ?: kept[0]
                        // As LearningSnapshot.arrange: the phrase out of the list, the exact-reading leader of what remains.
                        val leader = r.candidates.indices.firstOrNull { i -> kept[i] != phrase }?.let { kept[it] }
                            ?.takeIf { r.readings[r.candidates.indexOf(it)] == text }
                        val outweighed = phrase != null && leader != null && context.isNotEmpty() &&
                            ZenzRanking.contextOutweighs(text, phrase, leader, context, "", weights, judge)
                        val contextual = if (outweighed) leader!! else pinned
                        fun rank(x: String) = r.candidates.indexOf(x).let { if (it < 0) "-" else it.toString() }
                        fun score(x: String) = scores[x]?.let { "%.2f".format(it) } ?: "-"
                        out.println(
                            "$id\t$contextName\t$learnedName\t${rank(a)}\t${rank(b)}\t${score(a)}\t${score(b)}\t${kept[0]}\t$pinned\t$contextual\t${kept.take(4).joinToString(" ")}",
                        )
                    }
                }
            }
        }
    }
    console.println("context-learning-diag report: ${target.fileName}")
    return 0
}

/**
 * 0.1.31 policy on the real dictionary and model: for each case (id, reading, A, B, context for A, context for B) and
 * each learned state, the first candidate under the 0.1.30 rules (`margin2`, every phrase first) and under the 0.1.31
 * rules (context-first ranking, [LearnedOrder]). The learned states mirror what `LearningStore` keeps after the action:
 * implicit (B accepted 5 times: phrase only), explicit (B chosen: phrase and its non-default words, bonus 1500), legacy
 * (B from old history: the same, without provenance). Supplement and literal choices are not included. Inputs are synthetic.
 */
private fun rankingPolicyDiag(options: Options, console: PrintStream): Int {
    val old = ZenzRanking.Weights(contextMargin = 2.0, phraseGap = 3.0, phraseShift = 1.5)
    val new = old.copy(contextFirst = true) // as OssBackend.RANKING
    val scored = options.int("scored", 8)
    val correction = EditPenalties()
    val neutral = "今日はいい天気です。"
    val converter = OssConverter(MappedDictionary.open(options.path("resources")))
    val lib = options.path("lib").toAbsolutePath().toString()
    val native = dev.astra.ime.oss.zenz.ZenzNative.open(options.path("model").toAbsolutePath().toString(), options.int("threads", 4)) {
        System.load(lib)
    } ?: throw IllegalArgumentException("zenz model could not be loaded")
    val target = options.path("out")
    target.parent?.let { Files.createDirectories(it) }
    val millis = ArrayList<Double>()
    var exactTotal = 0
    var exactUnscored = 0
    var targetsUnscored = 0
    var targetsMissing = 0
    native.use { s ->
        val judge = ZenzRanking.Scorer { c, reading, list -> s.score(reading, list, c) }
        PrintStream(Files.newOutputStream(target), true, "UTF-8").use { out ->
            out.println("id\tcontext\tlearned\trankA\trankB\tscoreA\tscoreB\toldFirst\tnewFirst\tnewTop4")
            for (line in Files.readAllLines(options.path("cases"), StandardCharsets.UTF_8).filter { it.isNotBlank() }) {
                val f = line.split('\t')
                val (id, text, a, b) = f
                val plain = converter.convert(text, 20, correction)
                val exact = plain.readings.count { it == text }
                exactTotal += exact
                exactUnscored += plain.readings.drop(scored).count { it == text }
                for (x in listOf(a, b)) plain.candidates.indexOf(x).let { if (it < 0) targetsMissing++ else if (it >= scored) targetsUnscored++ }
                val chosen = plain.candidates.indexOf(b)
                val bWords = if (chosen >= 0) plain.words[chosen].filter { it.surface != it.reading && it !in plain.words[0] } else emptyList()
                val bonus = UserWords(1, bWords.groupBy { it.reading }.mapValues { (_, l) -> l.associate { it.surface to 1500 } }, emptyMap())
                val states = listOf(
                    Triple("none", null as LearnedOrder.Phrase?, null as UserWords?),
                    Triple("implicitB", LearnedOrder.Phrase(text, b, LearnedOrder.Strength.IMPLICIT), null),
                    Triple("explicitB", LearnedOrder.Phrase(text, b, LearnedOrder.Strength.EXPLICIT), bonus),
                    Triple("legacyB", LearnedOrder.Phrase(text, b, LearnedOrder.Strength.LEGACY), bonus),
                )
                for ((contextName, context) in listOf("none" to "", "A" to f[4], "B" to f[5], "neutral" to neutral)) {
                    for ((learnedName, phrase, user) in states) {
                        val r = converter.convert(text, 20, correction, user)
                        val scores = HashMap<String, Float>()
                        val head = ZenzRanking.Head(r.headLength, r.headSurface, r.headReading)
                        val recording = ZenzRanking.Scorer { c, reading, list ->
                            judge.score(c, reading, list)?.also { v -> list.forEachIndexed { i, x -> scores[r.headSurface + x] = v[i] } }
                        }
                        val learned = { i: Int -> user != null && r.words[i].any { user.hasBonus(it) } }
                        val strong = { i: Int -> phrase?.strength == LearnedOrder.Strength.EXPLICIT && learned(i) }
                        fun ranked(w: ZenzRanking.Weights, timed: Boolean): List<String> {
                            val started = System.nanoTime()
                            val order = ZenzRanking.rerank(
                                text, r.candidates, r.readings, scored, w, head, learned = learned, leftContext = context,
                                external = context.isNotEmpty(), strongLearned = strong, scorer = recording,
                            )
                            if (timed) millis += (System.nanoTime() - started) / 1e6
                            val list = ZenzRanking.keepExactLead(order ?: r.candidates.indices.toList(), r.readings, text, r.correctionLeads)
                                .map { r.candidates[it] }.toMutableList()
                            if (phrase == null) return list
                            val contextRanks = order != null && ZenzRanking.contextRanks(w, context.isNotEmpty(), context)
                            // As LearningSnapshot.arrange; 0.1.30 put every phrase first (with the same yield rule).
                            val p = if (w.contextFirst) phrase else LearnedOrder.Phrase(text, b, LearnedOrder.Strength.EXPLICIT)
                            val listed = list.indexOf(b)
                            val leader = list.filterIndexed { i, _ -> i != listed }.firstOrNull()?.takeIf { r.readings[r.candidates.indexOf(it)] == text }
                            val place = LearnedOrder.place(p, emptySet(), contextRanks, leader != null) {
                                context.isNotEmpty() && ZenzRanking.contextOutweighs(text, b, leader!!, context, "", w, judge)
                            }
                            if (place == LearnedOrder.KEEP) { if (listed < 0) list.add(minOf(1, list.size), b) } else {
                                if (listed >= 0) list.removeAt(listed)
                                list.add(minOf(place, list.size), b)
                            }
                            return list
                        }
                        val before = ranked(old, timed = false)
                        val after = ranked(new, timed = true)
                        fun rank(x: String) = r.candidates.indexOf(x).let { if (it < 0) "-" else it.toString() }
                        fun score(x: String) = scores[x]?.let { "%.2f".format(it) } ?: "-"
                        out.println("$id\t$contextName\t$learnedName\t${rank(a)}\t${rank(b)}\t${score(a)}\t${score(b)}\t${before[0]}\t${after[0]}\t${after.take(4).joinToString(" ")}")
                    }
                }
            }
        }
    }
    millis.sort()
    fun pct(p: Double) = if (millis.isEmpty()) 0.0 else millis[minOf(millis.lastIndex, (p * millis.size).toInt())]
    console.println(
        "ranking-policy-diag ${target.fileName}: reranks=${millis.size} p50=%.1fms p95=%.1fms exactCandidates=$exactTotal exactBeyond$scored=$exactUnscored targetsBeyond$scored=$targetsUnscored targetsMissing=$targetsMissing"
            .format(pct(0.5), pct(0.95)),
    )
    return 0
}

/** A row of data/context-holdout (id, family, kind, reading, surfaces, prefix) or of data/context-eval.tsv (no family). */
private class PolicyCase(val id: String, val family: String, val kind: String, val reading: String, val expected: List<String>, val prefix: String)

private fun readPolicyCases(path: Path): List<PolicyCase> =
    Files.readAllLines(path, StandardCharsets.UTF_8).filter { it.isNotBlank() }.map { line ->
        val f = line.split('\t')
        when (f.size) {
            6 -> PolicyCase(f[0], f[1], f[2], f[3], f[4].split('|'), f[5])
            5 -> PolicyCase(f[0], f[0], f[1], f[2], f[3].split('|'), f[4])
            else -> throw IllegalArgumentException("expected 5 or 6 fields: ${f.firstOrNull()}")
        }
    }

/**
 * Options against the shipped weights, per data/context-holdout/protocol.md. All options see the same memoized scores,
 * so a difference is the option's. Candidate recall is apart from ranking. Inputs are synthetic.
 */
private fun contextPolicyEval(options: Options, console: PrintStream): Int {
    val target = options.path("out")
    target.parent?.let { Files.createDirectories(it) }
    val names = options.all("policy").ifEmpty { CONTEXT_POLICIES.keys.toList() }
    require(names.first() == "baseline" && names.all { it in CONTEXT_POLICIES }) { "--policy: baseline first, then ${CONTEXT_POLICIES.keys}" }
    val policies = names.map { it to CONTEXT_POLICIES.getValue(it) }
    val converter = OssConverter(MappedDictionary.open(options.path("resources")))
    val scoredCount = options.int("scored", 8)
    val repeats = options.int("repeats", 3)
    val warmup = options.int("warmup", 1)
    val cases = options.all("cases").flatMap { readPolicyCases(Paths.get(it)) }
    val fixtures = options.all("fixtures").flatMap { readFixtures(Paths.get(it)) }
    val neutrals = options.all("neutral").flatMap { Files.readAllLines(Paths.get(it), StandardCharsets.UTF_8) }.filter { it.isNotBlank() }
    val lib = options.path("lib").toAbsolutePath().toString()
    val native = dev.astra.ime.oss.zenz.ZenzNative.open(options.path("model").toAbsolutePath().toString(), options.int("threads", 4)) {
        System.load(lib)
    } ?: throw IllegalArgumentException("zenz model could not be loaded")
    native.use { s ->
        val raw = ZenzRanking.Scorer { context, reading, list -> s.score(reading, list, context) }
        val memo = HashMap<List<Any>, FloatArray?>()
        val judge = ZenzRanking.Scorer { context, reading, list -> memo.getOrPut(listOf(context, reading, list)) { raw.score(context, reading, list) } }
        var identical = 0
        var compared = 0
        fun order(text: String, context: String, correction: EditPenalties?, weights: ZenzRanking.Weights, scores: MutableMap<String, Float> = HashMap()) =
            rankedWithContext(converter, judge, text, context, scoredCount, correction, scores, weights)
        fun checkEmpty(text: String, correction: EditPenalties?) {
            val base = order(text, "", correction, policies[0].second).second
            for ((_, weights) in policies.drop(1)) {
                compared++
                if (order(text, "", correction, weights).second == base) identical++
            }
        }
        PrintStream(Files.newOutputStream(target), true, "UTF-8").use { out ->
            out.println("id\tfamily\tkind\tpolicy\treading\texpected\tinList\tinScored\tlistSize\tdictRank\trankEmpty\trankPrefix\ttop1Empty\ttop1Prefix\tscoreExpected\tscoreTop1\tprefix")
            class Tally { var n = 0; var list = 0; var inScored = 0; var empty = 0; var prefix = 0; var gains = ArrayList<String>(); var losses = ArrayList<String>() }
            val tallies = LinkedHashMap<Pair<String, String>, Tally>()
            for (c in cases) {
                checkEmpty(c.reading, null)
                val (r, empty) = order(c.reading, "", null, policies[0].second)
                val dict = r.candidates.indexOfFirst { it in c.expected }
                val inScored = r.candidates.take(scoredCount).any { it in c.expected }
                fun rank(ord: List<Int>) = ord.indexOfFirst { r.candidates[it] in c.expected }.let { if (it < 0) "-" else (it + 1).toString() }
                val baselineRight = order(c.reading, c.prefix, null, policies[0].second).second.firstOrNull()?.let { r.candidates[it] in c.expected } ?: false
                for ((name, weights) in policies) {
                    val scores = LinkedHashMap<String, Float>()
                    val prefixed = order(c.reading, c.prefix, null, weights, scores).second
                    val top = r.candidates.getOrNull(prefixed.firstOrNull() ?: -1)
                    val best = r.candidates.firstOrNull { it in c.expected }
                    val right = top != null && top in c.expected
                    val t = tallies.getOrPut(c.kind to name) { Tally() }
                    t.n++
                    if (dict >= 0) t.list++
                    if (inScored) t.inScored++
                    if (empty.firstOrNull()?.let { r.candidates[it] in c.expected } == true) t.empty++
                    if (right) t.prefix++
                    if (right && !baselineRight) t.gains += c.id
                    if (!right && baselineRight) t.losses += c.id
                    fun fmt(v: Float?) = v?.let { "%.2f".format(it) } ?: "-"
                    out.println(listOf(
                        c.id, c.family, c.kind, name, c.reading, c.expected.joinToString("|"), (dict >= 0).toString(), inScored.toString(), r.candidates.size.toString(),
                        if (dict < 0) "-" else (dict + 1).toString(), rank(empty), rank(prefixed), r.candidates.getOrNull(empty.firstOrNull() ?: -1) ?: "-", top ?: "-",
                        fmt(best?.let { scores[it] }), fmt(top?.let { scores[it] }), c.prefix,
                    ).joinToString("\t"))
                }
            }
            val lines = ArrayList<String>()
            for ((key, t) in tallies) {
                lines += "${key.first}\t${key.second}\tcases=${t.n} recallList=${t.list} recallScored=${t.inScored} top1Empty=${t.empty} top1Prefix=${t.prefix} gains=${t.gains} losses=${t.losses}"
            }
            // Fixtures: the correct-reading column without correction, the typed column with it (the typo suite).
            for ((suite, correction) in listOf<Pair<String, EditPenalties?>>("reading" to null, "typed" to EditPenalties())) {
                val inputs = fixtures.map { (if (correction == null) it.reading else it.typed) to it }.filter { it.first.isNotEmpty() }
                inputs.forEach { checkEmpty(it.first, correction) }
                for (prefix in listOf("") + neutrals) {
                    val results = policies.map { (_, weights) ->
                        inputs.map { (text, f) ->
                            val (r, ord) = order(text, prefix, correction, weights)
                            r.candidates.isNotEmpty() && r.candidates[ord[0]] in f.expected
                        }
                    }
                    policies.forEachIndexed { p, (name, _) ->
                        val lost = inputs.indices.filter { results[0][it] && !results[p][it] }.map { inputs[it].second.id }
                        val gained = inputs.indices.filter { !results[0][it] && results[p][it] }.map { inputs[it].second.id }
                        lines += "fixtures-$suite\t$name\tn=${inputs.size} correct=${results[p].count { it }} lostVsBaseline=$lost gainedVsBaseline=$gained prefix=$prefix"
                    }
                }
            }
            lines += "emptyContextIdentical=$identical/$compared"
            if (options.flag("cold-check")) {
                // Sensitivity to the native prompt cache (data/context-holdout/addendum-after-dev.md): never a gate.
                val coldMemo = HashMap<List<Any>, FloatArray?>()
                val cold = ZenzRanking.Scorer { context, reading, list ->
                    coldMemo.getOrPut(listOf(context, reading, list)) {
                        s.score("", listOf("x"))
                        raw.score(context, reading, list)
                    }
                }
                fun right(c: PolicyCase, weights: ZenzRanking.Weights): Boolean {
                    val (r, ord) = rankedWithContext(converter, cold, c.reading, c.prefix, scoredCount, null, HashMap(), weights)
                    return ord.firstOrNull()?.let { r.candidates[it] in c.expected } ?: false
                }
                for ((kind, group) in cases.groupBy { it.kind }) {
                    val base = group.associate { it.id to right(it, policies[0].second) }
                    for ((name, weights) in policies) {
                        val now = group.associate { it.id to right(it, weights) }
                        val gains = group.filter { now.getValue(it.id) && !base.getValue(it.id) }.map { it.id }
                        val losses = group.filter { !now.getValue(it.id) && base.getValue(it.id) }.map { it.id }
                        lines += "cold-$kind\t$name\tcases=${group.size} top1Prefix=${now.values.count { it }} gains=$gains losses=$losses"
                    }
                }
            }
            // Same warmup and repeats for every option, interleaved and rotated so drift does not favour one.
            fun percentile(sorted: List<Double>, p: Double) = sorted[(p * sorted.size).toInt().coerceIn(0, sorted.lastIndex)]
            val cold = LinkedHashMap<String, ArrayList<Double>>()
            val keys = LinkedHashMap<String, ArrayList<Double>>()
            for (pass in 0 until warmup + repeats) for (i in policies.indices) {
                val (name, weights) = policies[(i + pass) % policies.size]
                for ((side, usePrefix) in listOf("empty" to false, "prefix" to true)) for (c in cases) {
                    val left = if (usePrefix) c.prefix else ""
                    s.score("", listOf("x")) // moves the cache off this prompt
                    val t0 = System.nanoTime()
                    rankedWithContext(converter, raw, c.reading, left, scoredCount, null, HashMap(), weights)
                    val first = (System.nanoTime() - t0) / 1e6
                    if (pass >= warmup) cold.getOrPut("$name $side") { ArrayList() } += first
                    for (end in 1..c.reading.length) {
                        val t1 = System.nanoTime()
                        rankedWithContext(converter, raw, c.reading.substring(0, end), left, scoredCount, null, HashMap(), weights)
                        val key = (System.nanoTime() - t1) / 1e6
                        if (pass >= warmup) keys.getOrPut("$name $side") { ArrayList() } += key
                    }
                }
            }
            for (name in cold.keys) {
                val a = cold.getValue(name).sorted()
                val b = keys.getValue(name).sorted()
                lines += "latency\t$name\twarmup=$warmup repeats=$repeats firstP50=%.1f firstP95=%.1f (n=${a.size}) keyP50=%.1f keyP95=%.1f (n=${b.size}) ms host only"
                    .format(percentile(a, 0.5), percentile(a, 0.95), percentile(b, 0.5), percentile(b, 0.95))
            }
            Files.write(options.path("summary"), lines, StandardCharsets.UTF_8)
            console.println("context-policy-eval cases=${cases.size} fixtures=${fixtures.size} policies=$names emptyContextIdentical=$identical/$compared")
        }
    }
    return 0
}

/**
 * Empty against committed left context on the shipped model. Inputs are synthetic (data/context-eval.tsv): the report
 * may carry them, and it carries nothing else. Without correction: the question is homophones of the exact reading.
 */
private fun contextEval(options: Options, console: PrintStream): Int {
    val target = options.path("out")
    target.parent?.let { Files.createDirectories(it) }
    val pairs = if (options.flag("no-pairs")) 0 else OssLimits().maxPairVariants
    val converter = OssConverter(MappedDictionary.open(options.path("resources")), OssLimits(maxPairVariants = pairs))
    val lib = options.path("lib").toAbsolutePath().toString()
    val scoredCount = options.int("scored", 8)
    val repeats = options.int("repeats", 3)
    // Evaluation only: what the rank prior does to context; the app keeps the default.
    val weights = ZenzRanking.Weights().let { base -> options.all("per-rank").lastOrNull()?.let { base.copy(perRank = it.toDouble()) } ?: base }
    val cases = Files.readAllLines(options.path("cases"), StandardCharsets.UTF_8).filter { it.isNotBlank() }.map { line ->
        val f = line.split('\t')
        require(f.size == 5) { "expected id, group, reading, surface[|surface...], prefix: ${f.firstOrNull()}" }
        ContextCase(f[0], f[1], f[2], f[3].split('|'), f[4])
    }
    val scorer = dev.astra.ime.oss.zenz.ZenzNative.open(options.path("model").toAbsolutePath().toString(), options.int("threads", 4)) {
        System.load(lib)
    } ?: throw IllegalArgumentException("zenz model could not be loaded")
    fun rankOf(r: OssConversion, order: List<Int>, surfaces: List<String>) =
        order.indexOfFirst { r.candidates[it] in surfaces }.let { if (it < 0) "-" else (it + 1).toString() }
    fun percentile(sorted: List<Double>, p: Double) = sorted[(p * sorted.size).toInt().coerceIn(0, sorted.lastIndex)]
    scorer.use { s ->
        val judge = ZenzRanking.Scorer { context, reading, list -> s.score(reading, list, context) }
        // Stored text never carries a prompt mark into the prompt (see ZenzRanking.promptContext).
        console.println("tokens: mark=${s.tokenCount("")} eosText=${s.tokenCount("</s>")} kana=${s.tokenCount("あ")} kanji=${s.tokenCount("私")} kata4=${s.tokenCount("アナウン")} (the vocabulary has no token for a mark: 3 byte tokens)")
        PrintStream(Files.newOutputStream(target), true, "UTF-8").use { out ->
            out.println("id\tgroup\treading\texpected\tinList\tlistSize\tdictRank\trankEmpty\trankPrefix\ttop1Empty\ttop1Prefix\tscoreExpected\tscoreEmptyTop1\tscoreExpectedNoPrefix\tprefix")
            var hits = 0
            var total = 0
            for (c in cases) {
                val before = LinkedHashMap<String, Float>()
                val after = LinkedHashMap<String, Float>()
                val (r, empty) = rankedWithContext(converter, judge, c.reading, "", scoredCount, null, before, weights)
                val (_, withPrefix) = rankedWithContext(converter, judge, c.reading, c.prefix, scoredCount, null, after, weights)
                val best = c.expected.firstOrNull { it in r.candidates }
                val top = r.candidates[empty[0]]
                val rank = rankOf(r, withPrefix, c.expected)
                if (c.group != "control") {
                    total++
                    if (rank == "1") hits++
                }
                fun fmt(v: Float?) = v?.let { "%.2f".format(it) } ?: "-"
                out.println(listOf(
                    c.id, c.group, c.reading, c.expected.joinToString("|"), (best != null).toString(), r.candidates.size.toString(),
                    rankOf(r, r.candidates.indices.toList(), c.expected), rankOf(r, empty, c.expected), rank,
                    top, r.candidates[withPrefix[0]], fmt(best?.let { after[it] }), fmt(after[top]), fmt(best?.let { before[it] }), c.prefix,
                ).joinToString("\t"))
            }
            console.println("cases=${cases.size} targetCases=$total targetTop1WithPrefix=$hits scored=$scoredCount")
            // Cold: a new composition decodes the context. Typing: each kana extends the prompt, as the KV cache sees it.
            for ((name, context) in listOf("empty" to null, "prefix" to "prefix")) {
                val cold = ArrayList<Double>()
                val typing = ArrayList<Double>()
                repeat(repeats) {
                    for (c in cases) {
                        val left = if (context == null) "" else c.prefix
                        s.score("", listOf("x")) // moves the cache off this prompt
                        val t0 = System.nanoTime()
                        rankedWithContext(converter, judge, c.reading, left, scoredCount, null, HashMap())
                        cold += (System.nanoTime() - t0) / 1e6
                        for (end in 1..c.reading.length) {
                            val t1 = System.nanoTime()
                            rankedWithContext(converter, judge, c.reading.substring(0, end), left, scoredCount, null, HashMap())
                            typing += (System.nanoTime() - t1) / 1e6
                        }
                    }
                }
                cold.sort()
                typing.sort()
                console.println(
                    "latency $name repeats=$repeats coldP50=%.1f coldP95=%.1f (n=${cold.size}) keyP50=%.1f keyP95=%.1f (n=${typing.size}) ms host only"
                        .format(percentile(cold, 0.5), percentile(cold, 0.95), percentile(typing, 0.5), percentile(typing, 0.95)),
                )
            }
            val fixtures = options.all("fixtures").flatMap { readFixtures(Paths.get(it)) }
            val neutrals = options.all("neutral").flatMap { Files.readAllLines(Paths.get(it), StandardCharsets.UTF_8) }.filter { it.isNotBlank() }
            if (fixtures.isNotEmpty()) {
                val correct = fixtures.count { f ->
                    val (r, order) = rankedWithContext(converter, judge, f.reading, "", scoredCount, null, HashMap(), weights)
                    r.candidates.isNotEmpty() && r.candidates[order[0]] in f.expected
                }
                console.println("fixtures=${fixtures.size} top1Correct=$correct without context, pairVariants=$pairs")
            }
            for (neutral in neutrals) {
                var both = 0
                var kept = 0
                var lost = 0
                var gained = 0
                var moved = 0
                val lostIds = ArrayList<String>()
                for (f in fixtures) {
                    val (r, empty) = rankedWithContext(converter, judge, f.reading, "", scoredCount, null, HashMap(), weights)
                    val (_, prefix) = rankedWithContext(converter, judge, f.reading, neutral, scoredCount, null, HashMap(), weights)
                    if (r.candidates.isEmpty()) continue
                    val a = r.candidates[empty[0]]
                    val b = r.candidates[prefix[0]]
                    if (a != b) moved++
                    when {
                        a in f.expected && b in f.expected -> both++
                        a in f.expected -> {
                            lost++
                            lostIds += "${f.id}:$a>$b"
                        }
                        b in f.expected -> gained++
                        else -> kept++
                    }
                }
                console.println("neutral fixtures=${fixtures.size} top1Moved=$moved correctBoth=$both lostByPrefix=$lost gainedByPrefix=$gained wrongBoth=$kept lost=$lostIds prefix=$neutral")
            }
        }
    }
    return 0
}

private fun isKanji(cp: Int) = Character.isIdeographic(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN

/** Exact lists against the dictionary read directly, from the shipped lexicon.bin. Prints no user text: inputs are dictionary words. */
private fun exactEval(options: Options, console: PrintStream): Int {
    val target = options.path("out")
    target.parent?.let { Files.createDirectories(it) }
    val dictionary = MappedDictionary.open(options.path("resources"))
    val lexicon = dictionary.lexicon
    val converter = OssConverter(dictionary)
    val readings = Files.readAllLines(options.path("readings"), StandardCharsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    val stride = options.int("stride", 400)
    val rareCount = options.int("rare", 200)
    val iterations = options.int("iterations", 200)
    var failures = 0
    PrintStream(Files.newOutputStream(target), true, "UTF-8").use { out ->
        // Same reading, any correction or user state: the exact list comes from the dictionary alone.
        fun check(reading: String, label: String): Boolean {
            val want = dictionarySurfaces(lexicon, reading)
            val got = converter.exactCandidates(reading)!!
            val whole = got.candidates.filter { it.whole }.map { it.surface }
            val ok = whole.toSet() == want && whole.size == got.wholeCount && whole.size == want.size &&
                got.candidates.map { it.surface }.toSet().size == got.candidates.size
            if (!ok) {
                failures++
                out.println("FAIL $label reading=$reading dictionary=${want.size} exact=${whole.size} missing=${(want - whole.toSet()).take(20)}")
            }
            return ok
        }
        for (reading in readings) {
            val want = dictionarySurfaces(lexicon, reading)
            val got = converter.exactCandidates(reading)!!
            val ok = check(reading, "probe")
            out.println("reading=$reading dictionaryExactSurfaces=${want.size} exactList=${got.wholeCount}+${got.candidates.size - got.wholeCount}segmented recall=${if (ok) "all" else "INCOMPLETE"}")
            out.println("  exact=${got.candidates.take(400).joinToString(" ") { it.surface }}")
            for ((name, correction) in listOf("off" to null, "scale1" to EditPenalties(), "scale2" to EditPenalties().scaled(2.0))) {
                val r = converter.convert(reading, 20, correction)
                val hit = want.count { it in r.candidates.toSet() }
                out.println("  $name ranked=${r.candidates.size} exactInRanked=$hit/${want.size} correctionLeads=${r.correctionLeads} suggestions=${r.suggestions.size}")
                out.println("  $name list=${r.candidates.zip(r.readings).joinToString(" ") { (s, k) -> if (k == reading) s else "$s<$k>" }}")
            }
        }
        // Whole-dictionary walk: every reading over the old caps, a stride through the rest, and the single kanji census.
        var total = 0
        var over20 = 0
        var over24 = 0
        var over100 = 0
        var checked = 0
        val singles = HashMap<String, Int>()
        var singlePairs = 0
        val rare = java.util.PriorityQueue<Triple<Int, String, String>>(compareBy { it.first })
        lexicon.forEachReading { reading, termId ->
            val n = dictionarySurfaces(lexicon, reading).size
            total++
            if (n > 20) over20++
            if (n > 24) over24++
            if (n > 100) over100++
            if (n > 20 || total % stride == 0) {
                checked++
                check(reading, "sweep")
            }
            lexicon.forEachEntry(termId) { _, cost, node ->
                if (node < 0) return@forEachEntry
                val s = lexicon.surface(node)
                if (s.codePointCount(0, s.length) == 1 && isKanji(s.codePointAt(0))) {
                    singlePairs++
                    singles.merge(s, 1, Int::plus)
                    rare += Triple(cost, reading, s)
                    if (rare.size > rareCount) rare.poll()
                }
            }
        }
        out.println("sweep readings=$total over20=$over20 over24=$over24 over100=$over100 checked=$checked failures=$failures")
        val rareList = rare.toList().sortedByDescending { it.first }
        var rareHits = 0
        for ((_, reading, surface) in rareList) {
            if (converter.exactCandidates(reading)!!.candidates.any { it.surface == surface }) rareHits++ else {
                failures++
                out.println("FAIL rare reading=$reading")
            }
        }
        val supplementary = singles.keys.count { it.codePointAt(0) > 0xFFFF }
        out.println("singleKanji surfaces=${singles.size} pairs=$singlePairs supplementaryPlane=$supplementary inCjkBasicBlock=${singles.keys.count { it.codePointAt(0) in 0x4E00..0x9FFF }} (dictionary entries with kana readings; not a Unicode coverage claim)")
        out.println("rareSingleKanji sampled=${rareList.size} reachable=$rareHits costliest=${rareList.take(40).joinToString(" ") { "${it.third}<${it.second}>" }}")
        // Host JVM only: says nothing about a phone.
        val bench = (readings + "ゆうき" + "こうき").distinct()
        repeat(20) { bench.forEach { converter.exactCandidates(it) } }
        for ((name, run) in listOf<Pair<String, (String) -> Unit>>(
            "exactCandidates" to { r -> converter.exactCandidates(r) },
            "completions" to { r -> converter.completions(r, 32) },
        )) {
            val nanos = LongArray(iterations * bench.size)
            var i = 0
            repeat(iterations) { bench.forEach { r -> val s = System.nanoTime(); run(r); nanos[i++] = System.nanoTime() - s } }
            nanos.sort()
            fun pct(p: Double) = nanos[(Math.ceil(p * nanos.size).toInt() - 1).coerceIn(0, nanos.lastIndex)] / 1e6
            out.println("bench $name samples=${nanos.size} p50Ms=${"%.3f".format(pct(0.5))} p95Ms=${"%.3f".format(pct(0.95))} maxMs=${"%.3f".format(nanos.last() / 1e6)} (host JVM, warm, not a device measurement)")
        }
        out.println("failures=$failures")
    }
    console.println("exact-eval failures=$failures report: ${target.fileName}")
    return if (failures == 0) 0 else 3
}

private fun readZenzScores(path: Path): Map<String, Double> =
    Files.readAllLines(path, StandardCharsets.UTF_8).filter { it.isNotBlank() }.associate { line ->
        val fields = line.split('\t')
        fields.first() to fields.last().toDouble()
    }

private fun bench(options: Options, out: PrintStream): Int {
    val loaded = load(options)
    val limit = options.int("limit", 5)
    val warmup = options.int("warmup", 3)
    val iterations = options.int("iterations", 10)
    require(warmup >= 0 && iterations > 0) { "--warmup must be >= 0 and --iterations > 0" }
    val sets = LinkedHashMap<String, List<String>>()
    for (path in options.all("fixtures").map { Paths.get(it) }) sets[path.fileName.toString()] = readFixtures(path).map { it.reading }
    val synthetic = options.int("synthetic", 0)
    if (synthetic > 0) {
        val maxLen = options.int("synthetic-max-len", 24)
        require(maxLen >= 1) { "--synthetic-max-len must be >= 1" }
        val random = Random(options.int("seed", 1))
        val kana = (0x3042..0x3093).map { it.toChar() }
        sets["synthetic"] = List(synthetic) { i -> String(CharArray(1 + i % maxLen) { kana[random.nextInt(kana.size)] }) }
    }
    require(sets.isNotEmpty()) { "bench needs --fixtures and/or --synthetic" }

    out.println("loadMillis=${loaded.loadMillis} approxHeapMbAfterLoad=${loaded.heapMb} (JVM warm timings only, not a device measurement)")
    out.println("peakHeapMbUpperBoundDuringLoad=${peakHeapMb()}")
    for ((name, inputs) in sets) {
        repeat(warmup) { inputs.forEach { loaded.converter.convert(it, limit) } }
        val nanos = LongArray(inputs.size * iterations)
        val statuses = HashMap<OssStatus, Int>()
        var nBest = 0
        var k = 0
        repeat(iterations) { iteration ->
            for (text in inputs) {
                val started = System.nanoTime()
                val r = loaded.converter.convert(text, limit)
                nanos[k++] = System.nanoTime() - started
                if (iteration == 0) {
                    statuses[r.status] = (statuses[r.status] ?: 0) + 1
                    if (r.nBestUsed) nBest++
                }
            }
        }
        nanos.sort()
        fun pct(p: Double): String = "%.3f".format(nanos[(Math.ceil(p * nanos.size).toInt() - 1).coerceIn(0, nanos.lastIndex)] / 1e6)
        out.println(
            "$name: inputs=${inputs.size} calls=${nanos.size} p50Ms=${pct(0.50)} p95Ms=${pct(0.95)} maxMs=${pct(1.0)} " +
                "nBestUsed=$nBest statuses=${statuses.toSortedMap()}",
        )
    }
    return 0
}

# OSS converter integration prototype (`:oss-converter`)

> **Historical design record** (0.1.2 to 0.1.11 trial versions), published with the 1.0.0 source. It is not a description
> of 1.0.0: since 1.0.0 this converter, the dictionary and zenz are used by default in every build, and the
> "debug-only, default-off" wording below describes the trial versions. For building 1.0.0 see [BUILDING.md](../BUILDING.md);
> for components and notices see [THIRD-PARTY.md](../THIRD-PARTY.md). Records and checklists this page cites that are not
> part of the published source are marked "not published".

Experimental, JVM-only. It compiles **unmodified Mozc dictionary_oss data** with the pinned primitives from
[kotlin-kana-kanji-converter](../third_party/kotlin-kana-kanji-converter/README.md) (MIT, revision in
`source-lock.json`, two recorded TokenArray patches in [PATCHES.md](../third_party/kotlin-kana-kanji-converter/PATCHES.md)).
Then it converts kana with that upstream engine. This is **not** Mozc's converter: it has no
segmenter, rewriters, suggestion, user history or symbol/emoji dictionaries. It is **not** Zenzai and has no model.
It does no typo correction. Since 0.1.2-trial, `:app` can use it behind a debug-only, default-off switch (see
"App connection" below); the default conversion path is unchanged. (Historical: see the note at the top for 1.0.0.)

## What is reused and what is ours

| Upstream (compiled from `third_party/.../upstream/src/main/kotlin`; only `TokenArray.getNodeIdForDictionary` patched) | Ours (`android/oss-converter`) |
|---|---|
| `PrefixTree`/`PrefixTreeWithTermId` → `Converter`/`ConverterWithTermId` → `LOUDS`/`LOUDSWithTermId` | Strict TSV (5 columns, or 6 with `SPELLING_CORRECTION`) and `id.def` reader (`MozcSourceParser`) |
| `TokenArray.buildPOSTable`/`buildPOSTableWithIndex`/`buildTokenArray`; `buildAndWriteDictionaryArtifacts` is the parity reference only | Build orchestration, record count/round-trip audit, manifest (`OssDictionaryBuilder`); the same trie/LOUDS/token steps one trie at a time with byte-identical output (`DictionaryArtifacts`) |
| `ConnectionMatrixParser` (text) and `ConnectionMatrixIO` (raw) | Strict resource loader with closed streams and a class allowlist (`OssResources`) |
| `GraphBuilder.constructGraph`, `FindPath.findBestPath`/`backwardAStar` (same composition as `KanaKanjiEngine`) | Bounded, thread-safe wrapper with raw fallback (`OssConverter`), CLI (`OssCli`) |

The build excludes `dictionary/DicUtils.kt` and `single_kanji/**`. They need upstream `Constants`, custom vocabulary
and single-kanji data, which are deliberately not pinned. `SystemNgramDictionary.kt` references two symbols from
unpinned n-gram files. `src/main/kotlin/com/kazumaproject/ngram/UpstreamNgramShims.kt` provides compile-only
stand-ins for them that throw. Only the `Empty*` n-gram dictionaries are used, so no n-gram data, ATOK data,
English data, custom entries or handmade entries are involved. Upstream's LICENSE is copied into the jar's
`META-INF`.

## Status: initial version, Task 11 fixes, full data

| Topic | Initial version (Task 10, `5d78cd9`) | After Task 11 fixes (this branch) |
|---|---|---|
| Sixth TSV column | Any 6-column row rejected, so the real data (62 `SPELLING_CORRECTION` rows) could not build | Exactly `SPELLING_CORRECTION` accepted and counted (`spellingCorrectionRows` in stats, report, manifest); any other tag or column count rejected with file:line. First five fields kept unchanged. Mozc's spelling-correction semantics are **not** implemented: tagged rows convert as ordinary entries |
| Kana surfaces | Upstream sentinels rewrote surfaces (`ヴァイオリン → バイオリン`); counted only | Upstream patch keeps exact surfaces (incl. `こんにちは` for `こんにちわ`, `ゔ` readings, NFD). Build audit **fails** on any rendered-surface mismatch |
| N-best | `backwardAStar` for runs ≤12 chars by default (unpruned queue) | Off by default (`maxNBestRunLength = 0`): best Viterbi + raw. Opt-in kept only for tiny test fixtures, not production |
| CLI fixtures | Expected column compared as one string | `|`-separated alternatives; labelled as correct-reading conversion, not typo restoration |

Tested on the small generated fixture only (`:oss-converter:test`, 20 tests). The full-data build, demo and bench
results were recorded in the development log, which is not published; anything not listed on this page is unverified on
the full data.

## Commands

Run from the repository root with the project-local toolchain ([BUILDING.md](../BUILDING.md)). Mozc data must already be
at a pinned `google/mozc` revision under the ignored `.astra/local/oss/mozc/` (`scripts/fetch-mozc-data.ps1`, BUILDING.md
section 4.1). These commands download nothing. To use a toolchain outside the repository, pass `-ToolchainRoot`
(BUILDING.md section 1).

```powershell
# Tests (small generated fixture; no Mozc data needed)
& ./scripts/build-android.ps1 -Tasks ':oss-converter:test'

# Build resources. --out must be empty or missing. Forked JVM heap defaults to 2g (-PossHeap=... to change).
$mozc = '.astra/local/oss/mozc/<rev>/src/data/dictionary_oss'
$out  = '.astra/local/oss/compiled/<rev>'
& ./scripts/build-android.ps1 -Tasks ':oss-converter:ossCli',"--args=build --mozc-dir $mozc --out $out"

# Correctness on the dev fixtures (reading column -> expected surface) plus ad-hoc text
& ./scripts/build-android.ps1 -Tasks ':oss-converter:ossCli',"--args=demo --resources $out --fixtures android/core/src/test/resources/feedback.tsv --text きょうはあめです"

# 0.1.7+: Latin spellings from SudachiDict raw lexicons (third_party/sudachidict/README.md), then build with --extra
$s = '.astra/local/oss/sudachi/20261015/x'
& ./scripts/build-android.ps1 -Tasks ':oss-converter:ossCli',"--args=english --sudachi $s/small_lex.csv --sudachi $s/core_lex.csv --sudachi $s/notcore_lex.csv --mozc-dir $mozc --out .astra/local/oss/english/english-20261015.txt"
& ./scripts/build-android.ps1 -Tasks ':oss-converter:ossCli',"--args=build --mozc-dir $mozc --extra .astra/local/oss/english/english-20261015.txt --out $out-en20261015"

# JVM warm latency (p50/p95/max), separate for fixtures and seeded synthetic kana
& ./scripts/build-android.ps1 -Tasks ':oss-converter:ossCli',"--args=bench --resources $out --fixtures android/core/src/test/resources/feedback.tsv --synthetic 200 --synthetic-max-len 32 --warmup 3 --iterations 10"
```

`build-android.ps1` also re-exports the lexicon and feedback cases first; that is existing behaviour. In
`ossCli`, relative paths resolve from the repository root. Non-ASCII `--text` depends on the console code page.
If it is garbled, use `--fixtures` instead.

Expected inputs in `--mozc-dir` are `dictionary00.txt`–`dictionary09.txt`, `id.def`,
`connection_single_column.txt` and `suffix.txt`. Pass `--no-suffix` to exclude `suffix.txt`. Outputs are
`yomi.dat`, `tango.dat`, `token.dat`, `pos_table.dat`, `connectionId.dat`, `build-report.txt` and
`oss-manifest.properties`. The manifest is written last and records input SHA-256s, counts and the upstream
revision. `pos_table_for_build.dat` is a build-only upstream intermediate and is not read at runtime.

### Must be checked on the real data

- **suffix.txt** has the same `reading, leftId, rightId, cost, surface` layout (parent inspection of the pinned
  revision). Mozc uses suffix entries only after content words. Merging them into the main dictionary lets them
  stand alone, which can change rankings; `--no-suffix` excludes them. This is a ranking decision, not a format one.
- `connection_single_column.txt` is expected as a decimal dimension line followed by exactly `n*n` signed 16-bit
  costs. The dimension must equal the `id.def` count. A BOM, a different header or a wrong count fails the build.
- Read `build-report.txt`. Records are never dropped or merged: exact duplicates and cost variants are counted
  and kept. The build stops on structural mismatches after write: IDs, costs or token counts read back through
  upstream lookups.
- **Surface rewrites (fixed by patch).** Unpatched upstream `TokenArray` stored every kana-only surface as a
  sentinel rendered from the reading, so e.g. `ばいおりん/ヴァイオリン` became `バイオリン`. The patch uses a
  sentinel only when it renders back exactly; `surfaceRewrites` in `build-report.txt` must be all 0, and any
  mismatch now aborts the build.
- Retain Mozc's LICENSE/notices for the pinned revision with any compiled asset.

## Runtime behaviour (`OssConverter`)

- Input is split into runs of hiragana (U+3041–U+3096) and `ー`. Only these runs reach upstream. Katakana,
  ASCII, full-width forms, punctuation, emoji and unpaired surrogates pass through verbatim. Upstream
  `commonPrefixSearch` continues after a mismatch from the super-root, so arbitrary characters are kept away from it.
- A run with no complete dictionary path is kept as typed (`PARTIAL`, `unconvertedRuns`). One uncovered kana
  currently leaves its whole run unconverted.
- Candidates are best first, deduplicated, at most `limit` (2..10), and the raw input is always included.
  By default the output is the best Viterbi path plus raw. N-best (`backwardAStar`) is off
  (`maxNBestRunLength = 0`): upstream keeps whole partial paths in an unpruned priority queue, cannot be
  interrupted, and its ordering is not reliable with negative connection costs. A positive value opts in for a
  single run within that length and ≤`maxNBestGraphNodes` lattice nodes; this is for tiny test fixtures only.
  Inputs over 64 UTF-16 units return raw only (`INPUT_TOO_LONG`). Runs whose lattice exceeds 20,000 nodes are
  left raw. These bounds are initial guesses and should be tuned with `bench`.
- Upstream exceptions (and `StackOverflowError`) return raw only with `BACKEND_ERROR`. Only the exception class
  name is kept. Nothing is logged, and `toString()` is redacted. `OutOfMemoryError` is not caught.
- Calls are serialized on one lock. A running conversion **cannot be cancelled or pre-empted** because upstream
  has no checkpoints. Use one worker and discard stale results.

## Risks of reusing the upstream loaders

- `KanaKanjiEngine.buildEngineFromResourceDirectory` and `TokenArray.readPOSTable` leave some file streams
  open: `pos_table.dat`, `connectionId.dat`, and the others on error. They also print stack traces to stdout and
  continue with empty structures. The `readPOSTableWithIndex` used during build is also left open. `OssResources`
  does not use the engine loader. It reads the same layout itself, fails on any error and closes every stream.
  Only the parity test calls the upstream loader.
- Upstream writers (`buildPOSTable`, `writeExternalNotCompress`) swallow I/O errors. The builder checks that
  outputs exist, then reloads and audits them.
- The resources use Java serialization. `OssResources` resolves only `BitSet`, `long[]`, `char[]`, `int[]` and
  `short[]`. Android has no `ObjectInputFilter`; this `resolveClass` allowlist also works on Android. Even so,
  ship only assets built by this pipeline and verified by SHA-256. Never load downloaded or user-supplied `.dat`
  files.

## Memory-mapped lexicon (`lexicon.bin`, 0.1.11+)

The APK ships `lexicon.bin` instead of `yomi.dat`/`tango.dat`/`token.dat`/`pos_table.dat`, and the app maps it
read-only straight from the APK (`AssetManager.openFd`, asset stored uncompressed via `noCompress "bin"`). Its pages
are file-backed: they are not Java heap, the OS can drop and re-read them, and nothing is deserialized at load.
`connectionId.dat` (the n² short matrix, about 14 MB) and the manifest are still read as before.

```powershell
# After build: write lexicon.bin next to the .dat files and audit it (every term ID's entries and surfaces).
& ./scripts/build-android.ps1 -Tasks ':oss-converter:ossCli',"--args=pack --resources $out-en20261015"
# Compare the deserialized and the mapped path on the fixtures (expect no changed lines), and bench the mapped one.
& ./scripts/build-android.ps1 -Tasks ':oss-converter:ossCli',"--args=compare --old $out-en20261015 --new $out-en20261015 --new-mapped --fixtures android/core/src/test/resources/feedback.tsv --fixtures data/dictionary-probes.tsv"
& ./scripts/build-android.ps1 -Tasks ':oss-converter:ossCli',"--args=bench --resources $out-en20261015 --mapped --fixtures android/core/src/test/resources/feedback.tsv"
```

- **Same data, same answers.** `pack` copies the arrays upstream deserializes (LOUDS bit vectors and labels, term
  IDs, token columns, POS table) unchanged, adds a rank directory per 512-bit block, and audits the result against
  the upstream lookups before giving it its final name. `MappedLexicon` ports the upstream lookups line by line
  (`commonPrefixSearch`, `getNodeIndex`/`search`, `getTermId`, `getLetter`, `getListDictionaryByYomiTermId`,
  `SuccinctBitVector` rank/select). `lattice()` reproduces `GraphBuilder.constructGraph` over either lexicon, so Viterbi,
  connection costs, zenz and learning are unchanged. Tests: `MappedLexiconTest` (bit vectors vs `SuccinctBitVector`
  on random and deserialized `BitSet`s, every lookup, lattices equal to `GraphBuilder`, converter output with
  correction and user words, malformed files).
- **Layout** (little endian): magic `ASTRALEX`, version 1, section count, then (offset, length) per section; sections
  are 8-byte aligned. Bit vector sections: size (= `BitSet.size()`), ones, word count, block count, words, block ranks.
- **Found while porting:** `getLetter` reverses its char list one char at a time. `StringBuilder.reverse()` keeps
  surrogate pairs together and gave different surfaces for two adjacent non-BMP characters; fixed and covered by a
  fixture.
- **Host measurements (2026-10-29, `bench` warmup 3 × 10, JVM heap after `System.gc()`, not a device):**

  | resources (lexicon.bin) | compact heap / load ms | mapped heap / load ms | synthetic p50/p95 ms compact → mapped |
  |---|---|---|---|
  | current `-en20261015` (25.0 MB) | 52 / 170 | 14 / 42 | 0.289/0.699 → 0.231/0.622 |
  | `ut-sudachi-floor11000` (48.4 MB) | 87 / 299 | 14 / 46 | 0.300/0.743 → 0.234/0.628 |
  | `ut-all-20261029` (63.5 MB) | 105 / 326 | 14 / 43 | 0.298/0.751 → 0.236/0.644 |

  `compare --new-mapped` on feedback.tsv (50) and dictionary-probes.tsv (33): no changed line for any of the three.
- **Cost:** the APK stores `lexicon.bin` uncompressed, so it grows (0.1.11: 55.6 MB vs 44.9 MB). The app hashes the
  mapped bytes once per load, which reads every page once (page cache, not heap).

## Android notes (not measured on any device)

- API 26 is compatible: `java.nio.file`, `ObjectInputStream` and `BitSet` exist there. Use
  `OssResources.load { name -> assets.open(name) }`, which closes every stream. Load on a background thread
  before the first conversion.
- Memory is the main risk. Upstream holds labels and token columns as boxed `MutableList<Char/Short/Int>`. The
  connection matrix is n² shorts (about 14 MB for ~2.7k IDs), and `ConnectionMatrixIO.read` briefly doubles it.
  Expect a heap well above the current lexicon. `bench` prints only a rough JVM heap estimate. Measure PSS on a
  device before shipping. Consider `noCompress` for `.dat` assets, or copying them to app storage once.
- Java-serialized `BitSet` written on desktop is expected to deserialize on Android, but this is unverified.

## App connection (0.1.2-trial, debug-only, default off; historical)

Done in `:app` (`OssBackend`, `ConversionWorker`, `DebugSettings`):

1. Full-data build reviewed (the review record, `reports/oss-full-2026-10-27/`, is not published); suffix kept merged by
   the owner's choice.
2. `:app` depends on `:oss-converter`. A checkbox in the setup screen (debug builds only, default off) enables it;
   the IME applies it when an editor starts. Resources load from `assets/oss/` on a separate thread, each stream
   checked against the bundled `oss-assets.sha256` (copied from `android/app/oss-assets.lock`, which the Gradle
   asset task also enforces at build time). Conversions run on the existing single worker. OSS candidates are used
   only when the converter was ready at submit time and returned `OK`; otherwise `:core` converts. Raw is always
   kept, stale results are dropped, sensitive fields never convert, and nothing is logged or learned. Load failure
   (including OOM) leaves `:core` in use; toggling off and on retries.
3. APK 16.0 MB (assets compressed). Not yet measured on the Pixel at that time: cold load time, PSS and per-keystroke
   latency (the trial checklist that listed them is not published). The desktop-serialized `BitSet` loads under
   Robolectric (JVM), which is not proof for Android's libcore.

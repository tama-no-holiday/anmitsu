# Local patches to the pinned upstream snapshot

Upstream: [KazumaProject/kotlin-kana-kanji-converter](https://github.com/KazumaProject/kotlin-kana-kanji-converter)
revision `13e391bc7a4a245f63aa0091c1e9c11db3dbd759`, MIT License (`upstream/LICENSE`, unchanged). The original
byte hashes stay in `source-lock.json` under `files`; post-patch hashes are recorded separately under
`patchedFiles`. Every patched region carries an `ai-japanese-ime patch` comment.

## 1. `src/main/kotlin/dictionary/TokenArray.kt` — `getNodeIdForDictionary`

(Section 2 below patches the same file; its table supersedes the "After this patch" hash here.)

| | SHA-256 |
|---|---|
| Upstream original | `78f10dcebd521f4db02c23b555dd5311ad7db85e5026a689678fcce947102c10` |
| After this patch (LF) | `4906145ad0776bafa12e95454accbbcb6c7885d0332870c0d06e0850711d7bdb` |

**Reason.** Upstream stores any surface that `isHiraganaOnlyPure()` / `isKatakanaOnlyPure()` accepts (after NFC
normalization) as a sentinel, and `GraphBuilder` renders sentinels from the *reading* (`-2` → reading,
`-1` → `reading.hiraToKata()`). Surfaces that are kana but not exactly the reading or its `hiraToKata()` were
therefore emitted differently from the dictionary. The first case was observed by the pre-patch test suite; the
others follow from the upstream code and are now regression cases in our fixture:

- `ばいおりん/ヴァイオリン` → `バイオリン` (katakana spelling differs)
- `こんにちわ/こんにちは` → `こんにちわ` (hiragana spelling differs)
- `ゔぁいおりん/ヴァイオリン` → `ゔァイオリン` (`hiraToKata` covers only U+3041..U+3093, not `ゔ`)
- decomposed `う` + U+3099 → precomposed reading `ゔ`; non-kana surfaces were looked up after NFC.

**Change.** Hiragana sentinel only if `surface == reading`; katakana sentinel only if
`surface == reading.hiraToKata()` (upstream's own function). Otherwise the raw, unnormalized surface is looked up
in the tango trie, which `buildAndWriteDictionaryArtifacts` already fills with raw surface strings
(`skipKanaOnlyTango = false` in our builder). A missing trie entry now fails (`check`) instead of returning `-1`,
which would collide with the katakana sentinel. Costs, POS IDs, token order and the file format are unchanged;
only `nodeId` values for affected surfaces differ. The now-unused `java.text.Normalizer` import is left in place to
keep the diff minimal.

**Guard.** `OssDictionaryBuilder.audit` renders every record back exactly as `GraphBuilder` does and fails the build
if any surface differs (`OssConverterTest.auditFailsOnAnySurfaceMismatch`,
`preservesExactSurfacesThatUnpatchedUpstreamRewrote`).

Note: `skipKanaOnlyTango = true` (unused by this project) would now fail on kana surfaces that need the trie.

## 2. `src/main/kotlin/dictionary/TokenArray.kt` — `setLoadedColumns`

| | SHA-256 |
|---|---|
| Upstream original | `78f10dcebd521f4db02c23b555dd5311ad7db85e5026a689678fcce947102c10` (unchanged) |
| After section 1 only (LF) | `4906145ad0776bafa12e95454accbbcb6c7885d0332870c0d06e0850711d7bdb` |
| After sections 1 + 2 (LF) | `8dc75924b5cdb1d11cd02afcfa7a9780fba5fcd682fee8196bda342d6edced0d` (computed by Claude Code 2026-10-29) |

**Reason.** `readExternalNotCompressStrict` copies the loaded `ShortArray`/`IntArray` columns into boxed
`MutableList<Short/Int>` (`toMutableList()`), one retained object per token entry for most values; the columns are
private, so a loader cannot supply anything else without reflection.

**Change.** One inserted `internal` function right after `readExternalNotCompressStrict` that assigns the three
columns and the postings `BitSet` the caller already read (same field order), then runs the same `rebuildCache()` and
`check(entryCount >= 0)`. `OssResources` uses it to install read-only `LoadedShortList`/`LoadedIntList` views over the
deserialized arrays. No existing line changed: builder paths, `readExternalNotCompressStrict` (kept as the boxed
reference loader in `LoadedResourcesTest`), lookups and the file format are untouched. It affects loading only, not
built artifacts, so the manifest's `upstreamPatches` value (`OssDictionaryBuilder.UPSTREAM_PATCHES`) is unchanged.

**Guard.** `LoadedResourcesTest` compares the compact loader with the boxed one on every reading, token entry, POS ID,
label and conversion probe; `DictionaryArtifactsParityTest` still checks byte parity of the built files.

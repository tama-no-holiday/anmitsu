# Dictionary expansion and periodic update (Mozc-UT)

> **Historical design record and maintainer procedure**, published with the 1.0.0 source. The status and numbers below
> are those of the 0.1.12 trial. For the dictionary in 1.0.0, see [THIRD-PARTY.md](../THIRD-PARTY.md) and
> `android/app/oss-assets.lock`; for building, see [BUILDING.md](../BUILDING.md).

Status (2026-10-29): **adopted in `0.1.12-trial`.** The app ships Mozc dictionary_oss (d766d25) + 92,423 SudachiDict
Latin spellings + 1,459,000 UT rows from all three sources below, prepared with `--cost-floor 11000` (record, hashes
and the reason for the floor in `third_party/mozc-ut/README.md`). The compiled build is packed into `lexicon.bin`
(memory-mapped, see [oss-converter-integration.md](oss-converter-integration.md)), so the dictionary size no longer adds
Java heap.

## What this adds

The engine (pinned kotlin-kana-kanji-converter primitives + Mozc costs/matrix) is unchanged. The vocabulary grows
through the existing `ossCli build --extra` path with one more Mozc-format file, `ut-extra.txt`, prepared from
publicly distributed Mozc-UT dictionaries:

| source | repository | licence (verify per revision) | POS used |
|---|---|---|---|
| sudachidict | https://github.com/utuhiro78/mozcdic-ut-sudachidict | Apache-2.0 | `名詞,一般,*,*,*,*,*` |
| neologd | https://github.com/utuhiro78/mozcdic-ut-neologd | Apache-2.0, plus the mecab-ipadic-neologd `COPYING` notice | `名詞,一般,*,*,*,*,*` |
| personal-names | https://github.com/utuhiro78/mozcdic-ut-personal-names | Apache-2.0 | `名詞,固有名詞,人名,一般,*,*,*` |

Do not assume every "UT" dictionary is GPL or CC. Other UT dictionaries (place names: Japan Post data notice;
JapaneseCorpus-derived: CC BY-SA) are **not** part of this pipeline. All three sources are pinned in
`third_party/mozc-ut/sources.lock.json`. The maintainer fetched and verified each pin with
`fetch-ut-dictionaries.py` into the ignored `.astra/local/oss-ut/`:

| source | revision | archive SHA-256 | rows |
|---|---|---|---|
| sudachidict | `13129ddebe1cb8eabb63feccbe157adec4e1de01` | `1b9555338cc36606a5f497a2f57a0f9b948fa02d8c13d18ee9cfb5c99fb9d86e` | 1,285,149 |
| neologd | `d8307abf02b830b185c9320822cffa0d0787c54e` | `231b9b5e4ad16baa3f79680f4e4615f7060511a41da4aac2149789eace3f2411` | 1,546,041 |
| personal-names | `b557dd4c1548cc7c2164d5f8b14170c40cbb6f81` | `610e2e52fad15df0edac854960a05451a7e9a62f3df09c3f065db9e1ab6f51f4` | 87,280 |

Rows carry the placeholder ids `0000`. Pinned does not mean adopted. (Historical: when the pins were first recorded,
nothing had been prepared, built, compared or bundled yet; the status at the top supersedes this.) The archive file names
in the lock (`mozcdic-ut-<name>.txt.bz2`) follow the upstream naming; if upstream renames them, the fetch fails with an
HTTP error and the lock must be corrected.

### Licence notices: the fetched files are not enough

The `LICENSE` and `README.md` that `fetch` downloads from these UT repositories only *link* to their licences. They
are kept as provenance, but they are **not** full notices and must never be the only notice shipped. Each lock entry
lists `bundleNotices`, the full-text files an adoption must ship:

- Apache License 2.0 full text: `third_party/sudachidict/LICENSE-2.0.txt` (vendored; with
  `third_party/sudachidict/LEGAL` it covers SudachiDict).
- neologd only: the mecab-ipadic-neologd `COPYING`, verbatim, at `third_party/mozc-ut/neologd/COPYING` (vendored),
  from upstream commit `abc61e33d8be3d0ead202e6b1df064c72d5ccf11`. Never retype it; replace it only byte for byte.

All `bundleNotices` hashes are recorded; the maintainer measured them on the vendored files (2026-10-29):

| File | SHA-256 |
|---|---|
| `third_party/sudachidict/LICENSE-2.0.txt` | `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30` |
| `third_party/sudachidict/LEGAL` | `725a8776b38e058b185e905594bc9a2437dbf3787df022fffeefedb9a84e4665` |
| `third_party/mozc-ut/neologd/COPYING` | `428ad012b9b7baf3af430fb730998791da30458e4f88fbcd8ef5ac75a0eed81e` |

A future `bundleNotices` entry with `sha256: null` is computed from the vendored file and recorded at adoption. The
fetch script does not read `bundleNotices`; the adoption checklist below enforces it.

The katakana guarantee for unknown words is separate and needs no data (see the end of this page).

## Pipeline (maintainer action only)

1. **check** – `fetch-ut-dictionaries.py check-latest` prints each source's upstream HEAD commit next to the pin.
   Read-only.
2. **fetch** – `fetch-ut-dictionaries.py fetch` downloads the archive and the notice files (LICENSE, README.md) at a
   40-hex commit from raw.githubusercontent.com into a new `<cache>/<name>/<revision>/` directory, decompresses
   the bz2 with Python's stdlib (size-capped), and writes `source.properties` with every SHA-256. A pinned source
   must match the lock's archive hash. Any other revision is an *unpinned candidate*: its hash is printed and must be
   reviewed and written into the lock by hand. The script never edits the lock and never overwrites a directory.
3. **prepare** – `ossCli ut-prepare` (Kotlin, offline) writes `ut-extra.txt`, `ut-provenance.txt`,
   `notices/<source>/*` and, last, `ut-summary.txt` into a new directory. `--check-only` writes nothing.
4. **build** – `ossCli build` with the shipped extras plus `ut-extra.txt` into a separate directory.
5. **compare** – `ossCli compare --old <current build> --new <candidate>` on the existing regression fixtures and
   `data/dictionary-probes.tsv`. Exit code 3 when more fixtures regress than `--max-regressions` (default 0).
6. **adopt** – only by hand, after review (below).

`scripts/update-ut-dictionaries.ps1` chains 1–5 into a new stage directory. Any failure stops the script. Nothing
outside the stage directory changes, so the previous dictionary stays in use. Nothing runs on a schedule, and the
app has no internet permission. Updating is always this explicit command plus a new APK.

### Commands (PowerShell, from the repository root)

`$imeTools` is the toolchain root (`-ToolchainRoot`, see [BUILDING.md](../BUILDING.md)) and `$py` the caller-provided
Python (any CPython 3.8+; stdlib only). `$mozc` is the pinned dictionary_oss directory (verify it with
`fetch-mozc-data.ps1 -VerifyOnly -CacheDir`). `$english` is the shipped SudachiEnglish file (`english-20261015.txt`,
SHA-256 in `third_party/sudachidict/README.md`). `$current` is the compiled directory that matches
`android/app/oss-assets.lock`.

```powershell
# Monthly check (network, read-only)
& ./scripts/update-ut-dictionaries.ps1 -Python $py -CheckLatest

# Fetch the pinned sudachidict into the cache (network). An existing fetch at the same revision can be reused by
# passing -SourceDirs, provided it contains a source.properties in the format written by this script.
& $py -I scripts/fetch-ut-dictionaries.py fetch --lock third_party/mozc-ut/sources.lock.json --source sudachidict --cache .astra/local/oss-ut
& $py -I scripts/fetch-ut-dictionaries.py verify --dir .astra/local/oss-ut/sudachidict/13129ddebe1cb8eabb63feccbe157adec4e1de01

# Counts only (offline)
& ./scripts/update-ut-dictionaries.ps1 -ToolchainRoot $imeTools -MozcDir $mozc -PriorExtra $english -CheckOnly

# Stage, build and compare (offline); writes .astra/local/oss/staged/ut-<timestamp>/
& ./scripts/update-ut-dictionaries.ps1 -ToolchainRoot $imeTools -MozcDir $mozc -PriorExtra $english -CurrentResources $current

# Same steps by hand
$sha = '07a05a268c3783b7e02b36e4aa591555a2dd19a1f3c93891576740761c154f2d'  # id.def in third_party/mozc/data-lock.json
& ./scripts/build-android.ps1 -ToolchainRoot $imeTools -Tasks ':oss-converter:ossCli',"--args=ut-prepare --mozc-dir $mozc --id-def-sha256 $sha --source <srcdir> --prior-extra $english --out <stage>/ut",'-PossHeap=4g'
& ./scripts/build-android.ps1 -ToolchainRoot $imeTools -Tasks ':oss-converter:ossCli',"--args=build --mozc-dir $mozc --extra $english --extra <stage>/ut/ut-extra.txt --out <stage>/compiled",'-PossHeap=4g'
& ./scripts/build-android.ps1 -ToolchainRoot $imeTools -Tasks ':oss-converter:ossCli',"--args=compare --old $current --new <stage>/compiled --fixtures android/core/src/test/resources/feedback.tsv --fixtures data/dictionary-probes.tsv --limit 5"
& ./scripts/build-android.ps1 -ToolchainRoot $imeTools -Tasks ':oss-converter:ossCli',"--args=bench --resources <stage>/compiled --fixtures data/dictionary-probes.tsv --synthetic 200 --synthetic-max-len 32"
```

Several sources: `-Sources sudachidict,personal-names` (each needs a pinned revision) or `-SourceDirs a,b`. The
order matters: for the same (reading, surface), the earlier source wins.

## Decisions in `ut-prepare` (`UtDictionaryImport.kt`)

- **Ids.** UT rows use `0000`, and id 0 is BOS/EOS. A row whose left or right id is not 0 is rejected: an id from
  another Mozc revision means something else. Each source declares a POS (`general-noun` / `person-name`), and the
  id is the single pinned id.def line with exactly that name (it fails if the name is missing or ambiguous). id.def
  must have the SHA-256 recorded in `third_party/mozc/data-lock.json`. Independently, `ossCli build` now refuses
  id 0 in any `--extra` file, so raw UT rows can never enter the engine.
- **Duplicates.** A (reading, surface) pair that Mozc (dictionary + suffix) or a prior extra already has is skipped,
  and the base cost stays. Across UT sources the earlier one wins; within one source the cheapest row wins. Every
  skip is counted (`duplicateOfBase`, `duplicateOfPriorExtra`, `duplicateOfEarlierSource`,
  `duplicateWithinSource`). `kept` counts unique pairs added, and `read = kept + duplicates + rejected` holds per
  source.
- **Cost.** `max(source cost, --cost-floor 8000)`, and at least Mozc's cheapest cost for that reading +
  `--relative-penalty 1000`. This is a heuristic that *tends* to place rare words after the ordinary words of the same
  reading; it is not a guarantee. Only the word cost is raised, and the lattice also adds POS connection costs, so a
  rare word can still outrank an ordinary one in some contexts. Raised rows are counted (`raisedToFloor`,
  `raisedRelative`). Sudachi UT costs (e.g. 8500/8999) are mostly above the floor already. These numbers are a
  conservative starting point, not tuned: the `compare` regression gate decides.
- **Spellings.** Nothing is normalized or rewritten. These surfaces are rejected and counted: non-NFC (`NOT_NFC`);
  control, format (e.g. zero-width), private-use, unassigned, U+FFFD or non-ASCII whitespace (`UNICODE`); leading or
  trailing spaces, or more than 48 UTF-16 units (`SURFACE`). Readings must be convertible hiragana (≤ 32 chars). Lines
  must have exactly 5 columns (≤ 512 chars), and costs must be 0..32767. A BOM, invalid UTF-8, a text that does not
  match its hash, a row count that differs from the lock, or rejects above `--max-reject-percent` (default 1) stop
  the whole run (a likely format change). The Unicode check uses the build JDK's character database, which the
  provenance records.
- **Determinism.** The output is sorted by reading, then surface, with LF endings and no BOM. Its SHA-256 is in the
  provenance and printed. The same inputs and JDK give identical bytes (tested).
- **Provenance.** `ut-provenance.txt` lists, per source: the repository, revision, archive and text SHA-256, licence,
  notice hashes, POS name, the id it resolved to and the kept count. It also records the id.def hash, the prior extra
  hashes and the policy. The notices are copied unmodified under `notices/<source>/`.

### Why not upstream's Wikipedia costs

Upstream `merge-ut-dictionaries` recalculates costs from Wikipedia title frequencies. That needs a large extra
download and a second data source with its own licence (CC BY-SA). This pipeline uses the floor and relative penalty
instead, and gates on `compare`. If the gate shows names ranked too low, the conservative alternative is to lower
`--cost-floor` in steps of 500 and re-run compare. Wikipedia-based costs would be a separate, explicitly approved
task.

## Adoption checklist (after a passing compare)

1. Review `ut-summary.txt` (rejects, duplicates and kept per source) and the compare output (`common` must not
   regress; check the `changed` lines by eye). Run `bench` and note the load time, heap and p95 next to the current
   build's (host JVM only).
2. Notices. For every adopted source, each `bundleNotices` file in the lock must exist and match its SHA-256
   (record the hash where it is `null`); otherwise stop. Bundle
   those full-text files via `renameLicenses` in `android/app/build.gradle.kts`. The link-only `notices/<source>/*`
   (LICENSE, README.md) may be copied to `third_party/mozc-ut/<source>/` unmodified as provenance, but never in place
   of a full notice. Add the source, revision, licence and the `ut-extra.txt` SHA-256 to
   `android/app/src/main/assets/licenses/THIRD-PARTY.md` and `third_party/mozc-ut/README.md`.
3. Pin any newly fetched revision and archive hash in `sources.lock.json`.
4. Replace `android/app/oss-assets.lock` with `oss-assets.lock.candidate` from the stage, bump versionCode and
   versionName, and build with `build-android.ps1 -OssResources <stage>/compiled` as in [BUILDING.md](../BUILDING.md)
   section 4. Since 1.0.0 the release APK is built unsigned (`package-android.ps1 -Version <version> -Release
   -RequireOss -RequireZenz`) and signed with the release key afterwards (BUILDING.md section 5).
5. Run the full test list and check on a device. Record the results with the release (release notes in
   `docs/releases/`).

Until step 4 nothing in the APK changes. If any step fails, keep the current lock and assets.

## Update cadence

Recommended: run `-CheckLatest` monthly. When an upstream revision changes, fetch it as a candidate and go through
prepare → build → compare → adopt. A new upstream commit does not mean new vocabulary: UT repositories are rebuilt
from their own sources (SudachiDict, NEologd, …), which may themselves be months or years old (as far as known, mecab-ipadic-neologd
has not been updated for years; check its repository). Do not describe a refreshed build as covering "current" terms without checking probes for
them.

## Literal choices (no data needed)

`:core` `LiteralChoices` runs last on every candidate list: after zenz, learned phrases and predictions in
`ConversionWorker`, and in `RepairSession.apply` for a focused repair unit. It guarantees two choices within the
20-candidate cap:

- the typed text as is (raw hiragana; it also has its own chip), and
- its full-width katakana, a per-character mapping of U+3041..U+3096 to U+30A1..U+30F6. ー, ASCII, punctuation,
  emoji and existing katakana/kanji are unchanged. Nothing is romanized or translated.

The katakana is placed at index ≤ 3 (`KATAKANA_SLOT`), never at index 0. A candidate already in that range keeps its
place. The lowest-ranked other candidates are dropped to make room, the top one last: at the production cap of 20 the
top candidate always stays first. Only with a cap below 3 (raw and katakana distinct) do the literal choices take
every slot and drop it; `ensure` allows caps down to 2, but the app's callers (`ConversionWorker`, `RepairSession.apply`) both use 20. It carries LITERAL
segments per hiragana run and PASSTHROUGH segments for the rest, so repair units stay aligned. Nothing is
auto-confirmed. English spellings still come only from dictionary data (Mozc, SudachiEnglish, UT), never from
invented romanization.

# Standard Mozc dictionary source

Source: [google/mozc](https://github.com/google/mozc), revision and individual SHA-256 values in `data-lock.json`. The original notices are preserved as `LICENSE` and `DICTIONARY-NOTICES.txt`. Any compiled dictionary distribution must include both. No changes to source vocabulary, word costs or context IDs are intended.

`scripts/fetch-mozc-data.ps1` downloads/verifies the pinned 10 dictionary shards, suffixes, context IDs, connection matrix and notices into ignored `.astra/local/oss/mozc`. `-VerifyOnly` performs offline integrity checks. Data files are deliberately not added to Git. This does not invoke an upstream build script or GitHub Actions.

Initial inspection counted **1,290,226 records** including 1,150 suffix records. This is not a count of unique words: inflections and alternate surfaces/POS entries are included. 62 rows have an optional sixth field; a compiler must inspect and handle it explicitly. The matrix has dimension 2672. Dictionary costs range from 0 to 18318 and context IDs through 2671.

## Rewriter data (0.1.20+)

`supplement-lock.json` pins six more files of the same revision: `src/data/symbol/{symbol,categorized}.tsv`,
`src/data/emoticon/{emoticon,categorized}.tsv`, `src/data/emoji/emoji_data.tsv` and `src/data/single_kanji/single_kanji.tsv`
(BSD-3-Clause, the `LICENSE` above; no further notice in those directories). `scripts/fetch-mozc-supplement.ps1` downloads and
verifies them into ignored `.astra/local/oss/mozc-supplement`; `ossCli supplement-prepare --data-dir <that dir> --out
android/app/src/main/assets/supplement/mozc-supplement.tsv` rearranges them, keyed by reading, into the committed table read by
`dev.astra.ime.core.Supplement`. Keys and characters are Mozc's; only two group keys are added (かおもじ: every emoticon, えもじ:
every emoji), as Mozc's rewriters answer to them. Like Mozc, the app offers these only for a whole reading, outside the dictionary
and lattice, so no dictionary cost or ranking changes. Used columns: readings (symbol.tsv, emoji_data.tsv), keys (emoticon.tsv) and the kana `description` column of the two
`categorized.tsv` files, which holds readings; symbol.tsv's kanji descriptions, Unicode names and categories are not used.

The upstream notes distinguish this OSS data from the Google Japanese Input proprietary dictionary. It includes IPAdic/NAIST/ICOT and Okinawa notices; code-level BSD terms alone do not describe every data condition. We are not using the expanded UT dictionary, scraped vocabulary, optional English/ngram datasets, or model weights in this step.

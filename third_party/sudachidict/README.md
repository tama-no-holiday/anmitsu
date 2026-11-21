# SudachiDict (Latin spellings only)

Source: https://github.com/WorksApplications/SudachiDict (Apache License 2.0; `LEGAL` lists the UniDic and NEologd
notices). Raw lexicons, release 20261015, V1 format, from
http://sudachi.s3-website-ap-northeast-1.amazonaws.com/sudachidict-raw/v1/20261015/ (downloaded 2026-10-27 into the
ignored `.astra/local/oss/sudachi/20261015/`):

| file | bytes | sha256 |
|---|---|---|
| small_lex.zip | 14623322 | 981b78cdc532ce058b34c76cd9acd3c9a6c771cc0ce528d7de92424b21172125 |
| core_lex.zip | 21934496 | e9ac06222f0ce401a0fef9b129e61e93d59e3ff395a1b037f498962ee5b8ad8b |
| notcore_lex.zip | 38201456 | 16072d75abe6a048947aacbcecfe1ab65a200965a9f1ddb7ceab366348fa21f2 |

`LEGAL` and `LICENSE-2.0.txt` are copied unmodified from the repository's `develop` branch.

## What is used

`ossCli english` (`android/oss-converter/.../SudachiEnglish.kt`) keeps only noun entries whose surface is a Latin
spelling (Google, Claude, iPhone, ...) and whose reading is katakana, and writes them as an extra Mozc-format source
(`english-20261015.txt`, 92,423 rows, sha256 e520c970c49c34eddea017df5add37b39df8977ca54494cb8993d48c61f04734). That hash
was verified by the maintainer against the actual shipped file; the value recorded here before
(`4adc8e44…e0e584`, also in `reports/android-trial-0.1.7.json`) did not match the file and is stale. Rows are
copied, not invented; the extractor only maps the POS to a Mozc id and places the cost relative to Mozc's katakana entry
for the same reading (see the KDoc). Nothing else from SudachiDict is used, and the Sudachi analyzer is not used.

The compiled resources that the app bundles are built from Mozc dictionary_oss plus that file
(`ossCli build --extra`); see `docs/oss-converter-integration.md`.

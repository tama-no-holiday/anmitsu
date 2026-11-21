# Mozc-UT vocabulary (adopted in 0.1.12-trial)

Rows from three publicly distributed Mozc-UT dictionaries are added to the shipped OSS dictionary as one extra
Mozc-format source (`ut-extra.txt`, built by `ossCli ut-prepare`; see `docs/dictionary-update.md`). Mozc's own
entries and costs are unchanged; only the added rows get the policy cost below.

| source | repository | revision | licence | kept pairs |
|---|---|---|---|---|
| sudachidict | https://github.com/utuhiro78/mozcdic-ut-sudachidict | `13129ddebe1cb8eabb63feccbe157adec4e1de01` | Apache-2.0 | 905,712 |
| neologd | https://github.com/utuhiro78/mozcdic-ut-neologd | `d8307abf02b830b185c9320822cffa0d0787c54e` | Apache-2.0 + mecab-ipadic-neologd COPYING | 499,602 |
| personal-names | https://github.com/utuhiro78/mozcdic-ut-personal-names | `b557dd4c1548cc7c2164d5f8b14170c40cbb6f81` | Apache-2.0 | 53,686 |

- `ut-extra.txt`: 1,459,000 rows, SHA-256 `fe97d71efde491fcba541f0c457b1436f170e13f582c223bdbef283313914f59`
  (not committed; reproducible from the pinned archives in `sources.lock.json` with the command in
  `docs/dictionary-update.md`).
- Policy: `--cost-floor 11000 --relative-penalty 1000` (all added rows were raised to the floor). Chosen over the
  default 8000 because 8000 regressed one probe (芥川龍之介 → 芥川竜之介 first) and 11000 regressed none, with the
  same vocabulary and the same two improvements (2026-10-29, `compare` on feedback.tsv + dictionary-probes.tsv).
- `ut-provenance.txt` and `ut-summary.txt` are the unmodified outputs of that run (archive/text/notice hashes, POS
  ids, counts, rejects).
- Notices shipped in the APK (`assets/licenses/`): `SudachiDict-LICENSE-2.0.txt` (Apache 2.0 full text, also covers
  the UT repositories), `SudachiDict-LEGAL.txt`, and `mecab-ipadic-neologd-COPYING.txt` = `neologd/COPYING` (verbatim,
  SHA-256 `428ad012b9b7baf3af430fb730998791da30458e4f88fbcd8ef5ac75a0eed81e`).

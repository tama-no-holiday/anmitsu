# 第三者の部品・データ・モデル（anmitsu 1.0.0）

この一覧は、リポジトリの記録（`third_party/`、ロックファイル、アプリ内の `android/app/src/main/assets/licenses/THIRD-PARTY.md`）をまとめたものです。法的な確認を経たものではありません。各部品の条件は、元のライセンス文が優先します。ライセンスの種類を書いていることは、その部品を使うことが法的にすべて問題ないという意味ではありません。

## あんみつ自身のライセンスの範囲

あんみつ自身のコード・文書・手作りのデータ（`data/`、`assets/lexicon.tsv` など）は [MIT License](LICENSE)（Copyright (c) 2026 anmitsu contributors）です。

このプロジェクトの `LICENSE` は、下の表と「ソースにあるもの・ないもの」に挙げた第三者の部品・辞書・モデルと、それらから作ったデータを**対象としません**。それらは、それぞれ元のライセンスと表記に従います。元のライセンスが MIT の部品（kotlin-kana-kanji-converter、llama.cpp / ggml など）も、このプロジェクトの `LICENSE` ではなく、その部品自身の MIT License と著作権表記に従います。このプロジェクトの `LICENSE` が第三者の条件を置き換えることはありません。このプロジェクトが加えた修正（`PATCHES.md` に記録したもの）も、元の部品のライセンスに従って扱います。

## APKに入るもの

| 部品 | 版・revision | ライセンス | APK内の表記 | 由来の記録 |
|---|---|---|---|---|
| Kotlin 標準ライブラリ | 2.2.20 | Apache-2.0 | `Kotlin-LICENSE.txt`、`Kotlin-NOTICE.txt` | `assets/licenses/THIRD-PARTY.md` |
| JetBrains annotations | 13.0 | Apache-2.0 | 同上 | 同上 |
| kotlin-kana-kanji-converter（変換の部品、KAZUMA NAKA） | `13e391bc7a4a245f63aa0091c1e9c11db3dbd759`、`TokenArray.kt` に記録した修正2件 | MIT | `kotlin-kana-kanji-converter-LICENSE.txt`、`-PATCHES.md` | `third_party/kotlin-kana-kanji-converter/` |
| Mozc 辞書データ（`dictionary_oss`） | google/mozc `d766d25d253141eff71e25c593aaf753f8d91cee` | BSD-3-Clause と辞書データの各表記（IPAdic、NAIST、ICOT、沖縄 ほか） | `Mozc-LICENSE.txt`、`Mozc-DICTIONARY-NOTICES.txt` | `third_party/mozc/` |
| Mozc の記号・顔文字・絵文字・単漢字データ（`assets/supplement/mozc-supplement.tsv`） | 同じ revision | BSD-3-Clause | `Mozc-LICENSE.txt` | `third_party/mozc/supplement-lock.json` |
| SudachiDict（英字表記の名詞 92,423 行） | raw 辞書 release 20261015 | Apache-2.0、UniDic・NEologd の表記 | `SudachiDict-LICENSE-2.0.txt`、`SudachiDict-LEGAL.txt` | `third_party/sudachidict/` |
| Mozc-UT 辞書（utuhiro78。sudachidict・neologd・personal-names、合計 1,459,000 行） | 各 revision は `third_party/mozc-ut/README.md` | Apache-2.0。neologd は mecab-ipadic-NEologd の COPYING も | `SudachiDict-LICENSE-2.0.txt`（Apache-2.0 全文）、`mecab-ipadic-neologd-COPYING.txt` | `third_party/mozc-ut/` |
| 英単語の候補（`assets/english/english-words.tsv`） | 上の SudachiDict と Mozc-UT の表記から作成 | 元データと同じ | 同上 | アプリ内の `THIRD-PARTY.md` |
| zenz-v3.2-xsmall Q5_K_M GGUF（Miwa-Keita） | Hugging Face revision `4f5423f0fad41a73b1242eb96fe5c12ae4fdca83`、無変更（現在の表示の revision `42cab8392a3727d1cf80932cf2275b7d523fbb9f` も同じファイル） | 無変更のモデルには現在の上流の表示 CC BY-SA 4.0 を採用（下の「zenz モデルのライセンス表示の記録」）。取得した revision の README の表示は apache-2.0 | 以前の署名済みAPK: `zenz-LICENSE-Apache-2.0.txt` のみ。直したソースと現在の候補: `zenz-MODEL-NOTICE.txt`（と記録としての `zenz-LICENSE-Apache-2.0.txt`） | `third_party/zenz/README.md` |
| llama.cpp / ggml | v0.7.0、無変更で `libastra_zenz.so` に組み込み | MIT。同梱の nlohmann/json の表記も | `llama.cpp-LICENSE.txt`、`llama.cpp-LICENSE-jsonhpp.txt` | `third_party/zenz/` |
| llamafile sgemm（llama.cpp v0.7.0 の `ggml/src/ggml-cpu/llamafile/sgemm.cpp`） | v0.7.0、無変更で `libastra_zenz.so` に組み込み（`GGML_LLAMAFILE` 有効、`llamafile_sgemm` を export） | MIT（Copyright 2024 Mozilla Foundation） | 以前の署名済みAPK: なし。直したソースと現在の候補: `llamafile-LICENSE.txt` | `third_party/zenz/README.md` |
| YaRN（llama.cpp v0.7.0 の `ggml/src/ggml-cpu/ops.cpp` がアルゴリズムの出典として https://github.com/jquesnelle/yarn を記載） | v0.7.0、無変更で `libastra_zenz.so` に組み込み（ソースの記載との関係のみ確認。シンボルごとには確かめていない） | MIT（Copyright (c) 2023 Jeffrey Quesnelle and Bowen Peng） | 以前の署名済みAPK: なし。直したソースと現在の候補: `yarn-LICENSE.txt` | `third_party/zenz/README.md` |
| Unicode のデータから作った表（llama.cpp v0.7.0 の `src/unicode-data.cpp`） | v0.7.0、無変更で組み込み。正規化の表のコメントは Unicode 16.0.0、主な表の版は固定されていない | Unicode License V3（安全側の表記として追加。表の由来全体は確かめていない） | 以前の署名済みAPK: なし。直したソースと現在の候補: `Unicode-LICENSE.txt` | `third_party/zenz/README.md` |
| アイコンの「あ」の字形 | Noto Sans CJK JP Bold の輪郭をベクターにしたもの。フォントファイルは入っていません | 下の「アイコンとフォントのライセンス」 | なし | `design/icon/anmitsu-icon/README.md` |
| 内蔵の小さな辞書（`assets/lexicon.tsv`） | `data/lexicon.json` から作成 | このプロジェクトの手作りデータ（MIT） | ― | `data/README.md` |

APK に入らないもの: JUnit、Robolectric、AndroidX のテスト部品（開発用）、ビルドツール（JDK、Gradle、Android SDK、NDK、CMake、Ninja）。

**以前の署名済みAPKと、現在の署名済み候補の違い。** 以前の 1.0.0 APK（SHA-256 `2f4543989165543229f1a1a9a27a70c50af98569b8c8d1fc72dc7b96b3ca612b`）は変えずに記録として残しており、現在の候補ではありません。その表記ファイル13件には、llamafile sgemm の Mozilla Foundation の表記も、CC BY-SA の文や URL も入っていません。ソースでは `llamafile-LICENSE.txt` と `zenz-MODEL-NOTICE.txt`、さらに `yarn-LICENSE.txt` と `Unicode-LICENSE.txt` を `assets/licenses/` に加え、アプリ内の `THIRD-PARTY.md` を直しました（下の 1・7）。コミット `f45c29e` 以降のソースから作った署名していないAPK（`7790bcfc…`、表記ファイル17件）に同じ鍵で署名したものが、現在の署名済み候補（SHA-256 `636d13fbc2dd280e7c2c79221c968df68697507cbcb87080af06176ecc0690a4`）です。まだ公開しておらず、実機での確認もまだです（[docs/releases/v1.0.0-validation.md](docs/releases/v1.0.0-validation.md) の E.4）。候補に入っているアプリ内の表記は `f45c29e` 時点のもので、この文書（ルートの `THIRD-PARTY.md`）をあとで直しても候補APKは変わりません。直したソースでは表記・パッケージ化・テストが変わりますが、アプリの動作は変わりません。モデル・`libastra_zenz.so`・辞書のファイルとハッシュは変えていません。

## ソースにあるもの・ないもの

Git のソースに入っている第三者のもの:

- `third_party/kotlin-kana-kanji-converter/upstream/`: 上の変換部品のソース（MIT、`upstream/LICENSE`）。`dictionary/DicUtils.kt` と `single_kanji/` はビルドから外しています。
- `third_party/*/` のライセンス文と表記: 無変更の写しです（ハッシュは各ロックファイル）。
- `assets/supplement/mozc-supplement.tsv`（Mozc 由来）と `assets/english/english-words.tsv`（SudachiDict・Mozc-UT 由来）。
- `design/icon/anmitsu-icon/`: アイコンの元データ（字形は輪郭にしたもので、フォントファイルは含みません）。

Git のソースに入っていないもの（APKにだけ入る）: 組み立てた Mozc・Mozc-UT・SudachiDict の辞書、zenz のモデル（GGUF）、llama.cpp を組み込んだ `libastra_zenz.so`。取得と組み立ての手順は [BUILDING.md](BUILDING.md) にあります。

## アイコンとフォントのライセンス

アイコンは、このプロジェクトが作った部分（3色の下線、カーソル、色、配置）と、Noto Sans CJK JP Bold（SIL OFL 1.1）の字形を輪郭にした「あ」からできています。APKにもソースにも、フォントファイルは入っていません。

- このプロジェクトが作った部分は MIT です。
- 「あ」の字形は Noto Sans CJK JP に由来するもので、このプロジェクトの著作物とは主張しません。MIT はこの字形のデザインそのものに権利を与えるものではありません。
- OFL の公式FAQ（[OFL-FAQ](https://openfontlicense.org/ofl-faq/)）は、フォントを使って作った画像やロゴ（字形を輪郭にしたものを含む）について、できた作品に OFL は及ばないとしています。このため、アイコンの画像に OFL のライセンス文は付けていません。この判断はこの一点だけのもので、アイコンのそのほかの権利（商標など）については結論を出していません。

## zenz モデルのライセンス表示の記録（2026-11-23 確認）

事実の記録で、法的な結論ではありません。

| 対象 | 確認したもの | 表示 |
|---|---|---|
| Miwa-Keita/zenz-v3.2-xsmall-gguf、取得した revision `4f5423f0fad41a73b1242eb96fe5c12ae4fdca83`（APK のモデルはこの revision） | その revision の README（Hugging Face から取得） | `license: apache-2.0` |
| 同じリポジトリの現在の main、revision `42cab8392a3727d1cf80932cf2275b7d523fbb9f` | Hugging Face API と、その revision の README | `cc-by-sa-4.0` |
| 同じリポジトリの公式ページ https://huggingface.co/Miwa-Keita/zenz-v3.2-xsmall-gguf | 表示されたライセンス | CC BY-SA 4.0 |
| ku-nlp/gpt2-small-japanese-char の公式モデルカード https://huggingface.co/ku-nlp/gpt2-small-japanese-char | 現在の表示 | CC BY-SA 4.0 |

- 上流の表示は、取得した revision（apache-2.0）と現在の main（CC BY-SA 4.0、2026-11-07 のコミット `42cab839…` で変更）で違います。どちらの revision のファイルも同じです（LFS の SHA-256 `00c64b3d318045a708d0cad5434faccab10f5481a49e6362864551fd0995fa58`、20,970,304 バイト）。この記録は、以前の表示による許諾が取り消されたとも、あんみつのアプリのコードが CC BY-SA になるとも主張しません。
- ソースでは、無変更のモデルに現在の上流の表示 CC BY-SA 4.0 を採り、`third_party/zenz/zenz-MODEL-NOTICE.txt`（作者 Miwa-Keita、モデル名、Hugging Face の出典、取得した revision と現在の表示の revision、SHA-256、無変更での再配布、https://creativecommons.org/licenses/by-sa/4.0/ と https://creativecommons.org/licenses/by-sa/4.0/legalcode）を APK に入れるようにしました。この表記はモデルのライセンスだけを示すもので、このプロジェクトの MIT License がそれを置き換えることはなく、MIT License も変わりません。
- `zenz-LICENSE-Apache-2.0.txt` は、取得した revision の表示の記録として残します。モデルの現在のライセンスでも、それだけで足りる表記でもありません。
- 以前の署名済み 1.0.0 APK（`2f454398…`）に入っているモデルの表記は `zenz-LICENSE-Apache-2.0.txt`（Apache-2.0 の全文）だけです。直した表記は現在の署名済み候補（`636d13fb…`）に入っており、利用者に届くのはその公開からです（まだ公開していません）。
- ku-nlp/gpt2-small-japanese-char と、同梱している xsmall モデルの関係は確かめていません。トークナイザーの名前（`gpt2-small-japanese-char`）や small モデルのメタデータだけからは判断しません。

## 表記ファイルと上流の照合（2026-11-23 確認）

- 次の4件は、固定した上流のファイルとバイト単位で同じでした: Mozc の `LICENSE`、Mozc 辞書の README（`DICTIONARY-NOTICES.txt` として同梱）、NEologd の `COPYING`（コミット `abc61e33d8be3d0ead202e6b1df064c72d5ccf11`）、変換部品の `LICENSE`（コミット `13e391bc7a4a245f63aa0091c1e9c11db3dbd759`）。
- Mozc-UT の bundle の表記のうち重複を除いた3件（Sudachi の LEGAL、Apache-2.0 の本文、NEologd の COPYING）は、ロックに記録したハッシュと一致しました。
- 確かめたのはこれらのファイルだけです。依存関係の全体の監査（下の 3・5）や、各条件を満たしているかの確認（4・6）の代わりにはなりません。

## 確認が終わっていない点

どれもこの文書では結論を出していません。それぞれ、ソースの公開と公式APKの配布のどちらに関係するかを書いています。

| # | 内容 | ソースの公開 | APKの配布 |
|---|---|---|---|
| 1 | **アプリ内の表記の古い記述**: 以前の署名済みAPKの `assets/licenses/THIRD-PARTY.md` は、変換部品と辞書について「Experimental OSS converter (debug builds only, off by default)」、変換部品の修正を「one recorded local patch」と書いています。1.0.0 ではどのビルドでも既定で使い、記録した修正は2件です。ソースのファイルはこの2点とモデル・llamafile の表記を直しました。 | 直した（ソース） | 以前のAPK（`2f454398…`）には残る。現在の署名済み候補（`636d13fb…`）では直っている（未公開） |
| 2 | **zenz モデルの学習データ**: モデルカードに学習データの説明がありません。small の GGUF のメタデータは ku-nlp の gpt2-small-japanese-char を基のモデルとしていますが、同梱している xsmall との関係は確かめていません（ku-nlp のモデルカードの現在の表示は CC BY-SA 4.0）。基のモデルと学習データの条件は確かめていません。Hub のライセンス表示は、学習データの来歴を示すものではありません。 | ほぼ関係しない（モデルはソースに入っていない。入っているのは読み込むコードと記録だけ） | 関係する（モデルを同梱して配布する） |
| 3 | **llama.cpp の同梱部品**: `libastra_zenz.so` に入る llama.cpp / ggml の部品のうち、llamafile sgemm（Mozilla Foundation の表記が必要。以前の署名済みAPKにはなく、ソースと現在の候補で `llamafile-LICENSE.txt` を加えた）が見つかりました。さらに YaRN（`ops.cpp` が出典として記載。`yarn-LICENSE.txt`）と Unicode のデータから作った表（`unicode-data.cpp`。安全側の表記として `Unicode-LICENSE.txt`）をソースに加えました。nlohmann/json・llamafile sgemm・YaRN・Unicode 以外に別の表記が必要なものがないかは、一つずつは確かめていません。ネイティブの依存関係の監査は終わっていません。 | 関係しない（llama.cpp のソースも `.so` もソースに入っていない） | 関係する（`.so` を同梱して配布する） |
| 4 | **Mozc-UT の元データ**: 固定した3つのUTリポジトリの LICENSE を取得して確かめました。sudachidict（`13129dde…`）は WorksApplications/SudachiDict の LEGAL への「Apache License Version 2.0」のリンクだけ、neologd（`d8307abf…`）は neologd/mecab-ipadic-neologd の COPYING へのリンクだけ、personal-names（`b557dd4c…`）は「Apache License Version 2.0」の文字列だけで、ほかの著作者・著作権の行はありませんでした。そのため、これらの LICENSE のハッシュが Apache-2.0 全文と違うことは、表記が欠けている証拠ではありません。指す先の SudachiDict の LEGAL、Apache-2.0 全文、NEologd の COPYING はすでに同梱しており、同じ内容の写しを増やす必要はありません。固定した README から、UT sudachidict は SudachiDict から、UT neologd は mecab-ipadic-NEologd から作られていることを確かめました。personal-names のさらに元のデータは上流で明かされておらず不明ですが、それは由来が分からないということで、再配布の表記が欠けていると分かったわけではありません。具体的な義務が見つからない限り、作者への問い合わせを前提条件にはしません。これは法的に問題がないと全面的に保証するものではありません。 | 関係する（ソースの `english-words.tsv` は Mozc-UT の表記も使っている） | 関係する（組み立てた辞書を同梱して配布する） |
| 5 | **依存関係のライセンスの全体の監査**: 公式APK（versionCode 1）のハッシュ・パッケージ名・版・署名者を確かめ、APKの `assets/licenses/` にある表記ファイル13件を一覧にしました（その時点の表の12ファイルと `THIRD-PARTY.md`。直したソースでは `llamafile-LICENSE.txt` と `zenz-MODEL-NOTICE.txt` が加わり、署名していない確認用のビルド（`44259e3e…`）で15件を確かめました。そのあと `yarn-LICENSE.txt` と `Unicode-LICENSE.txt` を加えたソース（コミット `f45c29e` 以降）から署名していない確認用のAPK（`7790bcfc…`）を作り直し、17件を確かめました。`44259e3e…` と比べて加わったのは `yarn-LICENSE.txt` と `Unicode-LICENSE.txt`、変わったのは `assets/licenses/THIRD-PARTY.md` だけで、なくなったものはありません。詳しくは [docs/releases/v1.0.0-validation.md](docs/releases/v1.0.0-validation.md) の E.3。これに署名した現在の候補 `636d13fb…` も17件で、署名で加わった `META-INF/` 以外は同じです（E.4））。これは表記ファイルの列挙で、APKにコンパイルされたすべての依存関係を一つずつ監査したものではありません（`scripts/package-android.ps1` が確かめるのも、主な表記ファイルがそろっていることだけです）。 | 一部（Gradle の依存はソースに入っていないが、`third_party/` の写しは全体を照合していない） | 関係する |
| 6 | **Mozc 辞書データの各表記**: IPAdic・NAIST・ICOT・沖縄などの表記はそのまま同梱していますが、各条件を満たしているかを一つずつは確かめていません。 | 一部（`mozc-supplement.tsv` は BSD-3-Clause の部分だけを使う記録） | 関係する |
| 7 | **zenz モデルの配布時の表記**: 取得した revision の表示は apache-2.0、現在の上流の表示は CC BY-SA 4.0 です（上の「zenz モデルのライセンス表示の記録」）。ソースでは、無変更のモデルに CC BY-SA 4.0 を採り、`zenz-MODEL-NOTICE.txt` を加えました。以前の署名済みAPKには、モデルの表記として Apache-2.0 の全文だけが入っています。モデルの学習データ・基のモデルの条件（2）は、この表記では解決していません。 | ほぼ関係しない（モデルはソースに入っていない） | 以前のAPK（`2f454398…`）には残る。現在の署名済み候補（`636d13fb…`）には `zenz-MODEL-NOTICE.txt` が入っている（未公開） |

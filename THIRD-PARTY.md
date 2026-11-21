# 第三者の部品・データ・モデル（anmitsu 1.0.0）

この一覧は、リポジトリの記録（`third_party/`、ロックファイル、アプリ内の `android/app/src/main/assets/licenses/THIRD-PARTY.md`）をまとめたものです。法的な確認を経たものではありません。各部品の条件は、元のライセンス文が優先します。

あんみつ自身のコードのライセンスは未決定です（[README.md](README.md)）。下の部品は、それぞれ元のライセンスのままです。

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
| zenz-v3.2-xsmall Q5_K_M GGUF（Miwa-Keita） | Hugging Face revision `4f5423f0fad41a73b1242eb96fe5c12ae4fdca83`、無変更 | Apache-2.0（Hub のタグ） | `zenz-LICENSE-Apache-2.0.txt` | `third_party/zenz/README.md` |
| llama.cpp / ggml | v0.7.0、無変更で `libastra_zenz.so` に組み込み | MIT。同梱の nlohmann/json の表記も | `llama.cpp-LICENSE.txt`、`llama.cpp-LICENSE-jsonhpp.txt` | `third_party/zenz/` |
| アイコンの「あ」の字形 | Noto Sans CJK JP Bold の輪郭をベクターにしたもの。フォントファイルは入っていません | フォントは SIL Open Font License 1.1。アイコンの画像には OFL を付けない（下の「アイコンとフォントのライセンス」） | なし | `design/icon/anmitsu-icon/README.md` |
| 内蔵の小さな辞書（`assets/lexicon.tsv`） | `data/lexicon.json` から作成 | このプロジェクトの手作りデータ | ― | `data/README.md` |

APK に入らないもの: JUnit、Robolectric、AndroidX のテスト部品（開発用）、ビルドツール（JDK、Gradle、Android SDK、NDK、CMake、Ninja）。

## ソースにだけあるもの

- `third_party/kotlin-kana-kanji-converter/upstream/`: 上の変換部品のソース（MIT、`upstream/LICENSE`）。`dictionary/DicUtils.kt` と `single_kanji/` はビルドから外しています。
- `third_party/*/` のライセンス文と表記: 無変更の写しです（ハッシュは各ロックファイル）。
- `design/icon/anmitsu-icon/`: アイコンの元データ（字形は輪郭にしたもので、フォントファイルは含みません）。

## アイコンとフォントのライセンス

アイコンの「あ」は、Noto Sans CJK JP Bold（SIL OFL 1.1）の字形を輪郭にしてベクターの図形にしたものです。APKにもソースにも、フォントファイルは入っていません。

OFL の公式FAQ（[OFL-FAQ](https://openfontlicense.org/ofl-faq/)）は、フォントを使って作った画像やロゴ（字形を輪郭にしたものを含む）について、できた作品に OFL は及ばないとしています。OFL がかかるのはフォントのソフトウェア自体で、それをこのプロジェクトは配布していません。このため、アイコンの画像に OFL のライセンス文を付ける必要はないと判断し、APKにもソースにも OFL の文を入れていません。

この判断はこの一点だけのものです。アイコンにどのライセンスを付けるか（プロジェクトのライセンスと一緒に未決定）や、アイコンのそのほかの権利については、ここでは結論を出していません。

## 確認が必要な点

公開の前に、次を確認する必要があります。どれも、この文書では結論を出していません。

1. **アプリ内の表記の古い記述**: `assets/licenses/THIRD-PARTY.md` は、変換部品と辞書について「Experimental OSS converter (debug builds only, off by default)」と書いています。1.0.0 ではどのビルドでも既定で使います。公式APKに合わせるため、1.0.0 のファイルは変えていません。
2. **zenz モデルの学習データ**: モデルカードに学習データの説明がありません。メタデータは ku-nlp の gpt2-small-japanese-char を基のモデルとしています。基のモデルと学習データの条件は確かめていません。
3. **llama.cpp の同梱部品**: `libastra_zenz.so` に入る llama.cpp の部品のうち、nlohmann/json 以外に別の表記が必要なものがないかを、一つずつは確かめていません。
4. **Mozc-UT の元データ**: 各UT辞書が何から作られたか（neologd 以外）は、リポジトリに記録していません。
5. **依存関係のライセンスの全体の監査**: 公開の準備として、公式APK（versionCode 1）のハッシュ・パッケージ名・版（1.0.0、versionCode 1）・署名者を確かめ、APKの `assets/licenses/` にある表記ファイル13件を一覧にしました。件数は、この一覧の「APK内の表記」の列にある12ファイルと `THIRD-PARTY.md` の合計と一致します。これは表記ファイルの列挙で、APKにコンパイルされたすべての依存関係とそのライセンスを一つずつ監査したものではありません。そうした監査はまだしていません（`scripts/package-android.ps1` が確かめるのも、主な表記ファイルがそろっていることだけです）。

# モデル・辞書比較（2026-10-26確認）

一次情報はAstraがWebで確認。既存ClaudeラッパーはWeb/MCPを無効化しているため、Claudeの知識だけで最新ライセンスを確定しない。以下は採用候補の調査であり、推論実測や配布承認を意味しない。現PoCには外部モデル・辞書を同梱しない。

| 候補 | 確認できた公開物・条件 | サイズ / メモリの目安 | Android実行案と判断 |
|---|---|---|---|
| zenz v3.2 small | 作者のGGUF公開、Apache-2.0メタデータ、GPT-2系95.1M。README本文は空で学習データの説明は不足 | Q5_K_Mファイル73.9MB。これはRAMではない | llama.cppのNDK/JNI経由を検証する第一候補。読み訂正を別層に保つ。[モデル/ファイル](https://huggingface.co/Miwa-Keita/zenz-v3.2-small-gguf/tree/main) |
| zenz v3.2 xsmall | 作者公開GGUF、Apache-2.0メタデータ、25.6M | Q5_K_Mファイル21MB | smallと同一評価で比較。軽量端末向け第一候補。[モデル](https://huggingface.co/Miwa-Keita/zenz-v3.2-xsmall-gguf) |
| AzooKeyKanaKanjiConverter / Zenzai | Swiftのかな漢字変換器、MIT。モデル・既定辞書は別資産 | コード・辞書・モデルの合計は未測定 | 公式の確認環境はApple系/Ubuntu等。Androidを検証済みと扱わない。Swift層の移植/境界を調査。[README](https://github.com/azooKey/AzooKeyKanaKanjiConverter) / [Zenzai仕様](https://github.com/azooKey/AzooKeyKanaKanjiConverter/blob/main/Docs/zenzai.md) |
| Mozc | GoogleのマルチプラットフォームIME。コードのBSD系条項に加え辞書のNAIST/ICOT等の通知がある | Androidビルドの辞書サイズ・RSSは未測定 | 辞書変換の有力な基準。C++エンジンをJNI等で分離し、別の読み補正層から候補を渡す。[公式](https://github.com/google/mozc) / [LICENSE](https://github.com/google/mozc/blob/master/LICENSE) |
| Jeff | 現公式READMEはv1.3のbase+adapter構成。旧Jeff-Qwen3.5-0.8B v1.2も公開。コードMIT、重みApache-2.0、学習元に複数条件 | 0.8B公称なら4bit重みだけで約400MB、BF16約1.6GB。実物は規模/付帯重みを確認する必要あり | 当面比較対象。選択肢を評価するだけで不足候補を生成できない。旧カードは英語用途、日本語IMEの根拠なし。[公式コード](https://github.com/firelex/jeff) / [旧モデルカード](https://huggingface.co/jeff-legacy/Jeff-Qwen3.5-0.8B) |
| Kev-0.8B | Qwen3.5-0.8B-Base上のLoRA+pointer head。カードは英語、adapter/headとbaseはApache-2.0と記載 | baseの理論重み約400MB/4bitに加え11.3M adapterとhead。配布adapterサイズだけでは推論できない | 日本語への転用評価が必要。選択肢順序・校正・ドメイン外性能も測る。IME初期採用は保留。[作者カード](https://huggingface.co/jaredpalmer/kev-0.8b) |
| Ruri v3 30m | 名称は30mだが公式表は総37M、ModernBERT-Ja、日本語embedding、Apache-2.0 | 重み理論37MB/int8、74MB/fp16 + tokenizer/activation/runtime | 小型encoderの候補。類似度が高くても否定や固有名詞を変える場合があり、そのままIME判断器としない。[作者カード](https://huggingface.co/cl-nagoya/ruri-v3-30m) |
| Ruri reranker small | 日本語cross-encoder、DistilBERT系、Apache-2.0 | 実パラメータ/変換後サイズ未確認 | ONNX Runtime Mobile向け書き出しを検証。検索関連度の学習なのでIME専用の対照データ/蒸留が必要。[作者カード](https://huggingface.co/cl-nagoya/ruri-reranker-small) |
| Recruit日本語typo detector | 99.6M RoBERTa、各文字の誤り種別を出す検出器、CC BY-SA 4.0を継承 | 理論約100MB/int8、約398MB/fp32、作業領域別 | 訂正文生成器ではない。誤り位置priorの比較用。共有条件・モデル/学習データを別途確認。[作者カード](https://huggingface.co/recruit-jp/japanese-typo-detector-roberta-base) |

軽量な日本語誤り訂正について、今回確認できた公開物は上の検出器であり「かな誤入力から意図した文章を生成するスマホ向けモデル」とは異なる。汎用的な校正器は文体や意味を変えるリスクがあるため、自動採用しない。追加候補は一次モデルカードと配布条件が確認できるまで保留する。

## 実行方式

[llama.cpp公式Android手順](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md)はAndroid StudioバインディングとNDKクロスビルドを記載する。これだけではzenz固有のトークナイザ、特殊トークン、スコアAPIまで互換と証明できない。azooKey側の使用revisionと合わせてparityテストが必要。サーバーを起動せずアプリ内推論を想定する。

[ONNX Runtime Mobile](https://onnxruntime.ai/docs/get-started/with-mobile.html)はAndroid/iOS対応。encoderの演算子対応、SentencePiece等の処理、量子化前後の順位一致を確認する。未監査のremote codeは実行しない。固定した短い入力と候補のbatch化を比較する。

[Android公式IMEガイド](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method)に沿ってKotlin/InputMethodService、入力viewと候補view、EditorInfoによる入力種別判定を実装する。通常のUI更新とバックグラウンド推論は別系統にする。

## メモリ見積もりと採否

理論重み容量は `パラメータ数 × bit数 / 8`（十進MB）。量子化scale/metadata、埋め込み、辞書、tokenizer、モデルのワーク領域、候補batch、KV/cache、Java/native heapは別に加算する。ファイルサイズや作者の別機種測定をこのプロジェクトのRAM/遅延実績に転用しない。

zenzの最初の実験では、常駐一個、短い文脈・候補数を固定し、追加RAMの探索予算をsmall 150–300MB、xsmall 60–150MBと仮置きする。これは設計用予算であって測定値・保証ではない。RSS/PSS、cold/warm、p50/p95、発熱後の持続性能で見直す。Jeff/Kevは重み容量だけでも負担が大きく、IME専用小型モデルで不足する場合に限定して比較する。

## 配布前のゲート

コード・辞書・重み・tokenizer・学習/評価データを個別に記録する。URLに加えrevision、SHA-256、原本ライセンス、NOTICE、改変履歴をロックする。Apache表示だけで学習データの由来が確認済みとはしない。この表のモデル・外部辞書は権利/サイズ情報を調査した段階で、まだダウンロード・実行・APK同梱していない。Android試験版のKotlin等のランタイム依存は別途導入し、APKの `assets/licenses/` にライセンス原文と出典を同梱する。

## 2026-10-27 OSS統合へ方針を更新

手作り辞書の増量を製品化の主軸にしない。既存資産の取得・実行を段階的に進めるため、上記の「未取得」は初回調査時点の状態とする。

| 経路 | 一次情報と今回の判断 |
|---|---|
| 公式Mozcエンジン | [公式Androidビルド手順](https://github.com/google/mozc/blob/master/docs/build_mozc_for_android.md)はlibmozc.so、Bazel、NDK r29を記載し、ビルド確認環境はmacOS/Linux。Android UIは廃止済み。優先比較対象だが、今のWindows/JVM環境にすぐ差すライブラリではない。WSL一覧取得は権限エラーで環境未確認。追加OSやビルド基盤を無断で構築しない。 |
| 既存Kotlin変換器＋Mozc標準辞書 | [KazumaProject/kotlin-kana-kanji-converter](https://github.com/KazumaProject/kotlin-kana-kanji-converter) のMITコードを固定revisionで取得。既存の圧縮トライ、接続コスト、経路探索を再利用する。Windows上の既存Kotlin/JDKで検証可能なため最初の統合試験に選択。公式Mozcと同等の機能・精度とは主張しない。 |
| Zenzai/zenz | [AzooKeyKanaKanjiConverter](https://github.com/azooKey/AzooKeyKanaKanjiConverter) はSwift 6.1以降・Apple/Ubuntuの確認環境を記載。Androidへの移植済みとは扱わない。既存zenz GGUFとllama.cppを次のローカル推論候補とし、ニューラル変換の再学習・自作はしない。 |
| 既存Android/NDKの再利用例 | [Sumire](https://github.com/KazumaProject/JapaneseKeyboard) と [C++変換パイプライン](https://github.com/KazumaProject/kana-kanji-conversion-c-plus-plus) にMozc辞書/zenz連携例がある。実装参照候補。アプリ全体・拡張辞書・配布バイナリをそのまま持ち込まず、コード・データ・モデルを個別に確認する。 |

取得済み: Kotlinコード31ファイル（MIT原文・ファイルhash）、Mozc公式標準辞書10分割＋suffix＋id.def＋接続コスト＋原本通知（15ファイル）。約129万は語形・同音語等を含むレコード数。`third_party/` のロックと通知を参照。上流の独自語彙/ATOK名義資源/UT拡張/追加ngramは混ぜていない。モデル重みはこの段階では未取得・未実行。

最初のゲートは実データの読み込み・通常変換・未知語維持・メモリ・遅延の実測。未知語ノード、n-best探索上限、接続コストの扱いに注意する。検証前に標準APKへ有効化しない。通過後にAndroidの非同期アダプタを統合し、さらに誤入力読み候補とzenz評価を分離して追加する。

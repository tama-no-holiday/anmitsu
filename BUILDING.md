# ビルド手順（anmitsu 1.0.0）

ここに書く手順は、開発に使ってきたスクリプトに合わせています。この書き出したソースの複製で、既存のツール・キャッシュ・辞書・AIのファイルを使い、テスト（3と同じタスク）、`assembleRelease`・`lintRelease`、`package-android.ps1` を実行しました。ビルドとパッケージ化は完了し、テストは2件が失敗して未解決です（[docs/releases/v1.0.0-validation.md](docs/releases/v1.0.0-validation.md)）。ツールの準備（2）とネイティブライブラリのビルド（4）は通していません。

## 1. 前提

- **Windows と PowerShell 7 が前提です。** `android/toolchains.lock.json` は Windows 用の JDK・Android コマンドラインツールを固定し、スクリプトは `gradle.bat`・`keytool.exe`・`aapt.exe` を呼びます。ほかのOS向けのスクリプトはありません。
- Gradle Wrapper はありません。`scripts/setup-android.ps1` が、固定したハッシュで確かめた Gradle 8.13 を使います。
- 主な版: JDK 17（Temurin 17.0.20.1）、Gradle 8.13、Android Gradle Plugin 8.11.1、Kotlin 2.2.20、compileSdk/targetSdk 36、minSdk 26、build-tools 35.0.0。
- Gradle の依存（AGP、Kotlin、JUnit、Robolectric）は Google Maven と Maven Central から取得します。依存のハッシュ検証（Gradle の dependency verification）は設定していません。

ツールとキャッシュは、リポジトリ内の `.astra/local/toolchains/` に置きます（`.gitignore` で除外しています）。別の場所を使う場合は、各スクリプトに `-ToolchainRoot <dir>` を渡します。

## 2. ツールの準備（ネット接続が必要）

```powershell
pwsh -NoProfile -File scripts/setup-android.ps1
```

JDK・Gradle・Android コマンドラインツールをダウンロードし、`android/toolchains.lock.json` の SHA-256 と照合します。続いて SDK manager が `platforms;android-36`、`build-tools;35.0.0`、`platform-tools` を入れます。SDK のライセンスへの同意を求められます。

## 3. テストと、辞書・AIなしのビルド

```powershell
pwsh -NoProfile -File scripts/build-android.ps1
```

既定のタスクは `:core:test`、`:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug` です。`:oss-converter:test` は `-Tasks` で指定します。

```powershell
pwsh -NoProfile -File scripts/build-android.ps1 -Tasks ':core:test',':oss-converter:test',':app:testDebugUnitTest',':app:testReleaseUnitTest'
```

注意:

- `build-android.ps1` は最初に `scripts/export-android-lexicon.ps1` と `scripts/export-feedback-cases.ps1` を実行し、`data/` から次の3つのファイルを書き直します: `android/app/src/main/assets/lexicon.tsv`、`android/core/src/test/resources/lexicon.tsv`、`android/core/src/test/resources/feedback.tsv`。同じ内容なら差分は出ません。
- デバッグ用の署名鍵 `.astra/local/toolchains/android-user/debug.keystore` がなければ作ります。
- 辞書（下の4）を渡さずにビルドしたAPKは、小さな内蔵辞書だけで変換し、AIも入りません。**公式APKとは中身が違います。**
- 辞書を渡さない場合、テストは小さな試験用の辞書で動きます。開発記録の全テストの実行は辞書ありの環境で行っていたため、辞書なしでの全テストの結果は記録にありません。

## 4. 公式APKと同じ中身にする（辞書とAI）

公式APKには、Gitに入れていない次の3つが入っています。ビルドは、それぞれが `android/app/oss-assets.lock`・`android/app/zenz-assets.lock` のハッシュと一致しないと失敗します。

| もの | ロックの値（SHA-256） | 作り方 |
|---|---|---|
| `lexicon.bin`・`connectionId.dat`・`oss-manifest.properties`（辞書） | `oss-assets.lock` | 4.1 |
| `zenz-v3.2-xsmall-Q5_K_M.gguf`（モデル） | `00c64b3d…0995fa58` | 4.2 |
| `arm64-v8a/libastra_zenz.so`（AIのライブラリ） | `91a88be6…74a88b59` | 4.3 |

### 4.1 辞書

元データの取得と組み立ての詳細は [docs/oss-converter-integration.md](docs/oss-converter-integration.md) と [docs/dictionary-update.md](docs/dictionary-update.md) にあります（どちらも試験版の時期に書いた記録で、コマンドは今も使えますが、状態の記述は当時のものです）。流れは次のとおりです。

1. Mozc の辞書データ（固定した revision）: `scripts/fetch-mozc-data.ps1`。`third_party/mozc/data-lock.json` で照合します。
2. SudachiDict の raw 辞書 release 20261015（`small_lex.zip`・`core_lex.zip`・`notcore_lex.zip`）: **取得するスクリプトはありません。** URL と SHA-256 は `third_party/sudachidict/README.md` にあります。手で取得して照合し、`ossCli english` で英字表記の追加データ（`english-20261015.txt`、SHA-256 `e520c970…`）を作ります。
3. Mozc-UT 辞書（3つ）: `scripts/fetch-ut-dictionaries.py`（`third_party/mozc-ut/sources.lock.json`）。`ossCli ut-prepare` で `ut-extra.txt`（SHA-256 `fe97d71e…`、`--cost-floor 11000 --relative-penalty 1000`）を作ります。
4. `ossCli build`（`--extra` に 2 と 3）と `ossCli pack` で組み立てます。
5. できたディレクトリを `build-android.ps1 -OssResources <dir>` に渡します。

`ut-prepare` の出力は同じ入力とJDKで同じバイトになることをテストしています。`build` と `pack` の出力が別の環境で同じバイトになるかは確かめていません。ロックと一致しなければビルドが止まるので、違う辞書が黙って入ることはありません。

### 4.2 モデル

`huggingface.co/Miwa-Keita/zenz-v3.2-xsmall-gguf` の revision `4f5423f0fad41a73b1242eb96fe5c12ae4fdca83` にある `zenz-v3.2-xsmall-Q5_K_M.gguf`（20,970,304 バイト）です。**取得するスクリプトはありません。** 手で取得し、SHA-256 を確かめてください。

### 4.3 AIのライブラリ

`android/zenz-native` と llama.cpp v0.7.0 のソースから、Android NDK r30・CMake 4.4.4・Ninja で作ります。手順は [third_party/zenz/README.md](third_party/zenz/README.md) の「Building the Android library」にあり、**スクリプトはありません。** CPU の条件（`armv8.2-a+dotprod+fp16`）はこのビルド設定から来ています。ロックの値と同じバイトになるかを第三者の環境で確かめたことはありません（開発記録では、以前の版のライブラリについて、ビルドIDを除いて同じになることを確かめています）。

### 4.4 すべてを渡したビルド

```powershell
pwsh -NoProfile -File scripts/build-android.ps1 -OssResources <辞書のdir> -ZenzLibDir <arm64-v8aを含むdir> -ZenzModel <gguf> `
  -Tasks ':app:assembleRelease'
pwsh -NoProfile -File scripts/package-android.ps1 -Version 1.0.0 -Release -RequireOss -RequireZenz
```

`package-android.ps1 -Release` は、未署名であること、デバッグ不可であること、パッケージ名、権限がないこと、`lib/` が空であること、ライセンス表記・辞書・AIのファイルがそろっていることを確かめ、`artifacts/apk/anmitsu-1.0.0-unsigned.apk` を作ります。

## 5. 署名

リリースビルドは署名しません。自分で作って保管している鍵で、`apksigner` を使って署名してください。鍵の作成と保管はこのリポジトリの範囲外です。

自分で署名したAPKは、公式APKとは署名者が違うため、公式APKを上書きできません（入れ替えるにはアンインストールが必要で、データは消えます）。

## 6. APKの確認

入手したAPKが公式のものか確かめるには、Android SDK の build-tools で次を実行します。

```powershell
Get-FileHash -Algorithm SHA256 <apk>
apksigner verify --verbose --print-certs <apk>
```

1.0.0（versionCode 1）の公式APKの SHA-256 は `2f4543989165543229f1a1a9a27a70c50af98569b8c8d1fc72dc7b96b3ca612b`、署名者証明書の SHA-256 は `23b46342e1798b7e44d4d526235f29f4c2276ca1718738a8d5098dcc9a612e21` です。

## 7. 再現性の限界

- 自分でビルドしたAPKが公式APKと同じバイトになることは確かめていません。署名が違うので、APK全体のハッシュは必ず違います。
- 署名を除いた中身を比べる手順も、まだ用意していません。辞書・モデル・ライブラリは、ロックのハッシュで公式と同じものかを確かめられます。
- ビルドに使うツールの版は固定していますが、Gradle の依存のハッシュは固定していません。

## 8. 書き出したソースの対応関係

`SOURCE-MANIFEST.txt` に、元のコミット、各ファイルのSHA-256とGitのblob IDがあります。`commit` の行は元のコミットにあるファイルそのもので、`overlay` の行は公開用に書いた文書です（このファイルなどの新しい文書と、元のコミットの文書5件を公開向けに直したもの: `docs/privacy-and-learning.md`、`docs/dictionary-update.md`、`docs/oss-converter-integration.md`、`docs/ux-0.1.18.md`、`third_party/zenz/README.md`）。直したのは文書だけで、コード・ライセンス文・ロック・アプリに入るファイルは元のコミットのままです。

## 9. 履歴としての記録と、公開していない参照先

- `reports/` の6件、`data/README.md` と `data/context-holdout/` の文書、`docs/model-comparison.md`、`docs/ux-0.1.18.md` は、開発の途中で書いた当時の記録です。1.0.0 の動作の説明ではありません（1.0.0 については [README.md](README.md)、[PRIVACY.md](PRIVACY.md)）。
- これらの記録やソースのコメントは、公開していないファイルを名前で挙げていることがあります。開発の作業記録（`docs/next-work.md` など）、試験版の手順書（`docs/android-trial.md` など）、ほかの版の記録（`docs/ux-*.md`）、評価の出力（`reports/` の `.json`・`.txt`・`.tsv` など）です。これらはこのソースに含まれず、入手できません。ビルドと確認に必要なことは、この文書と [THIRD-PARTY.md](THIRD-PARTY.md) に書いています。
- 公式APKのソースを保つため、コードのコメントと元の記録の中の参照はそのままにしています。

# CIベースライン（fixture限定）

`scripts/ci-baseline.ps1` は、OSS辞書・zenzを渡さない **fixture限定** のビルドで `:core:test` `:oss-converter:test` `:app:testDebugUnitTest` `:app:assembleDebug` `:app:lintDebug` を実行し、続けて `package-android.ps1 -Version 1.0.0` で権限ゼロ・パッケージ名・同梱ファイルを検査します。**リリース品質の確認ではありません。** 公式（release）署名・アップロード・公開は行いません。`assembleDebug` のdebug APKは開発用（debug）鍵で署名されます。0以外の終了は全て失敗で、再試行・失敗無視・テスト除外はしません。

## ローカル実行

[BUILDING.md](../BUILDING.md) の手順でツールチェーンを準備してから実行します。

```powershell
pwsh -NoProfile -File scripts/ci-baseline.ps1
```

ツールチェーンを既定（`.astra/local/toolchains/`）以外に置いた場合は `-ToolchainRoot '<絶対パス>'` を渡します。

APKとレポートはこのcheckoutの `artifacts/apk/`（`anmitsu-1.0.0.apk`、`build-info.json`、`ci-baseline-report.json`）にだけ書き込みます。テスト・lintのHTMLレポートのパスは実行の最後に表示されます。

## GitHub Actions

| | `baseline`（`.github/workflows/baseline.yml`） | `pr-ci`（`.github/workflows/pr-ci.yml`） |
|---|---|---|
| 起動 | 手動（`workflow_dispatch`）のみ | PR（opened / synchronize / reopened / ready_for_review）と手動 |
| Android SDK利用規約への同意 | 実行時の入力 `accept_android_sdk_licenses`（false ならジョブをスキップ） | リポジトリ変数 `ANDROID_SDK_LICENSES_ACCEPTED`（`true` 以外なら **失敗**、スキップしない） |
| 同時実行 | 1つ、取り消さない | PR番号/ref ごとに1つ、古い実行は取り消し |

どちらも `setup-android.ps1 -AcceptAndroidSdkLicenses` と `ci-baseline.ps1` を実行し、内容は上と同じfixture限定です。共通: windows-2022、35分で打ち切り、`contents: read`、checkoutは固定SHA・`persist-credentials: false`。secret・OIDC・`pull_request_target`・`workflow_run`・キャッシュ・成果物アップロード・定期実行・AI API呼び出しは使いません。結果はジョブのSummaryとステップログで確認します。

- 同意の入力・変数は、管理者が公式Android SDKの利用規約を読んで同意したことを示すだけの値で、秘密情報ではありません。`setup-android.ps1 -AcceptAndroidSdkLicenses` は上限付きの回数だけ `y` を `sdkmanager --licenses` に渡します。スイッチなしでは従来通り対話で同意を求めます。
- 外部forkからのPRでも、secretを使わない読み取り専用の `pull_request` で動きます。fork PRでリポジトリ変数が渡されない場合は同意チェックで失敗します（安全側）。

## 確認しないこと

- 完全なモデル・OSS辞書（`-OssResources`、zenz）、`assembleRelease`・`-Release`、利用者の署名、ライセンス/表記の内容の品質、実機での動作・安定性・性能。これらはリリースの手順（[BUILDING.md](../BUILDING.md)、[docs/releases/v1.0.0-validation.md](releases/v1.0.0-validation.md)）で別に確かめます。
- 以前のdebug/releaseビルドで不安定な失敗がありました。CIで再発した場合は原因を調べ、無視や再試行で隠さないでください。
- チェックが成功したと書くのは、そのPRの実際のhead SHAに対する結果を確認した場合だけにしてください。実行中（pending）は成功ではありません。

# CIベースライン（fixture限定）

`scripts/ci-baseline.ps1` は、OSS辞書・zenzを渡さない **fixture限定** のビルドで `:core:test` `:oss-converter:test` `:app:testDebugUnitTest` `:app:assembleDebug` `:app:lintDebug` を実行し、続けて `package-android.ps1 -Version 1.0.0` で権限ゼロ・パッケージ名・同梱ファイルを検査します。**リリース品質の確認ではありません。** 公式（release）署名・アップロード・公開は行いません。`assembleDebug` のdebug APKは開発用（debug）鍵で署名されます。0以外の終了は全て失敗で、再試行・失敗無視・テスト除外はしません。

## ローカル実行

専用worktreeから、既存のリポジトリ本体のツールチェーンを使います（セットアップ済みであること）。

```powershell
pwsh -NoProfile -File scripts/ci-baseline.ps1 -ToolchainRoot 'C:\path\to\repo-root\.astra\local\toolchains'
```

`C:\path\to\repo-root` はリポジトリ本体の絶対パスに置き換えてください（引用符付きのフルパス）。

APKとレポートはこのcheckoutの `artifacts/apk/`（`anmitsu-1.0.0.apk`、`build-info.json`、`ci-baseline-report.json`）にだけ書き込みます。テスト・lintのHTMLレポートのパスは実行の最後に表示されます。

## GitHub Actions（手動のみ）

現時点でGitHub上にworkflowはありません。`.github/workflows/baseline.yml` は `workflow_dispatch` のみで、push・PR・定期実行では動きません。AIのAPI呼び出しはなく、Actions内でClaudeは起動しません。secret・キャッシュ・成果物アップロードも使いません。

手順（GitHubアカウントの利用量と、上限超過時に課金が止まる設定を確認した後に限る）:

1. Actions → baseline → Run workflow を開く。
2. `accept_android_sdk_licenses` は、所有者が公式Android SDKの利用規約を自分で読んで同意済みの場合のみ true にする。false ではジョブ自体がスキップされ、ビルドしません。
3. 結果はジョブのSummaryとステップログで確認する（windows-2022、35分で打ち切り、同時実行は1つ）。

`setup-android.ps1 -AcceptAndroidSdkLicenses` は、上限付きの回数だけ `y` を `sdkmanager --licenses` に渡します。スイッチなしの通常セットアップは従来通り対話で同意を求めます。

## 注意

- fixture限定と完全なリリース（`-OssResources`、zenz、`assembleRelease`、`-Release`、利用者の署名、実機確認）は別物です。
- 以前のdebug/releaseビルドで不安定な失敗がありました。CIで再発した場合は原因を調べ、無視や再試行で隠さないでください。
- GitHub Actions上での実行とセットアップ（新規ランナーでのツールチェーン取得を含むコールドスタート）は、実際に実行するまで未検証です。ローカルでの確認は既存のツールチェーンを再利用したものです。
- 自動保守は未導入です。非公開の正本mainは保護ルールが使えないため、AIによる正本への書き込みは無効のままで、反映方法は未定です。

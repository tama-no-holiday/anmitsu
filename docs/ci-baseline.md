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

このソースには手動実行用のworkflowの定義 `.github/workflows/baseline.yml` が含まれます。GitHub上への配置と、そこでの実行は確認していません。定義は `workflow_dispatch` のみで、push・PR・定期実行では動きません。AIのAPI呼び出しはなく、Actions内でClaudeは起動しません。secret・キャッシュ・成果物アップロードも使いません。

手順（GitHubアカウントの利用量と、上限超過時に課金が止まる設定を確認した後に限る）:

1. Actions → baseline → Run workflow を開く。
2. `accept_android_sdk_licenses` は、所有者が公式Android SDKの利用規約を自分で読んで同意済みの場合のみ true にする。false ではジョブ自体がスキップされ、ビルドしません。
3. 結果はジョブのSummaryとステップログで確認する（windows-2022、35分で打ち切り、同時実行は1つ）。

`setup-android.ps1 -AcceptAndroidSdkLicenses` は、上限付きの回数だけ `y` を `sdkmanager --licenses` に渡します。スイッチなしの通常セットアップは従来通り対話で同意を求めます。

## GitHub Actions（PRチェック `pr-ci`）

`.github/workflows/pr-ci.yml` はPR（opened / synchronize / reopened / ready_for_review）と手動実行（`workflow_dispatch`）で、`baseline` と同じ `setup-android.ps1 -AcceptAndroidSdkLicenses` と `ci-baseline.ps1` を実行します。内容は同じくfixture限定で、完全なモデル・OSS辞書・release・ライセンス/表記の品質は確認しません。

| | `baseline` | `pr-ci` |
|---|---|---|
| 起動 | 手動のみ | PR・手動 |
| SDK利用規約への同意 | 実行ごとの入力（falseならスキップ） | リポジトリ変数 `ANDROID_SDK_LICENSES_ACCEPTED`（`true` 以外なら **失敗**、スキップしない） |
| 同時実行 | 1つ、取り消さない | PR番号/ref ごとに1つ、古い実行は取り消し |

共通: windows-2022（標準ランナー）、35分で打ち切り、`contents: read`、checkoutは固定SHA・`persist-credentials: false`。secret・OIDC・`pull_request_target`・`workflow_run`・キャッシュ・成果物アップロード・定期実行・AI API呼び出しは使いません。再試行・失敗無視はなく、失敗はジョブ `fixture-ci` の失敗になります。

- `ANDROID_SDK_LICENSES_ACCEPTED` は秘密情報ではなく、所有者の同意を示すだけの値です。所有者が公式Android SDKの利用規約を読んで同意した後にだけ、Settings → Secrets and variables → Actions → Variables で `true` に設定してください。未設定の間、`fixture-ci` は説明付きで失敗します。
- 外部forkからのPRでも、secretを使わない読み取り専用の `pull_request` で動きます。fork PRでリポジトリ変数が渡されるかは未確認です。渡されない場合は同意チェックで失敗します（安全側）。初回コントリビューターのworkflow実行に承認を求める設定も確認してください。
- 現状（所有者確認済み）: リポジトリは公開 `tama-no-holiday/anmitsu`（main は 560d0cf、旧リポジトリは非公開 `anmitsu-private-archive`）。main のルール: PR必須・バイパスなし・削除と非fast-forward禁止。もう一つの更新制限ルールはリポジトリ管理者がPR経由でのみバイパス可。自動マージは無効。GITHUB_TOKEN の既定は `contents: read`、PR承認は不可。
- **`pr-ci` はまだGitHub上で一度も実行していません。** 初回の成功を確認するまで、`fixture-ci` を必須チェック（required status check）に登録しないでください。成功後に、ジョブ名 `fixture-ci` を登録します。

## 注意

- fixture限定と完全なリリース（`-OssResources`、zenz、`assembleRelease`、`-Release`、利用者の署名、実機確認）は別物です。
- 以前のdebug/releaseビルドで不安定な失敗がありました。CIで再発した場合は原因を調べ、無視や再試行で隠さないでください。
- GitHub Actions上での実行とセットアップ（新規ランナーでのツールチェーン取得を含むコールドスタート）は、実際に実行するまで未検証です。ローカルでの確認は既存のツールチェーンを再利用したものです。
- 自動保守は未導入です。設定手順は [cloud-maintenance.md](cloud-maintenance.md) を参照してください。変更は作業ブランチとプルリクエストで反映します。

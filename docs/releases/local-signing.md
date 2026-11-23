# リリースAPKの手元での署名

`scripts/sign-release.ps1` は、署名していない 1.0.0 のリリースAPK（`scripts/package-android.ps1 -Release` で作るもの）を確かめてから、開発者の鍵で**新しいファイルに**署名します。手で実行するためのもので、フックや CI からは呼びません。鍵を作る・コピーする、パスワードを保存する、APKを公開することはしません。

## 前もって

- PowerShell 7 と、既存のツール一式（`ToolchainRoot` の下の `jdk/` と `sdk/build-tools/35.0.0`）。何もダウンロードしません。
- 公式の鍵ストア。リポジトリの外に置いてください（中にあると止まります）。**鍵を作り直してはいけません。** 別の鍵で署名したAPKは、既存のAPKの更新として導入できません。
- 実行の前に、鍵ストアのバックアップと、既存の署名済みAPKを残しておいてください。スクリプトはどちらも変えませんが、置き換えもしません。
- 署名していないAPKの SHA-256（`package-android.ps1` が出す `.sha256`）と、期待する署名者証明書の SHA-256。

## 使い方

パスワードは引数では渡さず、呼び出す側が名前を決めた環境変数に入れます。スクリプトはその**名前**だけを apksigner に `env:名前` として渡し、値を表示しません。

```powershell
$env:ANMITSU_KS_PASS = Read-Host -MaskInput 'keystore password'
try {
    pwsh -File scripts/sign-release.ps1 `
        -InputApk artifacts/apk/anmitsu-1.0.0-unsigned.apk `
        -OutputApk <出力先>/anmitsu-1.0.0.apk `
        -ToolchainRoot <ツールの場所> `
        -KeystorePath <鍵ストアの場所> `
        -KeyAlias <別名> `
        -ExpectedInputSha256 <署名していないAPKの SHA-256> `
        -ExpectedCertSha256 <署名者証明書の SHA-256> `
        -KeystorePassEnv ANMITSU_KS_PASS
} finally { Remove-Item Env:ANMITSU_KS_PASS -ErrorAction SilentlyContinue }
```

- 鍵のパスワードが鍵ストアと違うときは `-KeyPassEnv` に別の変数名を渡します。`-KeyAlias` は鍵ストアに鍵が1つだけなら省けます。
- 環境変数の設定と削除は呼び出す側の責任です。`$env:...= '実際の値'` のように値をコマンドに書くと履歴に残るので、上のように `Read-Host -MaskInput` で入れてください。パスワードや鍵をリポジトリ、スクリプト、メモに残さないでください。
- 証明書の SHA-256 は大文字・小文字、`:` 区切りのどちらでも受け付けます。

## スクリプトが確かめること

署名の前（どれかが合わなければ何も書きません）:

1. 引数: 2つの SHA-256 が64桁の16進数であること、入力・鍵ストア・出力先のフォルダがあること、出力のAPKとその `.idsig` がまだないこと、出力が入力・鍵ストアと別のファイルであること。
2. 入力の SHA-256 が期待値と一致すること。
3. 入力が署名されていないこと（apksigner の検証が通らず、`META-INF/` に JAR 署名がない）。これだけでは正しいAPKとはみなさず、次も確かめます。
4. `aapt` で、パッケージ名 `io.github.tama_no_holiday.anmitsu`、versionName `1.0.0`、versionCode `1`、`uses-permission` がないこと、debuggable でないこと。
5. `zipalign -c -P 16 4` で整列済みであること。入力を書き直すことはしません。

署名: `apksigner sign --v4-signing-enabled false --out <出力> <入力>`。入力はそのまま残ります。

署名の後:

1. 出力があり、`.idsig` ができていないこと。入力の SHA-256 が変わっていないこと。
2. `apksigner verify --print-certs` が通り、署名者がちょうど1つで、その証明書の SHA-256 が `-ExpectedCertSha256` と一致すること。
3. 出力について、署名前の 4・5 と同じ確認。

すべて通ると、出力の場所、署名者証明書の SHA-256、出力の SHA-256 を表示します。途中で失敗すると `NOT READY` を表示して止まります。そのときの出力ファイルは調べるために消さずに残すので、配布に使わず、確かめたあと手で片付けてください。もう一度実行するときは、別の出力先を指定するか、そのファイルを先に移してください。

## しないこと

- 鍵の作成・コピー、パスワードの保存や表示。
- APKの実行や端末への導入、GitHub などへの公開。公開は、表示された SHA-256 と証明書を確かめたうえで別に行います。

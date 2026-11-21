# 参加のしかた

## 現在の状態

このプロジェクト自身のコードのライセンスは未決定です。**ライセンスが決まるまで、プルリクエストによるコードの提供は受け付けを保留します。** 不具合の報告や提案は歓迎します（脆弱性は [SECURITY.md](SECURITY.md) へ）。

## 不具合の報告

- anmitsu の版、APK の SHA-256、端末の機種、Android の版、入力したアプリと入力欄の種類を書いてください。
- 実際に入力した文章・パスワード・個人の情報は書かないでください。作った文字列で再現してください。
- 変換の結果についての報告は、読み（例: `しかいをつとめます`）、出た候補、期待した候補を書いてください。

## 開発の決まり（ライセンス決定後に適用）

### 守ること

- 権限を増やさない。特に `INTERNET` は追加しない（`scripts/package-android.ps1` が権限のあるAPKを拒みます）。
- 入力した文字・クリップボード・前の文を、ログ・ファイル・送信に出さない。学習は [docs/privacy-and-learning.md](docs/privacy-and-learning.md) の範囲を超えない。入力から利用者の属性を推測しない。
- 辞書・モデル・ライブラリを変えるときは、ロック（`android/app/oss-assets.lock`、`android/app/zenz-assets.lock`、`third_party/*/` のロック）とライセンス表記を同時に更新する。
- `third_party/` のファイルは、記録したハッシュのバイトのまま保つ（`.gitattributes` で改行の変換を止めています）。修正が必要なら `PATCHES.md` に記録する。
- テストやデータには、作った文章だけを使う。実際の入力履歴や他人の文章を入れない。データの由来は `data/README.md` に書く。

### テスト

```powershell
pwsh -NoProfile -File scripts/build-android.ps1 -Tasks ':core:test',':oss-converter:test',':app:testDebugUnitTest',':app:lintDebug'
```

手順と前提は [BUILDING.md](BUILDING.md) にあります。JVM と Robolectric のテストは、実機での確認の代わりにはなりません。端末で確かめたことと、テストで確かめたことは分けて書いてください。

## 提供されたコードの扱い

コードを受け付ける場合の条件（ライセンス、表示の方法）は、ライセンスと一緒に決めて、ここに書きます。

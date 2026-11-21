# anmitsu アプリアイコン（B案：変換中の下線）

Android のアダプティブアイコン一式です。`res/` の中身をそのままプロジェクトに入れれば使えます。

## 中身

| パス | 役割 |
|---|---|
| `res/mipmap-anydpi-v26/ic_launcher.xml` / `ic_launcher_round.xml` | アダプティブアイコン定義（Android 8.0+） |
| `res/drawable/ic_launcher_foreground.xml` | 前景レイヤー（ベクター。「あ」＋3色の下線＋カーソル） |
| `res/drawable/ic_launcher_monochrome.xml` | テーマアイコン用の単色レイヤー（Android 13+） |
| `res/values/ic_launcher_background.xml` | 背景色 `#F4ECDD` |
| `res/mipmap-*dpi/ic_launcher.png` / `ic_launcher_round.png` | 旧Android（7.1以下）用のPNG |
| `playstore/ic_launcher-playstore.png` | Google Play 掲載用 512×512 |
| `source/*.svg` | 編集用の元データ |

- 「あ」はアウトライン化済み（Noto Sans CJK JP Bold, SIL OFL）なので、フォントは不要です。
- 前景は 108dp キャンバスのセーフゾーン（直径66dp）内に収めてあります。

## 色

| 用途 | 色 |
|---|---|
| 背景 | `#F4ECDD` |
| 文字 | `#2B1A17` |
| カーソル | `#7A2E35` |
| 下線（桜・蜜・抹茶） | `#E59A9A` / `#C8923A` / `#8DB07E` |

## AI（Codex / Claude Code）に渡すときのプロンプト

このフォルダをリポジトリ直下に `anmitsu-icon/` として置いてから、次の文をそのまま渡してください。

```
anmitsu-icon/ に新しいランチャーアイコン一式があります。これをアプリに適用してください。

1. anmitsu-icon/res/ の中身を app/src/main/res/ にコピーし、同名ファイルは上書きする。
2. 既存の古いアイコンを削除して重複リソースを防ぐ：
   - app/src/main/res/mipmap-*/ic_launcher.webp と ic_launcher_round.webp（.png と同名で衝突するため）
   - app/src/main/res/drawable/ic_launcher_background.xml（今回は values/ の色リソースを使う）
   - その他、旧アイコン専用で参照されなくなった drawable
3. AndroidManifest.xml の <application> が android:icon="@mipmap/ic_launcher" と
   android:roundIcon="@mipmap/ic_launcher_round" になっているか確認し、違えば直す。
   IMEの設定（res/xml/method.xml など）でアイコンを指定していれば同じく @mipmap/ic_launcher にする。
4. ./gradlew assembleDebug でビルドが通ることを確認する。
5. anmitsu-icon/ フォルダ自体はリポジトリの design/icon/ に移動して保管する（res以外の元データとして）。
```

# Hiragana Hand Writting Keyboard

Android 向けの「ひらがな手書き入力キーボード」プロジェクトです。  
手書き（描画）からひらがな入力を行う IME（Input Method Editor）を目標にした実装・検証用リポジトリです。

## Features

- ひらがなを手書きで入力（描画 UI から認識 → 文字入力）
- 認識結果の候補表示（複数候補を提示して選択できる想定）
- かな漢字変換エンジン／モジュールとの連携

## Demo / Screenshots

<img src="docs/light1.png" width="256px" height="auto"/>

<img src="docs/dark1.png" width="256px" height="auto"/>

## Requirements

- Android Studio（推奨: 最新安定版）
- Android SDK / Platform Tools
- JDK（Android Studio 同梱で可）


## Getting Started

### 1) Clone

```bash
git clone https://github.com/KazumaProject/Hiragana-Hand-Writting-Keyboard.git
```

### 2) Open in Android Studio

Android Studio で本リポジトリを開き、Gradle Sync を実行してください。

### 3) Build & Run

`app` モジュールを選択して実機またはエミュレータで実行してください。


## How to Use (IME として有効化)

本アプリが「キーボード（IME）」として動作する場合の一般的な手順です。

1. Android 設定 → **システム** → **言語と入力**（または **一般管理**）
2. **画面キーボード** → **キーボードの管理** で本アプリを有効化
3. テキスト入力欄でキーボード切替（地球儀アイコン等）から本キーボードを選択

※ 端末メーカーにより表記が異なります。

## Project Structure

- `app/`
  Android アプリ本体（UI、IME 実装、描画 UI、候補表示など）
- `kana_kanji_converter/`
  かな漢字変換（または変換関連）モジュール（連携・実験用）


## Third-Party

本プロジェクトは第三者のライブラリ／データ／辞書を利用しています。  
詳細は `THIRD_PARTY_NOTICES.md` を参照してください。

- PyTorch（手書き認識）
- tomoe_data（手書き文字生成用ストロークデータ）
- Mozc dictionary data（辞書データ）

## License

- 本リポジトリのコード: MIT License（`LICENSE`）
- 第三者成果物: `THIRD_PARTY_NOTICES.md` に記載の各ライセンス条件が適用されます。

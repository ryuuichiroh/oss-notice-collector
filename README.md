# OSS NOTICE Collector

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://www.oracle.com/java/)

Java プロジェクトが利用する OSS の NOTICE ファイルを自動収集する CLI ツールです。
Maven / Gradle プロジェクトの依存関係を解析し、Apache License 2.0 の OSS を対象に NOTICE ファイルを検索・取得・保存します。

## Features

- 🔍 Maven / Gradle プロジェクトの依存関係を自動解析
- 📄 Apache License 2.0 の OSS から NOTICE ファイルを自動収集
- 🌐 複数のソースから検索（Maven Central、GitHub、Apache Archive など）
- 📦 収集した NOTICE を1つのファイルに集約
- ⚙️ YAML 設定ファイルで柔軟にカスタマイズ可能
- 🔐 社内プライベートリポジトリにも対応
- 📊 JSON 形式の詳細レポート出力

## 必要環境

- Java 17 以上
- Maven 3.6 以上（ビルド用）
- Maven または Gradle（対象プロジェクトの依存関係解析用）

## Quick Start

```bash
# 1. リポジトリをクローン
git clone https://github.com/ryuuichiroh/oss-notice-collector.git
cd oss-notice-collector

# 2. ビルド
mvn package -DskipTests

# 3. サンプル設定ファイルを作成
cat > config.yaml << 'EOF'
project:
  path: "/path/to/your/project"
output:
  directory: "output"
EOF

# 4. 実行
java -jar target/notice-collector.jar --config config.yaml
```

## ビルド

```bash
cd oss-notice-collector
mvn package -DskipTests
```

`target/notice-collector.jar` が生成されます。

## 使い方

### 基本

```bash
java -jar notice-collector.jar --config config.yaml
```

### CLI オプション

| オプション | 短縮 | 説明 |
|---|---|---|
| `--config <path>` | `-c` | YAML 設定ファイルのパス |
| `--build-tool <type>` | `-b` | ビルドツールを指定（`maven`, `gradle`, `auto`） |
| `--deps-file <path>` | `-d` | 依存関係リストファイルのパス（`groupId:artifactId:version` 形式、1行1件） |
| `--help` | `-h` | ヘルプを表示 |
| `--version` | `-V` | バージョンを表示 |

### 例

```bash
# Maven プロジェクトを自動検出して実行
java -jar notice-collector.jar --config config.yaml

# ビルドツールを明示的に指定
java -jar notice-collector.jar --config config.yaml --build-tool maven

# テキストファイルで依存関係を指定（ビルドツール不要）
java -jar notice-collector.jar --config config.yaml --deps-file deps.txt
```

## 設定ファイル（YAML）

最小構成の例:

```yaml
project:
  path: "."                    # プロジェクトのルートパス
output:
  directory: "output"          # 出力先ディレクトリ
```

主要な設定項目:

| セクション | 説明 | 必須 |
|---|---|---|
| `project` | プロジェクトパスとビルドツール設定 | ○ |
| `output` | 出力先ディレクトリとファイル名 | ○ |
| `github` | GitHub API トークン設定 | × |
| `repositories` | 社内リポジトリ設定 | × |
| `overrides` | 特定依存関係の NOTICE/ライセンス上書き | × |
| `externalDefinitions` | 外部定義ファイル（ライセンスマッピングなど） | × |

詳細な設定方法は [設定ファイル詳細ガイド](docs/configuration.md) を参照してください。

## 出力

実行後、以下のファイルが生成されます。

| ファイル | 内容 |
|---|---|
| `output/notices/{groupId}/{artifactId}/{version}/NOTICE` | 依存関係ごとの NOTICE ファイル |
| `output/collection-report.json` | 収集結果の JSON レポート（サマリ＋詳細） |
| `output/THIRD-PARTY-NOTICES.txt` | 全 NOTICE を連結した集約ファイル |

## NOTICE 検索の優先順位

NOTICE ファイルは以下の順で検索され、見つかった時点で終了します。

1. ユーザ指定（`overrides` で `noticePath` / `noticeUrl` を指定）
   - `noticeUrl` では以下のバージョンプレースホルダーが使用可能:
     - `${version}` - バージョンをそのまま置換 (例: 1.2.3)
     - `${underscored_version}` - ドットをアンダースコアに置換 (例: 1_2_3)
     - `${version_major}` - メジャーバージョンのみ (例: 1)
     - `${version_minor}` - メジャー.マイナーバージョン (例: 1.2)
2. ローカルキャッシュ（Maven ローカルリポジトリ / Gradle キャッシュの JAR 内）
3. Maven Central（ソース JAR をダウンロードして抽出）
4. 社内プライベートリポジトリ
5. Apache Archive
6. GitHub リポジトリ（`<scm>` タグから URL を取得し GitHub API で検索）
7. ユーザ指定ソースコードリポジトリ（JGit でクローン）

## 環境変数

| 変数名 | 用途 |
|---|---|
| `GITHUB_TOKEN` | GitHub API アクセス用トークン（設定ファイルの `github.tokenEnv` で変更可） |

社内リポジトリの認証情報も環境変数で管理します（設定ファイルの `auth` セクション参照）。

## ライセンス

本ツールは Apache License 2.0 の OSS のみを NOTICE 収集対象とします。
ライセンス名の表記揺れは組み込みマッピングで自動的に SPDX 識別子に正規化されます。
`license-mappings.yaml` でマッピングの追加・上書きが可能です。

## 既知の改善項目

詳細な修正方法は [IMPROVEMENTS.md](./IMPROVEMENTS.md) を参照してください。

## Contributing

コントリビューションを歓迎します！

1. このリポジトリをフォーク
2. フィーチャーブランチを作成 (`git checkout -b feature/amazing-feature`)
3. 変更をコミット (`git commit -m 'Add some amazing feature'`)
4. ブランチにプッシュ (`git push origin feature/amazing-feature`)
5. プルリクエストを作成

バグ報告や機能要望は [Issues](https://github.com/ryuuichiroh/oss-notice-collector/issues) からお願いします。

## Links

- [Issues](https://github.com/ryuuichiroh/oss-notice-collector/issues) - バグ報告・機能要望
- [Releases](https://github.com/ryuuichiroh/oss-notice-collector/releases) - リリース履歴

## License

このプロジェクトは Apache License 2.0 の下でライセンスされています。詳細は [LICENSE](LICENSE) ファイルを参照してください。

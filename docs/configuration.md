# 設定ファイル詳細ガイド

このドキュメントでは、`config.yaml` の各設定項目について詳しく説明します。

## 目次

- [基本構造](#基本構造)
- [project セクション](#project-セクション)
- [output セクション](#output-セクション)
- [github セクション](#github-セクション)
- [repositories セクション](#repositories-セクション)
- [overrides セクション](#overrides-セクション)
- [externalDefinitions セクション](#externaldefinitions-セクション)
- [設定例](#設定例)

## 基本構造

```yaml
project:          # プロジェクト設定（必須）
output:           # 出力設定（必須）
github:           # GitHub API 設定（任意）
repositories:     # 社内リポジトリ設定（任意）
overrides:        # 個別依存関係の上書き設定（任意）
externalDefinitions:  # 外部定義ファイル（任意）
```

## project セクション

プロジェクトの依存関係を解析するための設定です。

### 基本設定

| 項目 | 必須 | デフォルト | 説明 |
|---|---|---|---|
| `path` | ○ | - | プロジェクトのルートディレクトリパス |
| `buildTool` | × | `auto` | ビルドツール (`auto`, `maven`, `gradle`) |

### Maven 固有設定

```yaml
project:
  maven:
    scopes:
      - "compile"
      - "runtime"
```

| 項目 | 必須 | デフォルト | 説明 |
|---|---|---|---|
| `scopes` | × | `["compile", "runtime"]` | 対象とする Maven スコープのリスト |

**利用可能なスコープ:**
- `compile` - コンパイル時の依存関係
- `runtime` - 実行時の依存関係
- `provided` - 実行環境が提供する依存関係
- `test` - テスト時のみの依存関係

### Gradle 固有設定

```yaml
project:
  gradle:
    configurations:
      - "runtimeClasspath"
      - "compileClasspath"
```

| 項目 | 必須 | デフォルト | 説明 |
|---|---|---|---|
| `configurations` | × | `["runtimeClasspath"]` | 対象とする Gradle コンフィギュレーションのリスト |

**よく使われるコンフィギュレーション:**
- `runtimeClasspath` - 実行時のクラスパス
- `compileClasspath` - コンパイル時のクラスパス
- `implementation` - 実装依存関係

## output セクション

収集した NOTICE ファイルの出力先を設定します。

```yaml
output:
  directory: "output"
  aggregatedFile: "THIRD-PARTY-NOTICES.txt"
  reportFile: "collection-report.json"
```

| 項目 | 必須 | デフォルト | 説明 |
|---|---|---|---|
| `directory` | ○ | - | 出力先ディレクトリパス（相対パスまたは絶対パス） |
| `aggregatedFile` | × | `THIRD-PARTY-NOTICES.txt` | 全 NOTICE を連結したファイル名 |
| `reportFile` | × | `collection-report.json` | 収集結果レポートのファイル名 |

**出力されるファイル構造:**
```
output/
├── notices/
│   └── {groupId}/
│       └── {artifactId}/
│           └── {version}/
│               └── NOTICE
├── THIRD-PARTY-NOTICES.txt
└── collection-report.json
```

## github セクション

GitHub API を使用して NOTICE ファイルを検索する際の設定です。

```yaml
github:
  tokenEnv: "GITHUB_TOKEN"
```

| 項目 | 必須 | デフォルト | 説明 |
|---|---|---|---|
| `tokenEnv` | × | `GITHUB_TOKEN` | GitHub トークンを格納した環境変数名 |

**GitHub トークンの取得方法:**
1. GitHub の Settings → Developer settings → Personal access tokens
2. "Generate new token (classic)" を選択
3. `public_repo` スコープを選択
4. 環境変数に設定: `export GITHUB_TOKEN=your_token_here`

**注意:** トークンがない場合、GitHub API のレート制限（60リクエスト/時）が適用されます。

## repositories セクション

社内プライベートリポジトリなど、Maven Central 以外のリポジトリを設定します。

```yaml
repositories:
  - name: "internal-repo"
    url: "https://repo.example.com/maven2"
    type: "maven"
    auth:
      type: "basic"
      usernameEnv: "REPO_USER"
      passwordEnv: "REPO_PASS"
```

### 各項目の説明

| 項目 | 必須 | 説明 |
|---|---|---|
| `name` | ○ | リポジトリの識別名 |
| `url` | ○ | リポジトリの URL |
| `type` | ○ | リポジトリタイプ（現在は `maven` のみ対応） |
| `auth` | × | 認証設定（不要な場合は省略可） |

### 認証設定

| 項目 | 必須 | 説明 |
|---|---|---|
| `type` | ○ | 認証タイプ（`basic` または `token`） |
| `usernameEnv` | △ | ユーザ名を格納した環境変数名（basic 認証時） |
| `passwordEnv` | △ | パスワードを格納した環境変数名（basic 認証時） |
| `tokenEnv` | △ | トークンを格納した環境変数名（token 認証時） |

**使用例:**
```bash
export REPO_USER=myuser
export REPO_PASS=mypassword
java -jar notice-collector.jar --config config.yaml
```

## overrides セクション

特定の依存関係に対して、NOTICE ファイルの取得方法やライセンス情報を上書きします。

### ローカルファイルを指定

```yaml
overrides:
  - groupId: "com.example"
    artifactId: "my-lib"
    version: "1.0.0"
    noticePath: "./notices/my-lib-NOTICE"
```

### URL を指定（バージョンプレースホルダー対応）

```yaml
overrides:
  - groupId: "log4j"
    artifactId: "log4j"
    spdxId: "Apache-2.0"
    noticeUrl: "https://github.com/apache/logging-log4j1/archive/refs/tags/v${underscored_version}.tar.gz"
```

### 各項目の説明

| 項目 | 必須 | 説明 |
|---|---|---|
| `groupId` | ○ | Maven の groupId |
| `artifactId` | ○ | Maven の artifactId |
| `version` | × | バージョン（省略時は全バージョンに適用） |
| `spdxId` | × | ライセンスの SPDX 識別子（例: `Apache-2.0`） |
| `noticePath` | × | ローカルの NOTICE ファイルパス |
| `noticeUrl` | × | NOTICE を含むアーカイブの URL |

### バージョンプレースホルダー

`noticeUrl` では以下のプレースホルダーが使用できます:

| プレースホルダー | 説明 | 例（バージョン 1.2.3 の場合） |
|---|---|---|
| `${version}` | バージョンをそのまま置換 | `1.2.3` |
| `${underscored_version}` | ドットをアンダースコアに置換 | `1_2_3` |
| `${version_major}` | メジャーバージョンのみ | `1` |
| `${version_minor}` | メジャー.マイナーバージョン | `1.2` |

**使用例:**
```yaml
# log4j 1.2.17 の場合
# URL: https://github.com/apache/logging-log4j1/archive/refs/tags/v1_2_17.tar.gz
overrides:
  - groupId: "log4j"
    artifactId: "log4j"
    noticeUrl: "https://github.com/apache/logging-log4j1/archive/refs/tags/v${underscored_version}.tar.gz"
```

### spdxId の使用ケース

`spdxId` を指定すると、通常のライセンス判定プロセスをスキップできます:

- pom.xml にライセンス情報が記載されていない場合
- ライセンス判定を確実に Apache-2.0 として扱いたい場合
- NOTICE ファイルの取得元が Apache-2.0 であることが既知の場合

## externalDefinitions セクション

ライセンスマッピングや NOTICE 検索パターンを外部ファイルで定義します。

```yaml
externalDefinitions:
  licenseMappings: "license-mappings.yaml"
  noticePatterns: "notice-patterns.yaml"
```

| 項目 | 必須 | 説明 |
|---|---|---|
| `licenseMappings` | × | ライセンス名の表記揺れマッピングファイル |
| `noticePatterns` | × | NOTICE ファイル検索パターン定義ファイル |

### license-mappings.yaml の例

```yaml
mappings:
  - names:
      - "The Apache Software License, Version 2.0"
      - "Apache License, Version 2.0"
      - "Apache-2.0"
      - "ASL 2.0"
    spdxId: "Apache-2.0"
  - names:
      - "The MIT License"
      - "MIT License"
    spdxId: "MIT"
```

### notice-patterns.yaml の例

```yaml
patterns:
  - "NOTICE"
  - "NOTICE.txt"
  - "NOTICE.md"
  - "NOTICE-binary"
```

## 設定例

### 最小構成

```yaml
project:
  path: "."
output:
  directory: "output"
```

### Maven プロジェクト（標準的な構成）

```yaml
project:
  path: "/path/to/maven/project"
  buildTool: "maven"
  maven:
    scopes:
      - "compile"
      - "runtime"

output:
  directory: "output"
  aggregatedFile: "THIRD-PARTY-NOTICES.txt"
  reportFile: "collection-report.json"

github:
  tokenEnv: "GITHUB_TOKEN"
```

### Gradle プロジェクト

```yaml
project:
  path: "/path/to/gradle/project"
  buildTool: "gradle"
  gradle:
    configurations:
      - "runtimeClasspath"

output:
  directory: "build/notices"

github:
  tokenEnv: "GITHUB_TOKEN"
```

### 社内リポジトリを使用

```yaml
project:
  path: "."

output:
  directory: "output"

github:
  tokenEnv: "GITHUB_TOKEN"

repositories:
  - name: "company-maven"
    url: "https://maven.company.com/repository"
    type: "maven"
    auth:
      type: "basic"
      usernameEnv: "MAVEN_USER"
      passwordEnv: "MAVEN_PASS"
```

### 特定ライブラリの NOTICE を手動指定

```yaml
project:
  path: "."

output:
  directory: "output"

overrides:
  # ローカルファイルを指定
  - groupId: "com.example"
    artifactId: "internal-lib"
    noticePath: "./custom-notices/internal-lib-NOTICE"
  
  # GitHub アーカイブから取得
  - groupId: "log4j"
    artifactId: "log4j"
    spdxId: "Apache-2.0"
    noticeUrl: "https://github.com/apache/logging-log4j1/archive/refs/tags/v${underscored_version}.tar.gz"
  
  # バージョン指定
  - groupId: "com.mysql"
    artifactId: "mysql-connector-j"
    version: "8.0.33"
    spdxId: "Apache-2.0"
    noticeUrl: "https://github.com/mysql/mysql-connector-j/archive/refs/tags/${version}.tar.gz"
```

### フル構成

```yaml
project:
  path: "/path/to/project"
  buildTool: "auto"
  maven:
    scopes:
      - "compile"
      - "runtime"
  gradle:
    configurations:
      - "runtimeClasspath"

output:
  directory: "output"
  aggregatedFile: "THIRD-PARTY-NOTICES.txt"
  reportFile: "collection-report.json"

github:
  tokenEnv: "GITHUB_TOKEN"

repositories:
  - name: "internal-repo"
    url: "https://repo.example.com/maven2"
    type: "maven"
    auth:
      type: "basic"
      usernameEnv: "REPO_USER"
      passwordEnv: "REPO_PASS"

overrides:
  - groupId: "com.example"
    artifactId: "my-lib"
    version: "1.0.0"
    noticePath: "./notices/my-lib-NOTICE"
  - groupId: "log4j"
    artifactId: "log4j"
    spdxId: "Apache-2.0"
    noticeUrl: "https://github.com/apache/logging-log4j1/archive/refs/tags/v${underscored_version}.tar.gz"

externalDefinitions:
  licenseMappings: "license-mappings.yaml"
  noticePatterns: "notice-patterns.yaml"
```

## トラブルシューティング

### GitHub API のレート制限エラー

**症状:** `API rate limit exceeded` エラーが発生

**解決方法:**
1. GitHub トークンを取得
2. 環境変数に設定: `export GITHUB_TOKEN=your_token`
3. 設定ファイルで `github.tokenEnv` を確認

### 社内リポジトリへの接続エラー

**症状:** `401 Unauthorized` または `403 Forbidden`

**解決方法:**
1. 認証情報の環境変数が正しく設定されているか確認
2. リポジトリの URL が正しいか確認
3. ネットワーク接続（プロキシ設定など）を確認

### NOTICE ファイルが見つからない

**症状:** 特定の依存関係の NOTICE が収集できない

**解決方法:**
1. `overrides` セクションで `noticePath` または `noticeUrl` を手動指定
2. `spdxId: "Apache-2.0"` を明示的に指定してライセンス判定をスキップ
3. レポートファイル（`collection-report.json`）で詳細なエラー情報を確認

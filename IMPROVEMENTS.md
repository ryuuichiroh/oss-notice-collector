# 改善項目の詳細と修正方法

README.md の「既知の改善項目」に対する具体的な修正方法をまとめます。

## 未実装改善（優先度順）

### 🟡 1. `--deps-file` での Gradle 出力形式サポート（優先度: 中）

### 対象ファイル

- `src/main/java/com/github/noticecollector/dependency/FileDependencyResolver.java`
- `src/main/java/com/github/noticecollector/dependency/Dependency.java`（`fromGav` メソッド）

### 現状の問題

`FileDependencyResolver.parseLine` は `Dependency.fromGav(line)` を直接呼び出しており、
`groupId:artifactId:version` 以外の形式（Gradle のツリー出力行等）を受け付けない。

### 修正方法

2 つのアプローチが考えられる。

#### アプローチ A: FileDependencyResolver にフォーマット自動検出を追加（推奨）

ファイルの内容を先読みし、Gradle 出力形式かプレーンな GAV リストかを判定する。

```java
@Override
public List<Dependency> resolve(Path projectPath, NoticeCollectorConfig config)
    throws DependencyResolutionException {
    // ... ファイル存在チェック ...

    List<String> lines = Files.readAllLines(depsFile, StandardCharsets.UTF_8);

    // Gradle 出力形式の検出: ツリー接頭辞（+---, \---）を含む行があるか
    boolean isGradleOutput = lines.stream()
        .anyMatch(line -> GRADLE_TREE_PREFIX.matcher(line).find());

    if (isGradleOutput) {
        return parseGradleOutput(lines);
    } else {
        return parseGavList(lines);
    }
}

private static final Pattern GRADLE_TREE_PREFIX =
    Pattern.compile("^[\\s|+\\\\-]+(\\S+:\\S+:\\S+)");
```

Gradle 出力形式のパースには `GradleDependencyResolver.parseGradleDependenciesOutput` の
ロジックを再利用（共通メソッドとして抽出）するのが望ましい。

#### アプローチ B: GradleDependencyResolver のパースロジックを共通化

`GradleDependencyResolver` 内の `parseGradleDependenciesOutput` を
static ユーティリティメソッドとして抽出し、`FileDependencyResolver` からも呼び出せるようにする。

```java
// GradleDependencyResolver から抽出
public class GradleOutputParser {
    public static List<Dependency> parse(String output, List<String> configurations) {
        // 既存の parseGradleDependenciesOutput ロジック
    }
}
```

### テスト観点

- Gradle の `dependencies` 出力をそのまま `--deps-file` に渡して正しくパースされること
- `->` によるバージョン解決が正しく処理されること
- `(*)` や `(c)` 等の Gradle 特有の注釈が無視されること
- 従来の `groupId:artifactId:version` 形式が引き続き動作すること
- 空行・コメント行が正しくスキップされること

package com.github.noticecollector.dependency;

import com.github.noticecollector.config.NoticeCollectorConfig;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gradle プロジェクトの依存関係を解決する {@link DependencyResolver} 実装。
 *
 * <p>{@code gradle dependencies --configuration runtimeClasspath} を実行し、
 * 出力をパースして依存関係リストを生成する。BOM（packaging=pom）は自動的にフィルタリングされる。
 *
 * <p>Gradle の依存関係ツリー出力における {@code ->} 表記（バージョン解決）に対応し、
 * 解決後のバージョンを採用する。
 */
public class GradleDependencyResolver implements DependencyResolver {

  private static final Logger LOG = LoggerFactory.getLogger(GradleDependencyResolver.class);

  /**
   * Gradle 依存関係ツリー出力行のパターン。
   *
   * <p>形式例:
   * <ul>
   *   <li>{@code +--- org.slf4j:slf4j-api:2.0.16}</li>
   *   <li>{@code |    \--- com.google.guava:guava:33.0-jre}</li>
   *   <li>{@code +--- io.netty:netty-buffer:4.1.100.Final -> 4.1.110.Final}</li>
   *   <li>{@code \--- org.apache.commons:commons-lang3:3.14.0 (*)}</li>
   *   <li>{@code +--- org.apache.commons:commons-lang3:3.14.0 (c)}</li>
   * </ul>
   *
   * <p>ツリー接頭辞（{@code +--- }, {@code \--- }, {@code |    } 等）の後に
   * {@code groupId:artifactId:version} が続く。{@code ->} がある場合は解決後バージョンを採用する。
   */
  private static final Pattern DEP_LINE_PATTERN =
      Pattern.compile(
          "^[\\s|+\\\\-]+([^:]+):([^:]+):([^:\\s]+)"
              + "(?:\\s+->\\s+([^\\s(]+))?"
              + "(?:\\s+\\(.*\\))?\\s*$");

  /**
   * configuration ヘッダ行のパターン（例: {@code runtimeClasspath - ...}）。
   */
  private static final Pattern CONFIG_HEADER_PATTERN =
      Pattern.compile("^(\\w+)(?:\\s+-\\s+.*)?$");

  private final ProcessExecutor processExecutor;

  /** デフォルトコンストラクタ。実際のプロセス実行を使用する。 */
  public GradleDependencyResolver() {
    this(new DefaultProcessExecutor());
  }

  /**
   * テスト用コンストラクタ。プロセス実行をモック可能にする。
   *
   * @param processExecutor プロセス実行の委譲先
   */
  GradleDependencyResolver(ProcessExecutor processExecutor) {
    this.processExecutor = processExecutor;
  }

  @Override
  public List<Dependency> resolve(Path projectPath, NoticeCollectorConfig config)
      throws DependencyResolutionException {
    validateProjectPath(projectPath);

    List<String> configurations = config.getProject().getGradle().getConfigurations();
    String output = executeGradleDependencies(projectPath, configurations);
    List<Dependency> deps = parseGradleDependenciesOutput(output, configurations);
    LOG.info("Resolved {} dependencies (after BOM filtering) from Gradle project at {}",
        deps.size(), projectPath);
    return deps;
  }

  private void validateProjectPath(Path projectPath) throws DependencyResolutionException {
    Path buildGradle = projectPath.resolve("build.gradle");
    Path buildGradleKts = projectPath.resolve("build.gradle.kts");
    if (!Files.exists(buildGradle) && !Files.exists(buildGradleKts)) {
      throw new DependencyResolutionException(
          "build.gradle or build.gradle.kts not found in project directory: " + projectPath);
    }
  }

  /**
   * プロジェクトディレクトリに Gradle Wrapper が存在する場合はそちらを優先し、
   * 存在しない場合は PATH 上の {@code gradle} コマンドにフォールバックする。
   *
   * @param projectPath プロジェクトディレクトリ
   * @return 使用する Gradle コマンドのパスまたは名前
   */
  String resolveGradleCommand(Path projectPath) {
    // Windows: gradlew.bat を優先
    if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
      Path gradlewBat = projectPath.resolve("gradlew.bat");
      if (Files.exists(gradlewBat)) {
        LOG.debug("Gradle Wrapper (bat) を使用します: {}", gradlewBat);
        return gradlewBat.toAbsolutePath().toString();
      }
      // Wrapper が見つからない場合は PATH 上の gradle にフォールバック
      LOG.debug("Gradle Wrapper が見つかりません。PATH 上の gradle を使用します");
      return "gradle";
    }

    // Unix / macOS: gradlew を優先
    Path gradlew = projectPath.resolve("gradlew");
    if (Files.exists(gradlew)) {
      LOG.debug("Gradle Wrapper を使用します: {}", gradlew);
      return gradlew.toAbsolutePath().toString();
    }

    // Wrapper が見つからない場合は PATH 上の gradle にフォールバック
    LOG.debug("Gradle Wrapper が見つかりません。PATH 上の gradle を使用します");
    return "gradle";
  }

  private String executeGradleDependencies(Path projectPath, List<String> configurations)
      throws DependencyResolutionException {
    List<String> command = new ArrayList<>();
    command.add(resolveGradleCommand(projectPath));
    command.add("dependencies");
    for (String configuration : configurations) {
      command.add("--configuration");
      command.add(configuration);
    }
    command.add("--console=plain");
    command.add("-q");

    LOG.debug("Executing: {}", String.join(" ", command));

    ProcessResult result = processExecutor.execute(command, projectPath);

    if (result.exitCode() != 0) {
      throw new DependencyResolutionException(
          "gradle dependencies failed with exit code " + result.exitCode()
              + ". stderr: " + result.stderr());
    }
    return result.stdout();
  }

  /**
   * {@code gradle dependencies} の出力をパースする。
   *
   * <p>出力形式例:
   * <pre>
   * runtimeClasspath - Runtime classpath of source set 'main'.
   * +--- org.springframework.boot:spring-boot-starter-web:3.2.0
   * |    +--- org.springframework.boot:spring-boot-starter:3.2.0
   * |    |    +--- org.springframework.boot:spring-boot:3.2.0
   * |    |    |    +--- org.springframework:spring-core:6.1.1
   * |    +--- com.fasterxml.jackson.core:jackson-databind:2.15.3 -> 2.16.0
   * \--- org.apache.commons:commons-lang3:3.14.0
   * </pre>
   *
   * @param output gradle dependencies の出力
   * @param targetConfigurations 対象 configuration リスト
   * @return パースされた依存関係リスト（重複除去・BOM フィルタリング済み）
   */
  List<Dependency> parseGradleDependenciesOutput(String output,
      List<String> targetConfigurations) {
    Set<String> gavSet = new LinkedHashSet<>();
    List<Dependency> result = new ArrayList<>();

    boolean inTargetConfig = false;

    for (String line : output.split("\\R")) {
      // 空行で configuration ブロック終了
      if (line.isBlank()) {
        inTargetConfig = false;
        continue;
      }

      // configuration ヘッダの検出
      Matcher headerMatcher = CONFIG_HEADER_PATTERN.matcher(line);
      if (headerMatcher.matches() && !line.startsWith("|") && !line.startsWith("+")
          && !line.startsWith("\\") && !line.startsWith(" ")) {
        String configName = headerMatcher.group(1);
        inTargetConfig = targetConfigurations.stream()
            .anyMatch(tc -> tc.equalsIgnoreCase(configName));
        continue;
      }

      if (!inTargetConfig) {
        continue;
      }

      Matcher depMatcher = DEP_LINE_PATTERN.matcher(line);
      if (depMatcher.matches()) {
        String groupId = depMatcher.group(1).trim();
        String artifactId = depMatcher.group(2).trim();
        String version = depMatcher.group(3).trim();
        String resolvedVersion = depMatcher.group(4);

        // -> 表記がある場合は解決後のバージョンを採用
        if (resolvedVersion != null && !resolvedVersion.isBlank()) {
          version = resolvedVersion.trim();
        }

        String gav = groupId + ":" + artifactId + ":" + version;
        if (gavSet.add(gav)) {
          Dependency dep = new Dependency(groupId, artifactId, version, "runtime", "jar");
          if (!dep.isBom()) {
            result.add(dep);
          }
        }
      }
    }

    return result;
  }

  /** プロセス実行の抽象化。テスト時にモック可能。 */
  interface ProcessExecutor {
    ProcessResult execute(List<String> command, Path workingDirectory)
        throws DependencyResolutionException;
  }

  /** プロセス実行結果。 */
  record ProcessResult(int exitCode, String stdout, String stderr) {}

  /** 実際の OS プロセスを実行するデフォルト実装。 */
  private static class DefaultProcessExecutor implements ProcessExecutor {
    @Override
    public ProcessResult execute(List<String> command, Path workingDirectory)
        throws DependencyResolutionException {
      try {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDirectory.toFile());
        pb.redirectErrorStream(false);
        Process process = pb.start();

        String stdout;
        String stderr;
        try (BufferedReader outReader =
                new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            BufferedReader errReader =
                new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
          stdout = outReader.lines().collect(Collectors.joining("\n"));
          stderr = errReader.lines().collect(Collectors.joining("\n"));
        }

        int exitCode = process.waitFor();
        return new ProcessResult(exitCode, stdout, stderr);
      } catch (IOException e) {
        throw new DependencyResolutionException(
            "Failed to execute gradle command. Is Gradle installed and on PATH?", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new DependencyResolutionException("gradle command was interrupted", e);
      }
    }
  }
}

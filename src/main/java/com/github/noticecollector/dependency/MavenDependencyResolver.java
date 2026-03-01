package com.github.noticecollector.dependency;

import com.github.noticecollector.config.NoticeCollectorConfig;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maven プロジェクトの依存関係を解決する {@link DependencyResolver} 実装。
 *
 * <p>{@code mvn dependency:list} を実行し、出力をパースして依存関係リストを生成する。 BOM（packaging=pom）は自動的にフィルタリングされる。
 */
public class MavenDependencyResolver implements DependencyResolver {

  private static final Logger LOG = LoggerFactory.getLogger(MavenDependencyResolver.class);

  /**
   * {@code mvn dependency:list} 出力行のパターン。
   *
   * <p>形式: {@code groupId:artifactId:packaging:version:scope}
   */
  private static final Pattern DEP_LINE_PATTERN =
      Pattern.compile(
          "^\\s+([^:]+):([^:]+):([^:]+):([^:]+):([^:]+)\\s*$");

  private final ProcessExecutor processExecutor;

  /** デフォルトコンストラクタ。実際のプロセス実行を使用する。 */
  public MavenDependencyResolver() {
    this(new DefaultProcessExecutor());
  }

  /**
   * テスト用コンストラクタ。プロセス実行をモック可能にする。
   *
   * @param processExecutor プロセス実行の委譲先
   */
  MavenDependencyResolver(ProcessExecutor processExecutor) {
    this.processExecutor = processExecutor;
  }

  @Override
  public List<Dependency> resolve(Path projectPath, NoticeCollectorConfig config)
      throws DependencyResolutionException {
    validateProjectPath(projectPath);

    Path outputFile = createTempOutputFile();
    try {
      List<String> scopes = config.getProject().getMaven().getScopes();
      executeMvnDependencyList(projectPath, outputFile, scopes);
      String output = Files.readString(outputFile, StandardCharsets.UTF_8);
      List<Dependency> deps = parseDependencyListOutput(output);
      LOG.info("Resolved {} dependencies (after BOM filtering) from Maven project at {}",
          deps.size(), projectPath);
      return deps;
    } catch (IOException e) {
      throw new DependencyResolutionException(
          "Failed to read mvn dependency:list output", e);
    } finally {
      deleteQuietly(outputFile);
    }
  }

  private void validateProjectPath(Path projectPath) throws DependencyResolutionException {
    Path pomFile = projectPath.resolve("pom.xml");
    if (!Files.exists(pomFile)) {
      throw new DependencyResolutionException(
          "pom.xml not found in project directory: " + projectPath);
    }
  }

  private Path createTempOutputFile() throws DependencyResolutionException {
    try {
      return Files.createTempFile("mvn-deps-", ".txt");
    } catch (IOException e) {
      throw new DependencyResolutionException("Failed to create temp file for dependency output", e);
    }
  }

  private void executeMvnDependencyList(Path projectPath, Path outputFile, List<String> scopes)
      throws DependencyResolutionException {
    String includeScope = scopes.stream()
        .filter(s -> "runtime".equalsIgnoreCase(s) || "compile".equalsIgnoreCase(s))
        .max((a, b) -> {
          // runtime は compile を包含するため、runtime があれば runtime を使う
          if ("runtime".equalsIgnoreCase(a)) {
            return 1;
          }
          if ("runtime".equalsIgnoreCase(b)) {
            return -1;
          }
          return 0;
        })
        .orElse("runtime");

    List<String> command = new ArrayList<>();
    // Windows では mvn.cmd を使用
    String mvnCommand = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
    command.add(mvnCommand);
    command.add("dependency:list");
    command.add("-DoutputFile=" + outputFile.toAbsolutePath());
    command.add("-DincludeScope=" + includeScope);
    command.add("-DoutputAbsoluteArtifactFilename=false");
    command.add("-B");

    LOG.debug("Executing: {}", String.join(" ", command));

    ProcessResult result = processExecutor.execute(command, projectPath);

    if (result.exitCode() != 0) {
      throw new DependencyResolutionException(
          "mvn dependency:list failed with exit code " + result.exitCode()
              + ". stderr: " + result.stderr());
    }
  }

  /**
   * {@code mvn dependency:list -DoutputFile=...} の出力ファイルをパースする。
   *
   * <p>出力形式（ファイル出力時）:
   * <pre>
   * The following files have been resolved:
   *    org.slf4j:slf4j-api:jar:2.0.16:compile
   *    ch.qos.logback:logback-classic:jar:1.5.16:runtime
   * </pre>
   *
   * @param output 出力ファイルの内容
   * @return パースされた依存関係リスト（BOM フィルタリング済み）
   */
  List<Dependency> parseDependencyListOutput(String output) {
    return output.lines()
        .map(DEP_LINE_PATTERN::matcher)
        .filter(Matcher::matches)
        .map(this::toDependency)
        .filter(dep -> !dep.isBom())
        .collect(Collectors.toList());
  }

  private Dependency toDependency(Matcher matcher) {
    String groupId = matcher.group(1).trim();
    String artifactId = matcher.group(2).trim();
    String packaging = matcher.group(3).trim();
    String version = matcher.group(4).trim();
    String scope = matcher.group(5).trim();
    return new Dependency(groupId, artifactId, version, scope, packaging);
  }

  private void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      LOG.warn("Failed to delete temp file: {}", path, e);
    }
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
            "Failed to execute mvn command. Is Maven installed and on PATH?", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new DependencyResolutionException("mvn command was interrupted", e);
      }
    }
  }
}

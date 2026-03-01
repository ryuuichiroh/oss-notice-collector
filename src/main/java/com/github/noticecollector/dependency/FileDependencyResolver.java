package com.github.noticecollector.dependency;

import com.github.noticecollector.config.NoticeCollectorConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * テキストファイルから依存関係リストを読み込む {@link DependencyResolver} 実装。
 *
 * <p>ビルドツール（Maven / Gradle）が利用できない環境でのフォールバックとして使用する。
 * ファイルの各行は {@code groupId:artifactId:version} 形式で記述する。
 * 空行および {@code #} で始まるコメント行は無視される。
 */
public class FileDependencyResolver implements DependencyResolver {

  private static final Logger LOG = LoggerFactory.getLogger(FileDependencyResolver.class);

  private final Path depsFile;

  /**
   * @param depsFile 依存関係リストファイルのパス
   */
  public FileDependencyResolver(Path depsFile) {
    this.depsFile = depsFile;
  }

  @Override
  public List<Dependency> resolve(Path projectPath, NoticeCollectorConfig config)
      throws DependencyResolutionException {
    if (!Files.exists(depsFile)) {
      throw new DependencyResolutionException(
          "依存関係リストファイルが見つかりません: " + depsFile);
    }

    try {
      List<String> lines = Files.readAllLines(depsFile, StandardCharsets.UTF_8);
      List<Dependency> deps = lines.stream()
          .map(String::trim)
          .filter(line -> !line.isEmpty() && !line.startsWith("#"))
          .map(this::parseLine)
          .collect(Collectors.toList());

      LOG.info("Resolved {} dependencies from file: {}", deps.size(), depsFile);
      return deps;
    } catch (IOException e) {
      throw new DependencyResolutionException(
          "依存関係リストファイルの読込に失敗しました: " + depsFile, e);
    } catch (IllegalArgumentException e) {
      throw new DependencyResolutionException(
          "依存関係リストファイルのパースに失敗しました: " + e.getMessage(), e);
    }
  }

  /**
   * {@code groupId:artifactId:version} 形式の行をパースする。
   *
   * @param line 入力行（トリム済み）
   * @return パース結果の Dependency（scope=null, packaging=null）
   * @throws IllegalArgumentException 形式が不正な場合
   */
  private Dependency parseLine(String line) {
    return Dependency.fromGav(line);
  }
}

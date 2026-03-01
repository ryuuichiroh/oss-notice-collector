package com.github.noticecollector.notice;

import com.github.noticecollector.config.ConfigLoader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * NOTICE 検索パターンのローダー。デフォルトパターンを提供し、外部 {@code notice-patterns.yaml}
 * が指定された場合はそのパターンリストで置き換える。
 */
public class NoticePatternLoader {

  private static final Logger logger = LoggerFactory.getLogger(NoticePatternLoader.class);

  /** デフォルトの NOTICE 検索パターン（優先順）。 */
  private static final List<String> DEFAULT_PATTERNS =
      List.of(
          "META-INF/NOTICE",
          "META-INF/NOTICE.txt",
          "META-INF/NOTICE.md",
          "NOTICE",
          "NOTICE.txt",
          "NOTICE.md");

  /**
   * デフォルトの検索パターンリストを返す。
   *
   * @return デフォルトパターンリスト（不変）
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "DEFAULT_PATTERNS は List.of() で生成された不変リストのため安全")
  public List<String> loadDefaults() {
    return DEFAULT_PATTERNS;
  }

  /**
   * 外部定義ファイルからパターンリストを読み込む。外部ファイルが有効な場合、デフォルトパターンを置き換える。
   *
   * @param externalFile 外部 notice-patterns.yaml のパス
   * @return パターンリスト（不変）
   * @throws NoticePatternLoadException 読込失敗時
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "DEFAULT_PATTERNS は List.of() で生成された不変リストのため安全")
  public List<String> load(Path externalFile) throws NoticePatternLoadException {
    try {
      ConfigLoader.validatePath(externalFile.toString());
    } catch (ConfigLoader.ConfigLoadException e) {
      throw new NoticePatternLoadException(
          "パストラバーサルが検出されました: " + externalFile, e);
    }

    if (!Files.exists(externalFile)) {
      throw new NoticePatternLoadException(
          "NOTICE パターンファイルが見つかりません: " + externalFile);
    }

    List<String> patterns = parseYaml(externalFile);
    if (patterns.isEmpty()) {
      logger.warn("外部パターンファイルにパターンが定義されていません。デフォルトを使用します: {}", externalFile);
      return DEFAULT_PATTERNS;
    }

    logger.info("NOTICE パターンを読み込みました（{}件）: {}", patterns.size(), externalFile);
    return Collections.unmodifiableList(patterns);
  }

  /**
   * YAML ファイルをパースし、パターンリストを抽出する。
   *
   * <p>期待するフォーマット:
   * <pre>
   * patterns:
   *   - "META-INF/NOTICE"
   *   - "NOTICE"
   * </pre>
   */
  @SuppressWarnings("unchecked")
  private List<String> parseYaml(Path file) throws NoticePatternLoadException {
    try (InputStream in = Files.newInputStream(file)) {
      Yaml yaml = new Yaml();
      Map<String, Object> root = yaml.load(in);

      if (root == null || !root.containsKey("patterns")) {
        logger.warn("NOTICE パターンファイルに patterns キーがありません: {}", file);
        return List.of();
      }

      Object patternsObj = root.get("patterns");
      if (!(patternsObj instanceof List<?> patternsList)) {
        throw new NoticePatternLoadException(
            "patterns は配列である必要があります: " + file);
      }

      List<String> result = new ArrayList<>();
      for (Object item : patternsList) {
        if (item instanceof String s && !s.isBlank()) {
          result.add(s);
        }
      }
      return result;
    } catch (IOException e) {
      throw new NoticePatternLoadException(
          "NOTICE パターンファイルの読込に失敗しました: " + file, e);
    }
  }

  /** NOTICE パターン読込時の例外。 */
  public static class NoticePatternLoadException extends Exception {
    public NoticePatternLoadException(String message) {
      super(message);
    }

    public NoticePatternLoadException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}

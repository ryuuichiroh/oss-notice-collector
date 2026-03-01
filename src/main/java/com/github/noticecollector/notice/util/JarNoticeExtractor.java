package com.github.noticecollector.notice.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JAR ファイル内から NOTICE ファイルを抽出するユーティリティ。
 *
 * <p>検索パターンリストの先頭から順に JAR エントリを走査し、case-insensitive かつパス完全一致で
 * マッチするエントリが見つかった時点で内容を抽出して返す。
 */
public class JarNoticeExtractor {

  private static final Logger logger = LoggerFactory.getLogger(JarNoticeExtractor.class);

  /**
   * JAR ファイルから検索パターンに一致する NOTICE ファイルを抽出する。
   *
   * <p>パターンリストの先頭から順に検索し、最初に見つかったファイルの内容を返す。
   * マッチングは case-insensitive かつパス完全一致で行う。
   *
   * @param jarPath JAR ファイルのパス
   * @param patterns NOTICE 検索パターンリスト（優先順）
   * @return 見つかった NOTICE の内容。見つからない場合は空の Optional
   * @throws IOException JAR ファイルの読込に失敗した場合
   */
  public Optional<String> extract(Path jarPath, List<String> patterns) throws IOException {
    if (jarPath == null || patterns == null || patterns.isEmpty()) {
      return Optional.empty();
    }

    try (JarFile jarFile = new JarFile(jarPath.toFile())) {
      for (String pattern : patterns) {
        Optional<String> content = findEntry(jarFile, pattern);
        if (content.isPresent()) {
          logger.debug("NOTICE を発見: {} (パターン: {})", jarPath, pattern);
          return content;
        }
      }
    }

    logger.debug("NOTICE が見つかりません: {}", jarPath);
    return Optional.empty();
  }

  /**
   * JAR 内のエントリを走査し、指定パターンに case-insensitive 完全一致するエントリの内容を返す。
   */
  private Optional<String> findEntry(JarFile jarFile, String pattern) throws IOException {
    Enumeration<JarEntry> entries = jarFile.entries();
    while (entries.hasMoreElements()) {
      JarEntry entry = entries.nextElement();
      if (!entry.isDirectory() && entry.getName().equalsIgnoreCase(pattern)) {
        try (InputStream is = jarFile.getInputStream(entry)) {
          String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
          return Optional.of(content);
        }
      }
    }
    return Optional.empty();
  }
}

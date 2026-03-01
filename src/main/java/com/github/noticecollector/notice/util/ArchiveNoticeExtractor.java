package com.github.noticecollector.notice.util;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * tar.gz / zip アーカイブ内から NOTICE ファイルを抽出するユーティリティ。
 *
 * <p>検索パターンリストの先頭から順にアーカイブエントリを走査し、case-insensitive かつ
 * ファイル名部分の完全一致でマッチするエントリが見つかった時点で内容を抽出して返す。
 *
 * <p>tar.gz アーカイブはディレクトリ構造を含むため、エントリパスの末尾（ファイル名部分）で
 * パターンマッチングを行う。zip アーカイブも同様。
 */
public class ArchiveNoticeExtractor {

  private static final Logger logger = LoggerFactory.getLogger(ArchiveNoticeExtractor.class);

  /**
   * アーカイブファイルから検索パターンに一致する NOTICE ファイルを抽出する。
   *
   * <p>ファイル拡張子に基づいてアーカイブ形式を判定し、適切な方法で展開・検索する。
   * パターンリストの先頭から順に検索し、最初に見つかったファイルの内容を返す。
   *
   * @param archivePath アーカイブファイルのパス
   * @param patterns NOTICE 検索パターンリスト（優先順）
   * @return 見つかった NOTICE の内容。見つからない場合は空の Optional
   * @throws IOException アーカイブの読込に失敗した場合
   */
  public Optional<String> extract(Path archivePath, List<String> patterns) throws IOException {
    if (archivePath == null || patterns == null || patterns.isEmpty()) {
      return Optional.empty();
    }

    Path fileNamePath = archivePath.getFileName();
    if (fileNamePath == null) {
      return Optional.empty();
    }

    String fileName = fileNamePath.toString().toLowerCase();
    if (fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz")) {
      return extractFromTarGz(archivePath, patterns);
    } else if (fileName.endsWith(".zip")) {
      return extractFromZip(archivePath, patterns);
    }

    logger.warn("未対応のアーカイブ形式: {}", archivePath);
    return Optional.empty();
  }

  /**
   * tar.gz アーカイブから NOTICE ファイルを抽出する。
   *
   * <p>tar.gz はストリーム処理のため、パターンごとに再走査が必要。効率のため、
   * 全エントリを1回走査し、最も優先度の高いパターンにマッチしたものを返す。
   */
  private Optional<String> extractFromTarGz(Path archivePath, List<String> patterns)
      throws IOException {
    String bestContent = null;
    int bestPatternIndex = Integer.MAX_VALUE;

    try (InputStream fis = java.nio.file.Files.newInputStream(archivePath);
        BufferedInputStream bis = new BufferedInputStream(fis);
        GzipCompressorInputStream gzis = new GzipCompressorInputStream(bis);
        TarArchiveInputStream tais = new TarArchiveInputStream(gzis)) {

      TarArchiveEntry entry;
      while ((entry = tais.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        int matchIndex = matchPatternIndex(entry.getName(), patterns);
        if (matchIndex >= 0 && matchIndex < bestPatternIndex) {
          bestContent = new String(tais.readAllBytes(), StandardCharsets.UTF_8);
          bestPatternIndex = matchIndex;
          if (bestPatternIndex == 0) {
            break; // 最優先パターンに一致したので早期終了
          }
        }
      }
    }

    if (bestContent != null) {
      logger.debug("NOTICE を発見（tar.gz）: {} (パターン: {})", archivePath,
          patterns.get(bestPatternIndex));
      return Optional.of(bestContent);
    }

    logger.debug("NOTICE が見つかりません（tar.gz）: {}", archivePath);
    return Optional.empty();
  }

  /**
   * zip アーカイブから NOTICE ファイルを抽出する。
   */
  private Optional<String> extractFromZip(Path archivePath, List<String> patterns)
      throws IOException {
    try (ZipFile zipFile = new ZipFile(archivePath.toFile())) {
      // パターン優先順で検索
      for (String pattern : patterns) {
        var entries = zipFile.entries();
        while (entries.hasMoreElements()) {
          ZipEntry entry = entries.nextElement();
          if (!entry.isDirectory() && matchesPattern(entry.getName(), pattern)) {
            try (InputStream is = zipFile.getInputStream(entry)) {
              String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
              logger.debug("NOTICE を発見（zip）: {} (パターン: {})", archivePath, pattern);
              return Optional.of(content);
            }
          }
        }
      }
    }

    logger.debug("NOTICE が見つかりません（zip）: {}", archivePath);
    return Optional.empty();
  }

  /**
   * エントリパスが検索パターンに一致するかを判定する。
   *
   * <p>case-insensitive でエントリパスの末尾がパターンと完全一致するかを確認する。
   * アーカイブ内のエントリはディレクトリプレフィックスを含むため（例:
   * {@code commons-lang3-3.14.0/META-INF/NOTICE.txt}）、末尾一致で判定する。
   * ただし、パターンがパス区切りを含む場合（例: {@code META-INF/NOTICE}）は
   * その部分全体で末尾一致を確認する。
   */
  static boolean matchesPattern(String entryPath, String pattern) {
    if (entryPath == null || pattern == null) {
      return false;
    }
    // 正規化: バックスラッシュをスラッシュに統一
    String normalizedEntry = entryPath.replace('\\', '/');
    String normalizedPattern = pattern.replace('\\', '/');

    // case-insensitive で末尾一致（パス区切り境界を考慮）
    String entryLower = normalizedEntry.toLowerCase();
    String patternLower = normalizedPattern.toLowerCase();

    if (entryLower.equals(patternLower)) {
      return true;
    }
    // 末尾一致かつパス区切り境界であること
    return entryLower.endsWith("/" + patternLower);
  }

  /**
   * エントリパスが検索パターンリストのどのインデックスに一致するかを返す。
   *
   * @return 一致したパターンのインデックス。一致しない場合は -1
   */
  private int matchPatternIndex(String entryPath, List<String> patterns) {
    for (int i = 0; i < patterns.size(); i++) {
      if (matchesPattern(entryPath, patterns.get(i))) {
        return i;
      }
    }
    return -1;
  }
}

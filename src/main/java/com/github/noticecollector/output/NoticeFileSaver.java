package com.github.noticecollector.output;

import com.github.noticecollector.dependency.Dependency;
import com.github.noticecollector.model.CollectionResult;
import com.github.noticecollector.model.CollectionStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SUCCESS の NOTICE / LICENSE ファイルを
 * {@code output/legals/{groupId}/{artifactId}/{version}/NOTICE} および
 * {@code output/legals/{groupId}/{artifactId}/{version}/LICENSE}
 * に保存する。ディレクトリが存在しない場合は自動作成する。
 */
public class NoticeFileSaver {

  private static final Logger LOG = LoggerFactory.getLogger(NoticeFileSaver.class);
  private static final String NOTICES_DIR = "legals";
  private static final String NOTICE_FILE_NAME = "NOTICE";
  private static final String LICENSE_FILE_NAME = "LICENSE";

  private final Path outputDirectory;

  /**
   * @param outputDirectory 出力ルートディレクトリ（例: {@code output}）
   */
  public NoticeFileSaver(Path outputDirectory) {
    this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
  }

  /**
   * SUCCESS の収集結果リストから NOTICE / LICENSE ファイルを保存する。
   *
   * @param results 収集結果リスト
   * @return 保存先パスが設定された更新済み CollectionResult リスト
   * @throws IOException ファイル書き込みに失敗した場合
   */
  public List<CollectionResult> saveAll(List<CollectionResult> results) throws IOException {
    Objects.requireNonNull(results, "results must not be null");
    List<CollectionResult> updated = new ArrayList<>(results.size());
    for (CollectionResult result : results) {
      if (result.status() == CollectionStatus.SUCCESS
          && (result.noticeContent() != null || result.licenseContent() != null)) {
        Path noticeSaved = null;
        Path licenseSaved = null;
        if (result.noticeContent() != null) {
          noticeSaved = saveFile(result.dependency(), NOTICE_FILE_NAME, result.noticeContent());
        }
        if (result.licenseContent() != null) {
          licenseSaved = saveFile(result.dependency(), LICENSE_FILE_NAME, result.licenseContent());
        }
        updated.add(new CollectionResult(
            result.dependency(),
            result.spdxId(),
            result.status(),
            result.sourceName(),
            result.sourceUrl(),
            noticeSaved,
            result.noticeContent(),
            result.licenseContent(),
            licenseSaved,
            result.failureReason()));
      } else {
        updated.add(result);
      }
    }
    return updated;
  }

  /**
   * 指定ファイル名で内容を保存する。
   *
   * @param dep 依存ライブラリ情報
   * @param fileName 保存ファイル名（NOTICE または LICENSE）
   * @param content ファイル内容
   * @return 保存先パス
   * @throws IOException ファイル書き込みに失敗した場合
   */
  Path saveFile(Dependency dep, String fileName, String content) throws IOException {
    Path filePath = buildFilePath(dep, fileName);
    Path parentDir = filePath.getParent();
    if (parentDir != null) {
      Files.createDirectories(parentDir);
    }
    Files.writeString(filePath, content, StandardCharsets.UTF_8);
    LOG.info("Saved {}: {}", fileName, filePath);
    return filePath;
  }

  /**
  * 保存先パスを構築する: {@code {outputDirectory}/legals/{groupId}/{artifactId}/{version}/{fileName}}
   */
  Path buildFilePath(Dependency dep, String fileName) {
    return outputDirectory
        .resolve(NOTICES_DIR)
        .resolve(dep.groupId())
        .resolve(dep.artifactId())
        .resolve(dep.version())
        .resolve(fileName);
  }

  // 下位互換のため残す
  Path buildNoticePath(Dependency dep) {
    return buildFilePath(dep, NOTICE_FILE_NAME);
  }
}

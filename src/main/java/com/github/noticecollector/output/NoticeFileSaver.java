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
 * SUCCESS の NOTICE ファイルを {@code output/notices/{groupId}/{artifactId}/{version}/NOTICE}
 * に保存する。ディレクトリが存在しない場合は自動作成する。
 */
public class NoticeFileSaver {

  private static final Logger LOG = LoggerFactory.getLogger(NoticeFileSaver.class);
  private static final String NOTICES_DIR = "notices";
  private static final String NOTICE_FILE_NAME = "NOTICE";

  private final Path outputDirectory;

  /**
   * @param outputDirectory 出力ルートディレクトリ（例: {@code output}）
   */
  public NoticeFileSaver(Path outputDirectory) {
    this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
  }

  /**
   * SUCCESS の収集結果リストから NOTICE ファイルを保存する。
   *
   * @param results 収集結果リスト
   * @return 保存先パスが設定された更新済み CollectionResult リスト
   * @throws IOException ファイル書き込みに失敗した場合
   */
  public List<CollectionResult> saveAll(List<CollectionResult> results) throws IOException {
    Objects.requireNonNull(results, "results must not be null");
    List<CollectionResult> updated = new ArrayList<>(results.size());
    for (CollectionResult result : results) {
      if (result.status() == CollectionStatus.SUCCESS && result.noticeContent() != null) {
        Path saved = save(result);
        updated.add(new CollectionResult(
            result.dependency(),
            result.spdxId(),
            result.status(),
            result.sourceName(),
            result.sourceUrl(),
            saved,
            result.noticeContent(),
            result.failureReason()));
      } else {
        updated.add(result);
      }
    }
    return updated;
  }

  /**
   * 単一の収集結果の NOTICE ファイルを保存する。
   *
   * @param result SUCCESS の収集結果
   * @return 保存先パス
   * @throws IOException ファイル書き込みに失敗した場合
   */
  Path save(CollectionResult result) throws IOException {
    Dependency dep = result.dependency();
    Path noticePath = buildNoticePath(dep);
    Path parentDir = noticePath.getParent();
    if (parentDir != null) {
      Files.createDirectories(parentDir);
    }
    Files.writeString(noticePath, result.noticeContent(), StandardCharsets.UTF_8);
    LOG.info("Saved NOTICE: {}", noticePath);
    return noticePath;
  }

  /**
   * 保存先パスを構築する: {@code {outputDirectory}/notices/{groupId}/{artifactId}/{version}/NOTICE}
   */
  Path buildNoticePath(Dependency dep) {
    return outputDirectory
        .resolve(NOTICES_DIR)
        .resolve(dep.groupId())
        .resolve(dep.artifactId())
        .resolve(dep.version())
        .resolve(NOTICE_FILE_NAME);
  }
}

package com.github.noticecollector.output;

import com.github.noticecollector.dependency.Dependency;
import com.github.noticecollector.model.CollectionResult;
import com.github.noticecollector.model.CollectionStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SUCCESS の全 NOTICE を連結し {@code THIRD-PARTY-NOTICES.txt} を生成する。
 *
 * <p>各エントリにはヘッダ（artifactId、version、groupId、取得元 URL）と区切り線を付与する。
 */
public class NoticeAggregator {

  private static final Logger LOG = LoggerFactory.getLogger(NoticeAggregator.class);

  static final String SEPARATOR =
      "================================================================================";

  private final Path outputDirectory;
  private final String aggregatedFileName;

  /**
   * @param outputDirectory 出力ルートディレクトリ
   * @param aggregatedFileName 集約ファイル名（例: {@code THIRD-PARTY-NOTICES.txt}）
   */
  public NoticeAggregator(Path outputDirectory, String aggregatedFileName) {
    this.outputDirectory =
        Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
    this.aggregatedFileName =
        Objects.requireNonNull(aggregatedFileName, "aggregatedFileName must not be null");
  }

  /**
   * SUCCESS の収集結果から集約 NOTICE ファイルを生成する。
   *
   * @param results 収集結果リスト
   * @throws IOException ファイル書き込みに失敗した場合
   */
  public void aggregate(List<CollectionResult> results) throws IOException {
    Objects.requireNonNull(results, "results must not be null");

    String content = buildContent(results);

    Files.createDirectories(outputDirectory);
    Path outputPath = outputDirectory.resolve(aggregatedFileName);
    Files.writeString(outputPath, content, StandardCharsets.UTF_8);
    LOG.info("Generated aggregated NOTICE: {}", outputPath);
  }

  /**
   * 収集結果から集約 NOTICE の文字列を構築する（テスト用にパッケージプライベート）。
   */
  String buildContent(List<CollectionResult> results) {
    List<CollectionResult> successResults =
        results.stream()
            .filter(r -> r.status() == CollectionStatus.SUCCESS)
            .filter(r -> r.noticeContent() != null)
            .toList();

    if (successResults.isEmpty()) {
      return "";
    }

    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < successResults.size(); i++) {
      if (i > 0) {
        sb.append(System.lineSeparator());
      }
      CollectionResult result = successResults.get(i);
      appendEntry(sb, result);
    }
    return sb.toString();
  }

  private void appendEntry(StringBuilder sb, CollectionResult result) {
    Dependency dep = result.dependency();

    sb.append(SEPARATOR).append(System.lineSeparator());
    sb.append("artifactId: ").append(dep.artifactId()).append(System.lineSeparator());
    sb.append("version: ").append(dep.version()).append(System.lineSeparator());
    sb.append("groupId: ").append(dep.groupId()).append(System.lineSeparator());
    sb.append("sourceUrl: ").append(result.sourceUrl() != null ? result.sourceUrl() : "N/A");
    sb.append(System.lineSeparator());
    sb.append(SEPARATOR).append(System.lineSeparator());
    sb.append(System.lineSeparator());
    sb.append(result.noticeContent());
    sb.append(System.lineSeparator());
  }
}

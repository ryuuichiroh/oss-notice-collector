package com.github.noticecollector.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.noticecollector.license.LicensedDependency;
import com.github.noticecollector.model.CollectionResult;
import com.github.noticecollector.model.CollectionStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jackson による JSON レポート（{@code collection-report.json}）を生成する。
 *
 * <p>サマリ情報（総依存数、Apache-2.0 数、各ステータス数）と、全ての依存関係の
 * 収集結果を含む（Apache-2.0、UNKNOWN_LICENSE、その他のライセンスを含む）。
 */
public class ReportGenerator {

  private static final Logger LOG = LoggerFactory.getLogger(ReportGenerator.class);

  private final Path outputDirectory;
  private final String reportFileName;
  private final ObjectMapper objectMapper;

  /**
   * @param outputDirectory 出力ルートディレクトリ
   * @param reportFileName レポートファイル名（例: {@code collection-report.json}）
   */
  public ReportGenerator(Path outputDirectory, String reportFileName) {
    this.outputDirectory =
        Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
    this.reportFileName =
        Objects.requireNonNull(reportFileName, "reportFileName must not be null");
    this.objectMapper = new ObjectMapper();
    this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
  }

  /**
   * JSON レポートを生成してファイルに書き出す。
   *
   * @param results 全依存関係の収集結果リスト（Apache-2.0、UNKNOWN_LICENSE、その他のライセンスを含む）
   * @param allDependencies 全依存関係リスト（サマリ集計用）
   * @throws IOException ファイル書き込みに失敗した場合
   */
  public void generate(List<CollectionResult> results, List<LicensedDependency> allDependencies)
      throws IOException {
    Objects.requireNonNull(results, "results must not be null");
    Objects.requireNonNull(allDependencies, "allDependencies must not be null");

    Report report = buildReport(results, allDependencies);

    Files.createDirectories(outputDirectory);
    Path reportPath = outputDirectory.resolve(reportFileName);
    objectMapper.writeValue(reportPath.toFile(), report);
    LOG.info("Generated report: {}", reportPath);
  }

  /**
   * 収集結果からレポートオブジェクトを構築する（テスト用にパッケージプライベート）。
   */
  Report buildReport(List<CollectionResult> results, List<LicensedDependency> allDependencies) {
    Summary summary = buildSummary(results, allDependencies);
    List<ResultEntry> entries = results.stream().map(ReportGenerator::toEntry).toList();
    return new Report(summary, entries);
  }

  private Summary buildSummary(
      List<CollectionResult> results, List<LicensedDependency> allDependencies) {
    int totalDependencies = allDependencies.size();
    long apache2Count = allDependencies.stream().filter(LicensedDependency::isApache2).count();

    long successCount =
        results.stream().filter(r -> r.status() == CollectionStatus.SUCCESS).count();
    long notRequiredCount =
        results.stream().filter(r -> r.status() == CollectionStatus.NOT_REQUIRED).count();
    long failedCount =
        results.stream().filter(r -> r.status() == CollectionStatus.FAILED).count();
    long unknownLicenseCount =
        results.stream().filter(r -> r.status() == CollectionStatus.UNKNOWN_LICENSE).count();
    long notApache2Count =
        results.stream().filter(r -> r.status() == CollectionStatus.NOT_APACHE_2_0).count();

    return new Summary(
        totalDependencies,
        (int) apache2Count,
        (int) successCount,
        (int) notRequiredCount,
        (int) failedCount,
        (int) unknownLicenseCount,
        (int) notApache2Count);
  }

  private static ResultEntry toEntry(CollectionResult result) {
    return new ResultEntry(
        result.dependency().groupId(),
        result.dependency().artifactId(),
        result.dependency().version(),
        result.spdxId(),
        result.status().name(),
        result.sourceName(),
        result.sourceUrl(),
        result.savedPath() != null ? result.savedPath().toString() : null,
        result.failureReason(),
        result.status() == CollectionStatus.NOT_REQUIRED ? result.sourceUrl() : null,
        result.status() == CollectionStatus.NOT_REQUIRED ? buildNotRequiredReason(result) : null);
  }

  private static String buildNotRequiredReason(CollectionResult result) {
    if (result.sourceName() != null) {
      return "Source found via " + result.sourceName() + " but no NOTICE file present";
    }
    return "Source found but no NOTICE file present";
  }

  /** JSON レポートのルートオブジェクト。 */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Report(@JsonProperty("summary") Summary summary, @JsonProperty("results") List<ResultEntry> results) {}

  /** サマリ情報。 */
  record Summary(
      @JsonProperty("totalDependencies") int totalDependencies,
      @JsonProperty("apache2Count") int apache2Count,
      @JsonProperty("successCount") int successCount,
      @JsonProperty("notRequiredCount") int notRequiredCount,
      @JsonProperty("failedCount") int failedCount,
      @JsonProperty("unknownLicenseCount") int unknownLicenseCount,
      @JsonProperty("notApache2Count") int notApache2Count) {}

  /** 個別の収集結果エントリ。 */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ResultEntry(
      @JsonProperty("groupId") String groupId,
      @JsonProperty("artifactId") String artifactId,
      @JsonProperty("version") String version,
      @JsonProperty("spdxId") String spdxId,
      @JsonProperty("status") String status,
      @JsonProperty("sourceName") String sourceName,
      @JsonProperty("sourceUrl") String sourceUrl,
      @JsonProperty("savedPath") String savedPath,
      @JsonProperty("failureReason") String failureReason,
      @JsonProperty("notRequiredSourceUrl") String notRequiredSourceUrl,
      @JsonProperty("notRequiredReason") String notRequiredReason) {}
}

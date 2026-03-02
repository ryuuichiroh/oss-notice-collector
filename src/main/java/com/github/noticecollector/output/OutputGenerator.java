package com.github.noticecollector.output;

import com.github.noticecollector.config.NoticeCollectorConfig;
import com.github.noticecollector.license.LicensedDependency;
import com.github.noticecollector.model.CollectionResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NoticeFileSaver、ReportGenerator、NoticeAggregator を統合し、
 * 収集結果を各種形式で出力するオーケストレータ。
 */
public class OutputGenerator {

  private static final Logger LOG = LoggerFactory.getLogger(OutputGenerator.class);

  private final NoticeFileSaver noticeFileSaver;
  private final ReportGenerator reportGenerator;
  private final NoticeAggregator noticeAggregator;

  /**
   * 設定から OutputGenerator を構築する。
   *
   * @param config 出力設定を含む NoticeCollectorConfig
   */
  public OutputGenerator(NoticeCollectorConfig config) {
    Objects.requireNonNull(config, "config must not be null");
    Path outputDir = Path.of(config.getOutput().getDirectory());
    this.noticeFileSaver = new NoticeFileSaver(outputDir);
    this.reportGenerator = new ReportGenerator(outputDir, config.getOutput().getReportFile());
    this.noticeAggregator = new NoticeAggregator(outputDir, config.getOutput().getAggregatedFile());
  }

  /**
   * テスト用コンストラクタ。各コンポーネントを直接注入する。
   */
  OutputGenerator(NoticeFileSaver noticeFileSaver, ReportGenerator reportGenerator,
      NoticeAggregator noticeAggregator) {
    this.noticeFileSaver = Objects.requireNonNull(noticeFileSaver);
    this.reportGenerator = Objects.requireNonNull(reportGenerator);
    this.noticeAggregator = Objects.requireNonNull(noticeAggregator);
  }

  /**
   * 収集結果を出力する。
   *
   * <ol>
   *   <li>SUCCESS の NOTICE ファイルを個別に保存</li>
   *   <li>JSON レポート（collection-report.json）を生成</li>
  *   <li>集約 NOTICE ファイル（THIRD-PARTY-LEGAL.txt）を生成</li>
   * </ol>
   *
   * @param results 収集結果リスト
   * @param allDependencies 全依存関係リスト（サマリ用）
   * @param config 出力設定
   * @throws IOException 出力処理に失敗した場合
   */
  public void generateOutput(List<CollectionResult> results,
      List<LicensedDependency> allDependencies,
      NoticeCollectorConfig config) throws IOException {
    Objects.requireNonNull(results, "results must not be null");
    Objects.requireNonNull(allDependencies, "allDependencies must not be null");

    LOG.info("Starting output generation for {} results", results.size());

    // 1. 個別 NOTICE ファイルの保存
    List<CollectionResult> updatedResults = noticeFileSaver.saveAll(results);

    // 2. JSON レポートの生成
    reportGenerator.generate(updatedResults, allDependencies);

    // 3. 集約 NOTICE ファイルの生成
    noticeAggregator.aggregate(updatedResults);

    LOG.info("Output generation completed");
  }
}

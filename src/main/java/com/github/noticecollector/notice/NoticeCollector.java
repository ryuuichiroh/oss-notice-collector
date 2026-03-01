package com.github.noticecollector.notice;

import com.github.noticecollector.dependency.Dependency;
import com.github.noticecollector.license.LicensedDependency;
import com.github.noticecollector.model.CollectionResult;
import com.github.noticecollector.model.CollectionStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NOTICE 収集オーケストレータ。7段階の優先順位に従って {@link NoticeSource} を試行し、
 * 各依存関係に対する {@link CollectionResult} を生成する。
 *
 * <p>検索ロジック:
 * <ol>
 *   <li>UNKNOWN_LICENSE の依存関係は即座に {@link CollectionStatus#UNKNOWN_LICENSE} を返す</li>
 *   <li>優先順位の昇順で各 NoticeSource を試行する</li>
 *   <li>いずれかのソースが FOUND を返した時点で検索を終了する（早期終了）</li>
 *   <li>全ソース試行後、SOURCE_FOUND_NO_NOTICE が1つ以上あれば NOT_REQUIRED</li>
 *   <li>全ソースが NOT_FOUND または ERROR の場合は FAILED</li>
 * </ol>
 */
public class NoticeCollector {

  private static final Logger LOG = LoggerFactory.getLogger(NoticeCollector.class);

  private final List<NoticeSource> sources;
  private final List<String> patterns;

  /**
   * NoticeCollector を構築する。
   *
   * @param sources NoticeSource 実装のリスト（優先順位でソートされる）
   * @param patterns NOTICE 検索パターンリスト
   */
  public NoticeCollector(List<NoticeSource> sources, List<String> patterns) {
    this.sources = sources.stream()
        .sorted(Comparator.comparingInt(NoticeSource::getPriority))
        .toList();
    this.patterns = List.copyOf(patterns);
  }

  /**
   * 依存関係リストに対して NOTICE ファイルを収集する。
   *
   * <p>Apache-2.0 の依存関係に対しては7段階の優先順位で検索を行い、
   * UNKNOWN_LICENSE の依存関係に対しては即座に UNKNOWN_LICENSE ステータスを返す。
   *
   * @param dependencies ライセンス付き依存関係リスト（Apache-2.0 および UNKNOWN_LICENSE を含む）
   * @return 収集結果リスト
   */
  public List<CollectionResult> collectNotices(List<LicensedDependency> dependencies) {
    List<CollectionResult> results = new ArrayList<>();
    for (LicensedDependency dep : dependencies) {
      if (dep.isUnknown()) {
        results.add(buildUnknownLicenseResult(dep));
      } else {
        results.add(collectForDependency(dep));
      }
    }
    return results;
  }

  /**
   * 単一の依存関係に対して NOTICE を収集する。優先順位順に各ソースを試行し、
   * 最初に FOUND が返された時点で早期終了する。
   */
  private CollectionResult collectForDependency(LicensedDependency dep) {
    Dependency dependency = dep.dependency();
    String gav = dependency.toGav();
    LOG.info("NOTICE 収集開始: {}", gav);

    boolean hasSourceFoundNoNotice = false;
    String lastSourceUrl = null;
    String lastSourceName = null;

    for (NoticeSource source : sources) {
      LOG.debug("ソース試行: {} (優先順位 {}) - {}", source.getSourceName(),
          source.getPriority(), gav);

      NoticeSearchResult result = source.search(dep, patterns);

      switch (result.outcome()) {
        case FOUND:
          LOG.info("NOTICE 発見: {} からの取得 ({})", source.getSourceName(), gav);
          return new CollectionResult(
              dependency,
              dep.spdxId(),
              CollectionStatus.SUCCESS,
              source.getSourceName(),
              result.sourceUrl(),
              null,
              result.noticeContent(),
              null);

        case SOURCE_FOUND_NO_NOTICE:
          LOG.debug("ソース発見・NOTICE なし: {} ({})", source.getSourceName(), gav);
          hasSourceFoundNoNotice = true;
          // 最初に SOURCE_FOUND_NO_NOTICE を返したソースを記録する（上書きしない）
          if (lastSourceName == null) {
            lastSourceUrl = result.sourceUrl();
            lastSourceName = source.getSourceName();
          }
          break;

        case NOT_FOUND:
          LOG.debug("ソース未発見: {} ({})", source.getSourceName(), gav);
          break;

        case ERROR:
          LOG.warn("ソースエラー: {} ({}) - {}", source.getSourceName(), gav,
              result.message());
          break;

        default:
          break;
      }
    }

    // 全ソース試行完了 — ステータス判定
    if (hasSourceFoundNoNotice) {
      LOG.info("NOTICE 不要と判定: {} (ソース発見済み・NOTICE なし)", gav);
      return new CollectionResult(
          dependency,
          dep.spdxId(),
          CollectionStatus.NOT_REQUIRED,
          lastSourceName,
          lastSourceUrl,
          null,
          null,
          null);
    }

    LOG.warn("NOTICE 収集失敗: {} (全ソースで未発見)", gav);
    return new CollectionResult(
        dependency,
        dep.spdxId(),
        CollectionStatus.FAILED,
        null,
        null,
        null,
        null,
        "全ての検索ソースで NOTICE ファイルが見つかりませんでした");
  }

  /** UNKNOWN_LICENSE の依存関係に対する CollectionResult を生成する。 */
  private CollectionResult buildUnknownLicenseResult(LicensedDependency dep) {
    LOG.info("ライセンス不明のため NOTICE 収集スキップ: {}", dep.dependency().toGav());
    return new CollectionResult(
        dep.dependency(),
        dep.spdxId(),
        CollectionStatus.UNKNOWN_LICENSE,
        null,
        null,
        null,
        null,
        "ライセンスが特定できないため NOTICE 収集をスキップしました");
  }
}

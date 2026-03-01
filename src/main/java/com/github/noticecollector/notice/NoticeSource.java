package com.github.noticecollector.notice;

import com.github.noticecollector.license.LicensedDependency;
import java.util.List;

/**
 * NOTICE ファイルの検索ソースを表す Strategy インターフェース。
 *
 * <p>各実装は特定のソース（ローカルキャッシュ、Maven Central、GitHub 等）から NOTICE ファイルを検索する。
 * NoticeCollector は {@link #getPriority()} の昇順で各ソースを試行し、最初に見つかった NOTICE を採用する。
 */
public interface NoticeSource {

  /**
   * 指定された依存関係の NOTICE ファイルを検索する。
   *
   * @param dependency 対象の依存関係
   * @param patterns NOTICE 検索パターンリスト（優先順）
   * @return 検索結果
   */
  NoticeSearchResult search(LicensedDependency dependency, List<String> patterns);

  /**
   * このソースの優先順位を返す（1〜7）。値が小さいほど優先度が高い。
   *
   * @return 優先順位
   */
  int getPriority();

  /**
   * このソースの名前を返す（レポート用）。
   *
   * @return ソース名（例: "USER_OVERRIDE", "LOCAL_CACHE"）
   */
  String getSourceName();
}

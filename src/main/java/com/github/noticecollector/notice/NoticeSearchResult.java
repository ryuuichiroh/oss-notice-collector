package com.github.noticecollector.notice;

/**
 * NoticeSource による NOTICE 検索の結果を保持するモデル。
 *
 * @param outcome 検索結果の種別
 * @param noticeContent NOTICE 内容（FOUND の場合のみ非 null）
 * @param sourceUrl 取得元 URL
 * @param message 補足メッセージ
 */
public record NoticeSearchResult(
    SearchOutcome outcome, String noticeContent, String sourceUrl, String message) {

  /** NOTICE 検索結果の種別。 */
  public enum SearchOutcome {
    /** NOTICE ファイルを発見した。 */
    FOUND,
    /** ソース（JAR/リポジトリ等）は見つかったが NOTICE が無い。 */
    SOURCE_FOUND_NO_NOTICE,
    /** ソース自体が見つからない。 */
    NOT_FOUND,
    /** エラーが発生した（リトライ上限超過等）。 */
    ERROR
  }
}

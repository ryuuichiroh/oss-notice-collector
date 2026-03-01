package com.github.noticecollector.model;

/** 各依存関係に対する NOTICE 収集結果のステータス。 */
/** 各依存関係に対する NOTICE 収集結果のステータス。 */
public enum CollectionStatus {
  /** NOTICE ファイルを収集できた。 */
  SUCCESS,
  /** ソースは見つかったが NOTICE ファイルが存在しない。 */
  NOT_REQUIRED,
  /** 全ソースで検索失敗。 */
  FAILED,
  /** ライセンスを判定できなかった。 */
  UNKNOWN_LICENSE,
  /** Apache-2.0 以外のライセンスのため NOTICE 収集対象外。 */
  NOT_APACHE_2_0
}


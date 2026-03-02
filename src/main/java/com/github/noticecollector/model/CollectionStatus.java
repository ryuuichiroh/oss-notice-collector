package com.github.noticecollector.model;

/** 各依存関係に対する NOTICE/LICENSE 収集結果のステータス。 */
public enum CollectionStatus {
  /** NOTICE または LICENSE ファイルを収集できた。 */
  SUCCESS,
  /** ソースは見つかったが NOTICE/LICENSE ファイルが存在しない。 */
  NOT_REQUIRED,
  /** 全ソースで検索失敗。 */
  FAILED,
  /** ライセンスを判定できなかった。 */
  UNKNOWN_LICENSE,
  /** targetLicenses で指定されたライセンス以外のため収集対象外。 */
  NOT_TARGET_LICENSE
}


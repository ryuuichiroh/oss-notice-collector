package com.github.noticecollector.model;

import com.github.noticecollector.dependency.Dependency;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 各依存関係に対する NOTICE/LICENSE 収集結果を保持するモデル。
 *
 * @param dependency 対象の依存関係
 * @param spdxId SPDX ライセンス識別子
 * @param status 収集ステータス
 * @param sourceName 取得元ソース名（"USER_OVERRIDE", "LOCAL_CACHE", "MAVEN_CENTRAL_SOURCE_JAR" 等）
 * @param sourceUrl 取得元 URL（null 可）
 * @param noticeSavedPath NOTICE 保存先パス（null 可）
 * @param noticeContent NOTICE ファイルの内容（null 可）
 * @param licenseContent LICENSE ファイルの内容（null 可）
 * @param licenseSavedPath LICENSE 保存先パス（null 可）
 * @param failureReason 失敗理由（null 可）
 */
public record CollectionResult(
    Dependency dependency,
    String spdxId,
    CollectionStatus status,
    String sourceName,
    String sourceUrl,
    Path noticeSavedPath,
    String noticeContent,
    String licenseContent,
    Path licenseSavedPath,
    String failureReason) {

  public CollectionResult {
    Objects.requireNonNull(dependency, "dependency must not be null");
    Objects.requireNonNull(status, "status must not be null");
  }
}

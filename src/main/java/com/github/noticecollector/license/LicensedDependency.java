package com.github.noticecollector.license;

import com.github.noticecollector.dependency.Dependency;
import java.util.Objects;

/**
 * ライセンス情報が付与された依存関係モデル。
 *
 * @param dependency 元の依存関係
 * @param spdxId SPDX ライセンス識別子（例: "Apache-2.0", "MIT", "UNKNOWN_LICENSE"）
 * @param rawLicenseName pom.xml に記載されていた元のライセンス名
 * @param licenseUrl pom.xml の {@code <url>} フィールド
 * @param licenseSource ライセンス情報の取得元（"POM", "JAR_META_INF", "GITHUB_API"）
 */
public record LicensedDependency(
    Dependency dependency,
    String spdxId,
    String rawLicenseName,
    String licenseUrl,
    String licenseSource) {

  public LicensedDependency {
    Objects.requireNonNull(dependency, "dependency must not be null");
    Objects.requireNonNull(spdxId, "spdxId must not be null");
  }

  /** ライセンスが Apache-2.0 かどうかを判定する。 */
  public boolean isApache2() {
    return "Apache-2.0".equals(spdxId);
  }

  /** ライセンスが不明かどうかを判定する。 */
  public boolean isUnknown() {
    return "UNKNOWN_LICENSE".equals(spdxId);
  }
}

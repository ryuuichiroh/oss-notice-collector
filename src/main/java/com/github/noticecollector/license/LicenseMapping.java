package com.github.noticecollector.license;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * ライセンス名から SPDX 識別子へのマッピングを管理する。 case-insensitive マッチングおよび URL ベースの補助判定を提供する。
 */
public class LicenseMapping {

  /** UNKNOWN_LICENSE を表す定数。 */
  public static final String UNKNOWN_LICENSE = "UNKNOWN_LICENSE";

  /** ライセンス名（小文字） → SPDX 識別子 のマッピング。 */
  private final Map<String, String> nameToSpdx;

  /** ライセンス URL の部分文字列 → SPDX 識別子 のマッピング。 */
  private final Map<String, String> urlToSpdx;

  private LicenseMapping(Map<String, String> nameToSpdx, Map<String, String> urlToSpdx) {
    this.nameToSpdx = Collections.unmodifiableMap(nameToSpdx);
    this.urlToSpdx = Collections.unmodifiableMap(urlToSpdx);
  }

  /**
   * ライセンス名から SPDX 識別子を解決する（case-insensitive）。
   *
   * @param licenseName pom.xml 等に記載されたライセンス名
   * @return SPDX 識別子。マッピングに一致しない場合は {@code null}
   */
  public String resolveByName(String licenseName) {
    if (licenseName == null || licenseName.isBlank()) {
      return null;
    }
    return nameToSpdx.get(licenseName.toLowerCase(Locale.ROOT).trim());
  }

  /**
   * ライセンス URL から SPDX 識別子を解決する（補助判定）。 URL に既知のパターンが含まれるかを部分一致で判定する。
   *
   * @param licenseUrl pom.xml の {@code <url>} フィールド
   * @return SPDX 識別子。マッピングに一致しない場合は {@code null}
   */
  public String resolveByUrl(String licenseUrl) {
    if (licenseUrl == null || licenseUrl.isBlank()) {
      return null;
    }
    String lowerUrl = licenseUrl.toLowerCase(Locale.ROOT).trim();
    for (Map.Entry<String, String> entry : urlToSpdx.entrySet()) {
      if (lowerUrl.contains(entry.getKey())) {
        return entry.getValue();
      }
    }
    return null;
  }

  /**
   * ライセンス名と URL の両方を使って SPDX 識別子を解決する。 名前マッチングを優先し、一致しない場合に URL 補助判定を行う。 いずれにも一致しない場合は {@link
   * #UNKNOWN_LICENSE} を返す。
   *
   * @param licenseName ライセンス名（null 可）
   * @param licenseUrl ライセンス URL（null 可）
   * @return SPDX 識別子
   */
  public String resolve(String licenseName, String licenseUrl) {
    String byName = resolveByName(licenseName);
    if (byName != null) {
      return byName;
    }
    String byUrl = resolveByUrl(licenseUrl);
    if (byUrl != null) {
      return byUrl;
    }
    return UNKNOWN_LICENSE;
  }

  /** 登録されている名前マッピングの件数を返す。 */
  public int nameCount() {
    return nameToSpdx.size();
  }

  /** 登録されている URL マッピングの件数を返す。 */
  public int urlCount() {
    return urlToSpdx.size();
  }

  /** ビルダーを生成する。 */
  public static Builder builder() {
    return new Builder();
  }

  /** LicenseMapping のビルダー。 */
  public static class Builder {
    private final Map<String, String> nameToSpdx = new HashMap<>();
    private final Map<String, String> urlToSpdx = new HashMap<>();

    /**
     * ライセンス名のリストを SPDX 識別子にマッピングする。 既存のマッピングは上書きされる。
     *
     * @param names ライセンス名のリスト
     * @param spdxId SPDX 識別子
     * @return this
     */
    public Builder addNameMapping(List<String> names, String spdxId) {
      Objects.requireNonNull(names, "names must not be null");
      Objects.requireNonNull(spdxId, "spdxId must not be null");
      for (String name : names) {
        if (name != null && !name.isBlank()) {
          nameToSpdx.put(name.toLowerCase(Locale.ROOT).trim(), spdxId);
        }
      }
      return this;
    }

    /**
     * URL パターンを SPDX 識別子にマッピングする。
     *
     * @param urlPattern URL の部分文字列パターン（小文字で格納）
     * @param spdxId SPDX 識別子
     * @return this
     */
    public Builder addUrlMapping(String urlPattern, String spdxId) {
      Objects.requireNonNull(urlPattern, "urlPattern must not be null");
      Objects.requireNonNull(spdxId, "spdxId must not be null");
      urlToSpdx.put(urlPattern.toLowerCase(Locale.ROOT).trim(), spdxId);
      return this;
    }

    /** LicenseMapping を構築する。 */
    public LicenseMapping build() {
      return new LicenseMapping(nameToSpdx, urlToSpdx);
    }
  }
}

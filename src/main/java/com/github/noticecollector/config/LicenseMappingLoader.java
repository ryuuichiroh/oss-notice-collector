package com.github.noticecollector.config;

import com.github.noticecollector.license.LicenseMapping;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * デフォルト組み込みマッピングと外部 {@code license-mappings.yaml} を読み込み、 {@link LicenseMapping}
 * を構築するローダー。
 */
public class LicenseMappingLoader {

  private static final Logger logger = LoggerFactory.getLogger(LicenseMappingLoader.class);

  /** デフォルトの Apache-2.0 ライセンス名マッピング。 */
  private static final List<String> DEFAULT_APACHE2_NAMES =
      List.of(
          "The Apache Software License, Version 2.0",
          "Apache License, Version 2.0",
          "Apache-2.0",
          "ASL 2.0",
          "Apache 2",
          "Apache 2.0",
          "Apache License 2.0",
          "The Apache License, Version 2.0",
          "Apache License Version 2.0",
          "Apache License v2.0",
          "Apache License, version 2.0",
          "Apache Software License - Version 2.0",
          "Apache-2.0 license");

  /** デフォルトの MIT ライセンス名マッピング。 */
  private static final List<String> DEFAULT_MIT_NAMES =
      List.of("MIT License", "The MIT License", "MIT", "The MIT License (MIT)");

  /** デフォルトの EPL-1.0 ライセンス名マッピング。 */
  private static final List<String> DEFAULT_EPL1_NAMES =
      List.of("Eclipse Public License 1.0", "Eclipse Public License - v 1.0", "EPL-1.0");

  /** デフォルトの EPL-2.0 ライセンス名マッピング。 */
  private static final List<String> DEFAULT_EPL2_NAMES =
      List.of(
          "Eclipse Public License v2.0",
          "Eclipse Public License - v 2.0",
          "Eclipse Public License 2.0",
          "EPL 2.0",
          "EPL-2.0");

  /** デフォルトの LGPL-2.1 ライセンス名マッピング。 */
  private static final List<String> DEFAULT_LGPL21_NAMES =
      List.of(
          "GNU Lesser General Public License, Version 2.1",
          "LGPL 2.1",
          "LGPL-2.1",
          "LGPL-2.1-only");

  /** デフォルトの BSD-2-Clause ライセンス名マッピング。 */
  private static final List<String> DEFAULT_BSD2_NAMES =
      List.of(
          "BSD 2-Clause License",
          "The BSD 2-Clause License",
          "BSD-2-Clause",
          "Simplified BSD License");

  /** デフォルトの BSD-3-Clause ライセンス名マッピング。 */
  private static final List<String> DEFAULT_BSD3_NAMES =
      List.of(
          "BSD 3-Clause License",
          "The BSD 3-Clause License",
          "BSD-3-Clause",
          "New BSD License",
          "Modified BSD License",
          "BSD License 3");

  /** デフォルトの CDDL-1.1 ライセンス名マッピング。 */
  private static final List<String> DEFAULT_CDDL_NAMES =
      List.of(
          "CDDL 1.1",
          "CDDL-1.1",
          "Common Development and Distribution License 1.1",
          "CDDL + GPLv2 with classpath exception");

  /** デフォルトの URL ベース補助判定パターン。 */
  private static final String APACHE2_URL_PATTERN = "apache.org/licenses/license-2.0";

  /**
   * デフォルトマッピングのみで {@link LicenseMapping} を構築する。
   *
   * @return デフォルトマッピングを含む LicenseMapping
   */
  public LicenseMapping loadDefaults() {
    return buildDefaultMapping().build();
  }

  /**
   * デフォルトマッピングに外部定義ファイルをマージして {@link LicenseMapping} を構築する。
   * 外部定義ファイルのマッピングはデフォルトマッピングを上書きする。
   *
   * @param externalFile 外部 license-mappings.yaml のパス
   * @return マージ済み LicenseMapping
   * @throws LicenseMappingLoadException 読込失敗時
   */
  public LicenseMapping load(Path externalFile) throws LicenseMappingLoadException {
    try {
      ConfigLoader.validatePath(externalFile.toString());
    } catch (ConfigLoader.ConfigLoadException e) {
      throw new LicenseMappingLoadException("パストラバーサルが検出されました: " + externalFile, e);
    }

    if (!Files.exists(externalFile)) {
      throw new LicenseMappingLoadException(
          "ライセンスマッピングファイルが見つかりません: " + externalFile);
    }

    LicenseMapping.Builder builder = buildDefaultMapping();
    mergeExternalFile(builder, externalFile);

    logger.info("ライセンスマッピングを読み込みました: {}", externalFile);
    return builder.build();
  }

  /**
   * デフォルトの組み込みマッピングを含むビルダーを返す。
   *
   * @return デフォルトマッピング済みのビルダー
   */
  private LicenseMapping.Builder buildDefaultMapping() {
    return LicenseMapping.builder()
        .addNameMapping(DEFAULT_APACHE2_NAMES, "Apache-2.0")
        .addNameMapping(DEFAULT_MIT_NAMES, "MIT")
        .addNameMapping(DEFAULT_EPL1_NAMES, "EPL-1.0")
        .addNameMapping(DEFAULT_EPL2_NAMES, "EPL-2.0")
        .addNameMapping(DEFAULT_LGPL21_NAMES, "LGPL-2.1-only")
        .addNameMapping(DEFAULT_BSD2_NAMES, "BSD-2-Clause")
        .addNameMapping(DEFAULT_BSD3_NAMES, "BSD-3-Clause")
        .addNameMapping(DEFAULT_CDDL_NAMES, "CDDL-1.1")
        .addUrlMapping(APACHE2_URL_PATTERN, "Apache-2.0")
        .addUrlMapping("opensource.org/licenses/mit", "MIT")
        .addUrlMapping("eclipse.org/legal/epl-2.0", "EPL-2.0")
        .addUrlMapping("eclipse.org/legal/epl-v20", "EPL-2.0")
        .addUrlMapping("eclipse.org/legal/epl-v10", "EPL-1.0")
        .addUrlMapping("gnu.org/licenses/lgpl-2.1", "LGPL-2.1-only")
        .addUrlMapping("opensource.org/licenses/bsd", "BSD-3-Clause");
  }

  /**
   * 外部 YAML ファイルを読み込み、ビルダーにマッピングを追加する。 外部定義は既存のデフォルトマッピングを上書きする。
   *
   * @param builder マッピングビルダー
   * @param externalFile 外部 YAML ファイルパス
   * @throws LicenseMappingLoadException パース失敗時
   */
  @SuppressWarnings("unchecked")
  private void mergeExternalFile(LicenseMapping.Builder builder, Path externalFile)
      throws LicenseMappingLoadException {
    try (InputStream in = Files.newInputStream(externalFile)) {
      Yaml yaml = new Yaml();
      Map<String, Object> root = yaml.load(in);

      if (root == null || !root.containsKey("mappings")) {
        logger.warn("ライセンスマッピングファイルに mappings キーがありません: {}", externalFile);
        return;
      }

      Object mappingsObj = root.get("mappings");
      if (!(mappingsObj instanceof List<?> mappingsList)) {
        throw new LicenseMappingLoadException(
            "mappings は配列である必要があります: " + externalFile);
      }

      for (Object entry : mappingsList) {
        if (!(entry instanceof Map<?, ?> entryMap)) {
          continue;
        }

        Object spdxIdObj = entryMap.get("spdxId");
        Object namesObj = entryMap.get("names");

        if (!(spdxIdObj instanceof String spdxId) || spdxId.isBlank()) {
          logger.warn("spdxId が不正なエントリをスキップします: {}", entry);
          continue;
        }

        if (namesObj instanceof List<?> namesList) {
          List<String> names =
              namesList.stream()
                  .filter(String.class::isInstance)
                  .map(String.class::cast)
                  .toList();
          builder.addNameMapping(names, spdxId);
        }
      }
    } catch (IOException e) {
      throw new LicenseMappingLoadException(
          "ライセンスマッピングファイルの読込に失敗しました: " + externalFile, e);
    }
  }

  /** ライセンスマッピング読込時の例外。 */
  public static class LicenseMappingLoadException extends Exception {
    public LicenseMappingLoadException(String message) {
      super(message);
    }

    public LicenseMappingLoadException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}

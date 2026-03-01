package com.github.noticecollector.license;

import static org.junit.jupiter.api.Assertions.*;

import com.github.noticecollector.config.LicenseMappingLoader;
import com.github.noticecollector.dependency.Dependency;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LicenseIdentifierTest {

  private LicenseMapping defaultMapping;

  @BeforeEach
  void setUp() {
    defaultMapping = new LicenseMappingLoader().loadDefaults();
  }

  @Nested
  @DisplayName("LicenseMapping: 名前ベースの解決")
  class NameResolution {

    @Test
    @DisplayName("デフォルトの Apache-2.0 表記揺れが全て正しくマッピングされる")
    void defaultApache2Mappings() {
      List<String> apache2Names = List.of(
          "The Apache Software License, Version 2.0",
          "Apache License, Version 2.0",
          "Apache-2.0",
          "ASL 2.0",
          "Apache 2",
          "Apache 2.0",
          "Apache License 2.0");

      for (String name : apache2Names) {
        assertEquals("Apache-2.0", defaultMapping.resolveByName(name),
            "マッピング失敗: " + name);
      }
    }

    @Test
    @DisplayName("case-insensitive でマッチングされる")
    void caseInsensitive() {
      assertEquals("Apache-2.0", defaultMapping.resolveByName("apache license, version 2.0"));
      assertEquals("Apache-2.0", defaultMapping.resolveByName("APACHE LICENSE, VERSION 2.0"));
      assertEquals("Apache-2.0", defaultMapping.resolveByName("asl 2.0"));
    }

    @Test
    @DisplayName("マッピングに存在しない名前は null を返す")
    void unknownName() {
      assertNull(defaultMapping.resolveByName("Some Proprietary License"));
    }

    @Test
    @DisplayName("null や空文字は null を返す")
    void nullOrBlank() {
      assertNull(defaultMapping.resolveByName(null));
      assertNull(defaultMapping.resolveByName(""));
      assertNull(defaultMapping.resolveByName("   "));
    }
  }

  @Nested
  @DisplayName("LicenseMapping: URL ベースの補助判定")
  class UrlResolution {

    @Test
    @DisplayName("Apache ライセンス URL から Apache-2.0 を解決する")
    void apacheUrl() {
      assertEquals("Apache-2.0",
          defaultMapping.resolveByUrl("http://www.apache.org/licenses/LICENSE-2.0"));
      assertEquals("Apache-2.0",
          defaultMapping.resolveByUrl("https://www.apache.org/licenses/LICENSE-2.0.txt"));
    }

    @Test
    @DisplayName("未知の URL は null を返す")
    void unknownUrl() {
      assertNull(defaultMapping.resolveByUrl("https://example.com/license"));
    }
  }

  @Nested
  @DisplayName("LicenseMapping: resolve（名前 + URL 統合）")
  class CombinedResolution {

    @Test
    @DisplayName("名前マッチが優先される")
    void nameFirst() {
      assertEquals("Apache-2.0",
          defaultMapping.resolve("Apache-2.0", "https://example.com/unknown"));
    }

    @Test
    @DisplayName("名前が不一致でも URL で解決できる")
    void fallbackToUrl() {
      assertEquals("Apache-2.0",
          defaultMapping.resolve("Unknown Name",
              "http://www.apache.org/licenses/LICENSE-2.0"));
    }

    @Test
    @DisplayName("名前も URL も不一致なら UNKNOWN_LICENSE")
    void unknown() {
      assertEquals(LicenseMapping.UNKNOWN_LICENSE,
          defaultMapping.resolve("Proprietary", "https://example.com/license"));
    }
  }

  @Nested
  @DisplayName("LicenseIdentifier: pom.xml からのライセンス特定")
  class PomIdentification {

    @Test
    @DisplayName("pom.xml の <licenses> から Apache-2.0 を特定する")
    void identifyApache2FromPom() {
      String pomXml = """
          <?xml version="1.0" encoding="UTF-8"?>
          <project>
            <modelVersion>4.0.0</modelVersion>
            <groupId>org.example</groupId>
            <artifactId>test-lib</artifactId>
            <version>1.0.0</version>
            <licenses>
              <license>
                <name>The Apache Software License, Version 2.0</name>
                <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
              </license>
            </licenses>
          </project>
          """;

      Dependency dep = new Dependency("org.example", "test-lib", "1.0.0", "compile", "jar");
      LicenseIdentifier identifier = new LicenseIdentifier(
          defaultMapping, d -> pomXml, d -> null, null, null);

      List<LicensedDependency> results = identifier.identifyLicenses(List.of(dep));

      assertEquals(1, results.size());
      LicensedDependency result = results.get(0);
      assertTrue(result.isApache2());
      assertEquals("Apache-2.0", result.spdxId());
      assertEquals("POM", result.licenseSource());
    }

    @Test
    @DisplayName("pom.xml に <licenses> がない場合は UNKNOWN_LICENSE")
    void noLicensesSection() {
      String pomXml = """
          <?xml version="1.0" encoding="UTF-8"?>
          <project>
            <modelVersion>4.0.0</modelVersion>
            <groupId>org.example</groupId>
            <artifactId>no-license</artifactId>
            <version>1.0.0</version>
          </project>
          """;

      Dependency dep = new Dependency("org.example", "no-license", "1.0.0", "compile", "jar");
      LicenseIdentifier identifier = new LicenseIdentifier(
          defaultMapping, d -> pomXml, d -> null, null, null);

      List<LicensedDependency> results = identifier.identifyLicenses(List.of(dep));

      assertEquals(1, results.size());
      assertTrue(results.get(0).isUnknown());
    }

    @Test
    @DisplayName("pom.xml 取得失敗時は UNKNOWN_LICENSE")
    void pomFetchFailure() {
      Dependency dep = new Dependency("org.example", "fail", "1.0.0", "compile", "jar");
      LicenseIdentifier identifier = new LicenseIdentifier(
          defaultMapping, d -> { throw new java.io.IOException("network error"); },
          d -> null, null, null);

      List<LicensedDependency> results = identifier.identifyLicenses(List.of(dep));

      assertEquals(1, results.size());
      assertTrue(results.get(0).isUnknown());
    }
  }

  @Nested
  @DisplayName("LicenseIdentifier: GitHub API からのライセンス特定")
  class GitHubIdentification {

    @Test
    @DisplayName("pom.xml で不明でも GitHub API で特定できる")
    void fallbackToGithub() {
      Dependency dep = new Dependency("org.example", "gh-lib", "1.0.0", "compile", "jar");
      LicenseIdentifier identifier = new LicenseIdentifier(
          defaultMapping,
          d -> null,
          d -> null,
          d -> new LicenseIdentifier.GitHubLicenseInfo(
              "Apache-2.0", "Apache License 2.0",
              "https://github.com/example/gh-lib/blob/main/LICENSE"),
          null);

      List<LicensedDependency> results = identifier.identifyLicenses(List.of(dep));

      assertEquals(1, results.size());
      assertTrue(results.get(0).isApache2());
      assertEquals("GITHUB_API", results.get(0).licenseSource());
    }
  }

  @Nested
  @DisplayName("LicenseIdentifier: ユーザオーバーライド（spdxId）")
  class UserOverride {

    @Test
    @DisplayName("overrides の spdxId が設定されている場合、それを優先する")
    void overrideSpdxId() {
      com.github.noticecollector.config.NoticeCollectorConfig config =
          new com.github.noticecollector.config.NoticeCollectorConfig();
      com.github.noticecollector.config.NoticeCollectorConfig.OverrideConfig override =
          new com.github.noticecollector.config.NoticeCollectorConfig.OverrideConfig();
      override.setGroupId("log4j");
      override.setArtifactId("log4j");
      override.setSpdxId("Apache-2.0");
      config.setOverrides(List.of(override));

      Dependency dep = new Dependency("log4j", "log4j", "1.1.3", "compile", "jar");
      LicenseIdentifier identifier = new LicenseIdentifier(
          defaultMapping, d -> null, d -> null, null, config);

      LicensedDependency result = identifier.identifyLicense(dep);

      assertEquals("Apache-2.0", result.spdxId());
      assertEquals("USER_OVERRIDE", result.licenseSource());
    }

    @Test
    @DisplayName("version が指定されている場合、バージョンも一致する必要がある")
    void overrideWithVersion() {
      com.github.noticecollector.config.NoticeCollectorConfig config =
          new com.github.noticecollector.config.NoticeCollectorConfig();
      com.github.noticecollector.config.NoticeCollectorConfig.OverrideConfig override =
          new com.github.noticecollector.config.NoticeCollectorConfig.OverrideConfig();
      override.setGroupId("log4j");
      override.setArtifactId("log4j");
      override.setVersion("1.1.3");
      override.setSpdxId("Apache-2.0");
      config.setOverrides(List.of(override));

      LicenseIdentifier identifier = new LicenseIdentifier(
          defaultMapping, d -> null, d -> null, null, config);

      // バージョン一致
      Dependency dep1 = new Dependency("log4j", "log4j", "1.1.3", "compile", "jar");
      LicensedDependency result1 = identifier.identifyLicense(dep1);
      assertEquals("Apache-2.0", result1.spdxId());

      // バージョン不一致
      Dependency dep2 = new Dependency("log4j", "log4j", "1.2.17", "compile", "jar");
      LicensedDependency result2 = identifier.identifyLicense(dep2);
      assertEquals(LicenseMapping.UNKNOWN_LICENSE, result2.spdxId());
    }

    @Test
    @DisplayName("spdxId が未設定の場合、通常のライセンス特定フローに進む")
    void overrideWithoutSpdxId() {
      com.github.noticecollector.config.NoticeCollectorConfig config =
          new com.github.noticecollector.config.NoticeCollectorConfig();
      com.github.noticecollector.config.NoticeCollectorConfig.OverrideConfig override =
          new com.github.noticecollector.config.NoticeCollectorConfig.OverrideConfig();
      override.setGroupId("log4j");
      override.setArtifactId("log4j");
      // spdxId は未設定
      config.setOverrides(List.of(override));

      String pomXml = """
          <project>
            <licenses>
              <license>
                <name>The Apache Software License, Version 2.0</name>
              </license>
            </licenses>
          </project>
          """;

      Dependency dep = new Dependency("log4j", "log4j", "1.2.17", "compile", "jar");
      LicenseIdentifier identifier = new LicenseIdentifier(
          defaultMapping, d -> pomXml, d -> null, null, config);

      LicensedDependency result = identifier.identifyLicense(dep);

      assertEquals("Apache-2.0", result.spdxId());
      assertEquals("POM", result.licenseSource());
    }
  }

  @Nested
  @DisplayName("LicenseIdentifier: LICENSE ファイル内容からの推定")
  class ContentInference {

    private final LicenseIdentifier identifier = new LicenseIdentifier(
        new LicenseMappingLoader().loadDefaults(), d -> null, d -> null, null, null);

    @Test
    @DisplayName("Apache License 2.0 の内容を正しく推定する")
    void inferApache2() {
      String content = """
          Apache License
          Version 2.0, January 2004
          http://www.apache.org/licenses/
          """;
      assertEquals("Apache-2.0", identifier.inferLicenseFromContent(content));
    }

    @Test
    @DisplayName("MIT License の内容を正しく推定する")
    void inferMit() {
      String content = "MIT License\n\nPermission is hereby granted, free...";
      assertEquals("MIT", identifier.inferLicenseFromContent(content));
    }

    @Test
    @DisplayName("判定できない内容は UNKNOWN_LICENSE")
    void inferUnknown() {
      assertEquals(LicenseMapping.UNKNOWN_LICENSE,
          identifier.inferLicenseFromContent("Some custom license text"));
    }
  }

  @Nested
  @DisplayName("LicensedDependency: ユーティリティメソッド")
  class LicensedDependencyTests {

    @Test
    @DisplayName("isApache2 と isUnknown が正しく判定される")
    void statusMethods() {
      Dependency dep = new Dependency("org.example", "lib", "1.0", "compile", "jar");

      LicensedDependency apache = new LicensedDependency(
          dep, "Apache-2.0", "Apache License 2.0", null, "POM");
      assertTrue(apache.isApache2());
      assertFalse(apache.isUnknown());

      LicensedDependency unknown = new LicensedDependency(
          dep, "UNKNOWN_LICENSE", null, null, null);
      assertFalse(unknown.isApache2());
      assertTrue(unknown.isUnknown());

      LicensedDependency mit = new LicensedDependency(
          dep, "MIT", "MIT License", null, "POM");
      assertFalse(mit.isApache2());
      assertFalse(mit.isUnknown());
    }
  }
}

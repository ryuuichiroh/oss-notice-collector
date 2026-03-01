package com.github.noticecollector.license;

import static org.junit.jupiter.api.Assertions.*;

import com.github.noticecollector.config.LicenseMappingLoader;
import com.github.noticecollector.config.NoticeCollectorConfig;
import com.github.noticecollector.dependency.Dependency;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 既存ライセンス特定プロセスの保存テスト（Preservation Property Tests）。
 *
 * <p>修正前後で、バグ条件に該当しない入力に対して既存の動作が維持されることを検証する。
 * これらのテストは未修正コードで PASS する必要がある（ベースライン動作の確認）。
 */
class LicenseIdentifierPreservationTest {

  private LicenseMapping defaultMapping;

  @BeforeEach
  void setUp() {
    defaultMapping = new LicenseMappingLoader().loadDefaults();
  }

  @Nested
  @DisplayName("Preservation 1: spdxId オーバーライドの優先")
  class SpdxIdOverridePreservation {

    @Test
    @DisplayName("spdxId が指定されている場合、その値を使用しアーカイブダウンロードを行わない")
    void spdxIdOverrideTakesPrecedence() {
      NoticeCollectorConfig.OverrideConfig override = new NoticeCollectorConfig.OverrideConfig();
      override.setGroupId("com.mysql");
      override.setArtifactId("mysql-connector-j");
      override.setSpdxId("Apache-2.0");
      override.setNoticeUrl("https://example.com/archive.tar.gz");

      NoticeCollectorConfig config = new NoticeCollectorConfig();
      config.setOverrides(List.of(override));

      // pom、JAR、GitHub はすべて失敗するが、spdxId が指定されているので問題ない
      LicenseIdentifier identifier = new LicenseIdentifier(
          defaultMapping,
          d -> { throw new IOException("POM not found"); },
          d -> null,
          d -> { throw new IOException("GitHub not found"); },
          config);

      Dependency dep = new Dependency("com.mysql", "mysql-connector-j", "9.4.0", "compile", "jar");
      LicensedDependency result = identifier.identifyLicense(dep);

      assertEquals("Apache-2.0", result.spdxId());
      assertEquals("USER_OVERRIDE", result.licenseSource());
    }

    @Test
    @DisplayName("spdxId オーバーライドは pom.xml より優先される")
    void spdxIdOverridePrecedesOverPom() {
      NoticeCollectorConfig.OverrideConfig override = new NoticeCollectorConfig.OverrideConfig();
      override.setGroupId("log4j");
      override.setArtifactId("log4j");
      override.setSpdxId("Apache-2.0");

      NoticeCollectorConfig config = new NoticeCollectorConfig();
      config.setOverrides(List.of(override));

      // pom.xml は MIT を返すが、spdxId オーバーライドが優先される
      String pomXml = """
          <project>
            <licenses>
              <license><name>MIT License</name></license>
            </licenses>
          </project>
          """;

      LicenseIdentifier identifier = new LicenseIdentifier(
          defaultMapping, d -> pomXml, d -> null, null, config);

      Dependency dep = new Dependency("log4j", "log4j", "1.2.17", "compile", "jar");
      LicensedDependency result = identifier.identifyLicense(dep);

      assertEquals("Apache-2.0", result.spdxId());
      assertEquals("USER_OVERRIDE", result.licenseSource());
    }
  }

  @Nested
  @DisplayName("Preservation 2: pom.xml からのライセンス特定")
  class PomLicensePreservation {

    @Test
    @DisplayName("pom.xml からライセンスを特定できる場合、その判定結果を使用する")
    void pomLicenseTakesPrecedence() {
      String pomXml = """
          <project>
            <licenses>
              <license>
                <name>The Apache Software License, Version 2.0</name>
                <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
              </license>
            </licenses>
          </project>
          """;

      // noticeUrl が指定されていても、pom.xml で特定できればそちらを使用
      NoticeCollectorConfig.OverrideConfig override = new NoticeCollectorConfig.OverrideConfig();
      override.setGroupId("org.example");
      override.setArtifactId("pom-lib");
      override.setNoticeUrl("https://example.com/archive.tar.gz");

      NoticeCollectorConfig config = new NoticeCollectorConfig();
      config.setOverrides(List.of(override));

      LicenseIdentifier identifier = new LicenseIdentifier(
          defaultMapping, d -> pomXml, d -> null, null, config);

      Dependency dep = new Dependency("org.example", "pom-lib", "1.0.0", "compile", "jar");
      LicensedDependency result = identifier.identifyLicense(dep);

      assertEquals("Apache-2.0", result.spdxId());
      assertEquals("POM", result.licenseSource());
    }

    @Test
    @DisplayName("pom.xml で MIT と判定された場合、MIT を返す")
    void pomMitLicense() {
      String pomXml = """
          <project>
            <licenses>
              <license><name>MIT License</name></license>
            </licenses>
          </project>
          """;

      LicenseIdentifier identifier = new LicenseIdentifier(
          defaultMapping, d -> pomXml, d -> null, null, null);

      Dependency dep = new Dependency("org.example", "mit-lib", "1.0.0", "compile", "jar");
      LicensedDependency result = identifier.identifyLicense(dep);

      assertEquals("MIT", result.spdxId());
      assertEquals("POM", result.licenseSource());
    }
  }

  @Nested
  @DisplayName("Preservation 3: JAR からのライセンス特定")
  class JarLicensePreservation {

    @Test
    @DisplayName("JAR が見つからない場合、JAR ステップをスキップして次に進む")
    void jarNotFoundFallsThrough() {
      // pom.xml も失敗、JAR も null → GitHub API へフォールバック
      LicenseIdentifier identifier = new LicenseIdentifier(
          defaultMapping,
          d -> null,
          d -> null,
          d -> new LicenseIdentifier.GitHubLicenseInfo(
              "Apache-2.0", "Apache License 2.0", "https://github.com/example/lib"),
          null);

      Dependency dep = new Dependency("org.example", "jar-lib", "1.0.0", "compile", "jar");
      LicensedDependency result = identifier.identifyLicense(dep);

      assertEquals("Apache-2.0", result.spdxId());
      assertEquals("GITHUB_API", result.licenseSource());
    }
  }

  @Nested
  @DisplayName("Preservation 4: GitHub API からのライセンス特定")
  class GitHubLicensePreservation {

    @Test
    @DisplayName("GitHub API からライセンスを取得できる場合、その判定結果を使用する")
    void githubLicenseTakesPrecedence() {
      // noticeUrl が指定されていても、GitHub API で特定できればそちらを使用
      NoticeCollectorConfig.OverrideConfig override = new NoticeCollectorConfig.OverrideConfig();
      override.setGroupId("org.example");
      override.setArtifactId("gh-lib");
      override.setNoticeUrl("https://example.com/archive.tar.gz");

      NoticeCollectorConfig config = new NoticeCollectorConfig();
      config.setOverrides(List.of(override));

      LicenseIdentifier identifier = new LicenseIdentifier(
          defaultMapping,
          d -> null,
          d -> null,
          d -> new LicenseIdentifier.GitHubLicenseInfo(
              "Apache-2.0", "Apache License 2.0", "https://github.com/example/gh-lib"),
          config);

      Dependency dep = new Dependency("org.example", "gh-lib", "1.0.0", "compile", "jar");
      LicensedDependency result = identifier.identifyLicense(dep);

      assertEquals("Apache-2.0", result.spdxId());
      assertEquals("GITHUB_API", result.licenseSource());
    }

    @Test
    @DisplayName("GitHub API が失敗した場合、UNKNOWN_LICENSE を返す（noticeUrl なし）")
    void githubFailureWithoutNoticeUrl() {
      LicenseIdentifier identifier = new LicenseIdentifier(
          defaultMapping,
          d -> null,
          d -> null,
          d -> { throw new IOException("GitHub API error"); },
          null);

      Dependency dep = new Dependency("org.example", "no-gh", "1.0.0", "compile", "jar");
      LicensedDependency result = identifier.identifyLicense(dep);

      assertEquals("UNKNOWN_LICENSE", result.spdxId());
    }
  }

  @Nested
  @DisplayName("Preservation 5: noticeUrl 未指定時の動作")
  class NoNoticeUrlPreservation {

    @Test
    @DisplayName("noticeUrl が指定されていない場合、アーカイブダウンロードを試行せず UNKNOWN_LICENSE")
    void noNoticeUrlReturnsUnknown() {
      // overrides はあるが noticeUrl は未設定、spdxId も未設定
      NoticeCollectorConfig.OverrideConfig override = new NoticeCollectorConfig.OverrideConfig();
      override.setGroupId("org.example");
      override.setArtifactId("no-url-lib");
      // noticeUrl は未設定

      NoticeCollectorConfig config = new NoticeCollectorConfig();
      config.setOverrides(List.of(override));

      LicenseIdentifier identifier = new LicenseIdentifier(
          defaultMapping,
          d -> { throw new IOException("POM not found"); },
          d -> null,
          d -> { throw new IOException("GitHub not found"); },
          config);

      Dependency dep = new Dependency("org.example", "no-url-lib", "1.0.0", "compile", "jar");
      LicensedDependency result = identifier.identifyLicense(dep);

      assertEquals("UNKNOWN_LICENSE", result.spdxId());
    }

    @Test
    @DisplayName("overrides が空の場合、UNKNOWN_LICENSE を返す")
    void emptyOverridesReturnsUnknown() {
      LicenseIdentifier identifier = new LicenseIdentifier(
          defaultMapping,
          d -> { throw new IOException("POM not found"); },
          d -> null,
          d -> { throw new IOException("GitHub not found"); },
          null);

      Dependency dep = new Dependency("org.example", "no-config", "1.0.0", "compile", "jar");
      LicensedDependency result = identifier.identifyLicense(dep);

      assertEquals("UNKNOWN_LICENSE", result.spdxId());
    }
  }

  @Nested
  @DisplayName("Preservation 6: inferLicenseFromContent の動作維持")
  class InferLicensePreservation {

    private final LicenseIdentifier identifier = new LicenseIdentifier(
        defaultMapping, d -> null, d -> null, null, null);

    @Test
    @DisplayName("Apache License 2.0 の内容を正しく推定する")
    void inferApache2() {
      assertEquals("Apache-2.0", identifier.inferLicenseFromContent(
          "Apache License\nVersion 2.0, January 2004\nhttp://www.apache.org/licenses/"));
    }

    @Test
    @DisplayName("MIT License の内容を正しく推定する")
    void inferMit() {
      assertEquals("MIT", identifier.inferLicenseFromContent(
          "MIT License\n\nPermission is hereby granted, free of charge..."));
    }

    @Test
    @DisplayName("判定できない内容は UNKNOWN_LICENSE")
    void inferUnknown() {
      assertEquals("UNKNOWN_LICENSE",
          identifier.inferLicenseFromContent("Some custom license text"));
    }

    @Test
    @DisplayName("null や空文字は UNKNOWN_LICENSE")
    void inferNullOrBlank() {
      assertEquals("UNKNOWN_LICENSE", identifier.inferLicenseFromContent(null));
      assertEquals("UNKNOWN_LICENSE", identifier.inferLicenseFromContent(""));
      assertEquals("UNKNOWN_LICENSE", identifier.inferLicenseFromContent("   "));
    }
  }
}

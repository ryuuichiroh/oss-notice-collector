package com.github.noticecollector.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

  private final ConfigLoader loader = new ConfigLoader();

  @TempDir Path tempDir;

  @Test
  void loadDefaults_returnsAllFieldsNonNull() {
    NoticeCollectorConfig config = loader.loadDefaults();

    assertNotNull(config.getProject());
    assertNotNull(config.getProject().getMaven());
    assertNotNull(config.getProject().getGradle());
    assertNotNull(config.getOutput());
    assertNotNull(config.getRepositories());
    assertNotNull(config.getGithub());
    assertNotNull(config.getApacheArchive());
    assertNotNull(config.getOverrides());
    assertNotNull(config.getSourceRepositories());
    assertNotNull(config.getTargetLicenses());
    assertNotNull(config.getExternalDefinitions());

    assertEquals(".", config.getProject().getPath());
    assertEquals("auto", config.getProject().getBuildTool());
    assertEquals("./output", config.getOutput().getDirectory());
    assertEquals("THIRD-PARTY-NOTICES.txt", config.getOutput().getAggregatedFile());
    assertEquals("collection-report.json", config.getOutput().getReportFile());
    assertEquals("GITHUB_TOKEN", config.getGithub().getTokenEnv());
    assertEquals("https://api.github.com", config.getGithub().getApiBaseUrl());
    assertTrue(config.getApacheArchive().isEnabled());
    assertEquals(50, config.getApacheArchive().getMaxDownloadSizeMb());
    assertEquals(1, config.getTargetLicenses().size());
    assertEquals("Apache-2.0", config.getTargetLicenses().get(0));
  }

  @Test
  void load_partialYaml_appliesDefaults() throws Exception {
    Path yamlFile = tempDir.resolve("config.yaml");
    Files.writeString(
        yamlFile,
        """
        project:
          path: "/my/project"
          buildTool: "maven"
        """);

    NoticeCollectorConfig config = loader.load(yamlFile);

    assertEquals("/my/project", config.getProject().getPath());
    assertEquals("maven", config.getProject().getBuildTool());
    // Defaults applied for unspecified fields
    assertNotNull(config.getOutput());
    assertEquals("./output", config.getOutput().getDirectory());
    assertNotNull(config.getGithub());
    assertEquals("GITHUB_TOKEN", config.getGithub().getTokenEnv());
    assertNotNull(config.getTargetLicenses());
  }

  @Test
  void load_fullYaml_parsesAllFields() throws Exception {
    Path yamlFile = tempDir.resolve("config.yaml");
    Files.writeString(
        yamlFile,
        """
        project:
          path: "/my/project"
          buildTool: "gradle"
          localRepository: "/custom/.m2/repository"
          gradleCache: "/custom/.gradle/caches"
          maven:
            scopes:
              - "compile"
          gradle:
            configurations:
              - "compileClasspath"
        output:
          directory: "./custom-output"
          aggregatedFile: "NOTICES.txt"
          reportFile: "report.json"
        github:
          tokenEnv: "MY_GH_TOKEN"
          apiBaseUrl: "https://github.example.com/api/v3"
        apacheArchive:
          enabled: false
          maxDownloadSizeMb: 100
        targetLicenses:
          - "Apache-2.0"
          - "MIT"
        externalDefinitions:
          licenseMappings: "custom-mappings.yaml"
          noticePatterns: "custom-patterns.yaml"
        """);

    NoticeCollectorConfig config = loader.load(yamlFile);

    assertEquals("gradle", config.getProject().getBuildTool());
    assertEquals("/custom/.m2/repository", config.getProject().getLocalRepository());
    assertEquals("/custom/.gradle/caches", config.getProject().getGradleCache());
    assertEquals(1, config.getProject().getMaven().getScopes().size());
    assertEquals("compile", config.getProject().getMaven().getScopes().get(0));
    assertEquals("compileClasspath", config.getProject().getGradle().getConfigurations().get(0));
    assertEquals("./custom-output", config.getOutput().getDirectory());
    assertEquals("NOTICES.txt", config.getOutput().getAggregatedFile());
    assertEquals("report.json", config.getOutput().getReportFile());
    assertEquals("MY_GH_TOKEN", config.getGithub().getTokenEnv());
    assertFalse(config.getApacheArchive().isEnabled());
    assertEquals(100, config.getApacheArchive().getMaxDownloadSizeMb());
    assertEquals(2, config.getTargetLicenses().size());
    assertEquals("custom-mappings.yaml", config.getExternalDefinitions().getLicenseMappings());
    assertEquals("custom-patterns.yaml", config.getExternalDefinitions().getNoticePatterns());
  }

  @Test
  void load_emptyYaml_returnsDefaults() throws Exception {
    Path yamlFile = tempDir.resolve("empty.yaml");
    Files.writeString(yamlFile, "");

    NoticeCollectorConfig config = loader.load(yamlFile);

    assertNotNull(config.getProject());
    assertEquals(".", config.getProject().getPath());
    assertNotNull(config.getOutput());
    assertNotNull(config.getGithub());
  }

  @Test
  void load_nonExistentFile_throwsException() {
    Path missing = tempDir.resolve("nonexistent.yaml");
    ConfigLoader.ConfigLoadException ex =
        assertThrows(ConfigLoader.ConfigLoadException.class, () -> loader.load(missing));
    assertTrue(ex.getMessage().contains("設定ファイルが見つかりません"));
  }

  @Test
  void load_pathTraversal_forwardSlash_throwsException() {
    Path malicious = tempDir.resolve("../../../etc/passwd");
    ConfigLoader.ConfigLoadException ex =
        assertThrows(ConfigLoader.ConfigLoadException.class, () -> loader.load(malicious));
    assertTrue(ex.getMessage().contains("パストラバーサル"));
  }

  @Test
  void load_pathTraversal_backSlash_throwsException() {
    Path malicious = tempDir.resolve("..\\..\\etc\\passwd");
    ConfigLoader.ConfigLoadException ex =
        assertThrows(ConfigLoader.ConfigLoadException.class, () -> loader.load(malicious));
    assertTrue(ex.getMessage().contains("パストラバーサル"));
  }

  @Test
  void validatePath_rejectsTraversalPatterns() {
    assertThrows(
        ConfigLoader.ConfigLoadException.class, () -> ConfigLoader.validatePath("../secret"));
    assertThrows(
        ConfigLoader.ConfigLoadException.class, () -> ConfigLoader.validatePath("foo/../bar"));
    assertThrows(
        ConfigLoader.ConfigLoadException.class, () -> ConfigLoader.validatePath("foo\\..\\bar"));
    assertThrows(
        ConfigLoader.ConfigLoadException.class, () -> ConfigLoader.validatePath("foo/.."));
    assertThrows(
        ConfigLoader.ConfigLoadException.class, () -> ConfigLoader.validatePath("foo\\.."));
    assertThrows(ConfigLoader.ConfigLoadException.class, () -> ConfigLoader.validatePath(".."));
  }

  @Test
  void validatePath_allowsSafePaths() throws Exception {
    // These should not throw
    ConfigLoader.validatePath("./output");
    ConfigLoader.validatePath("/absolute/path");
    ConfigLoader.validatePath("relative/path");
    ConfigLoader.validatePath("file.yaml");
    ConfigLoader.validatePath(null);
  }

  @Test
  void load_yamlWithPathTraversalInProjectPath_throwsException() throws Exception {
    Path yamlFile = tempDir.resolve("bad-config.yaml");
    Files.writeString(
        yamlFile,
        """
        project:
          path: "../../etc"
        """);

    assertThrows(ConfigLoader.ConfigLoadException.class, () -> loader.load(yamlFile));
  }

  @Test
  void load_yamlWithPathTraversalInOutputDir_throwsException() throws Exception {
    Path yamlFile = tempDir.resolve("bad-output.yaml");
    Files.writeString(
        yamlFile,
        """
        output:
          directory: "../../../tmp/evil"
        """);

    assertThrows(ConfigLoader.ConfigLoadException.class, () -> loader.load(yamlFile));
  }

  @Test
  void load_yamlWithOverrides_parsesCorrectly() throws Exception {
    Path yamlFile = tempDir.resolve("overrides.yaml");
    Files.writeString(
        yamlFile,
        """
        overrides:
          - groupId: "org.apache.commons"
            artifactId: "commons-lang3"
            version: "3.14.0"
            noticePath: "/path/to/NOTICE"
        """);

    NoticeCollectorConfig config = loader.load(yamlFile);

    assertEquals(1, config.getOverrides().size());
    NoticeCollectorConfig.OverrideConfig override = config.getOverrides().get(0);
    assertEquals("org.apache.commons", override.getGroupId());
    assertEquals("commons-lang3", override.getArtifactId());
    assertEquals("3.14.0", override.getVersion());
    assertEquals("/path/to/NOTICE", override.getNoticePath());
  }

  @Test
  void load_yamlWithRepositories_parsesCorrectly() throws Exception {
    Path yamlFile = tempDir.resolve("repos.yaml");
    Files.writeString(
        yamlFile,
        """
        repositories:
          - name: "nexus"
            url: "https://nexus.example.com/repo/"
            type: "maven"
            auth:
              type: "basic"
              usernameEnv: "NEXUS_USER"
              passwordEnv: "NEXUS_PASS"
        """);

    NoticeCollectorConfig config = loader.load(yamlFile);

    assertEquals(1, config.getRepositories().size());
    NoticeCollectorConfig.RepositoryConfig repo = config.getRepositories().get(0);
    assertEquals("nexus", repo.getName());
    assertEquals("https://nexus.example.com/repo/", repo.getUrl());
    assertNotNull(repo.getAuth());
    assertEquals("basic", repo.getAuth().getType());
    assertEquals("NEXUS_USER", repo.getAuth().getUsernameEnv());
    assertEquals("NEXUS_PASS", repo.getAuth().getPasswordEnv());
  }
}

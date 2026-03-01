package com.github.noticecollector.license;

import static org.junit.jupiter.api.Assertions.*;

import com.github.noticecollector.config.NoticeCollectorConfig;
import com.github.noticecollector.dependency.Dependency;
import com.github.noticecollector.http.HttpClientWrapper;
import com.github.noticecollector.http.HttpRequestException;
import com.github.noticecollector.notice.util.ArchiveNoticeExtractor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * LicenseIdentifier のアーカイブからのライセンス特定機能のテスト。
 *
 * <p>pom.xml、ローカル JAR、GitHub API のいずれからもライセンスを特定できず、
 * かつ overrides で noticeUrl が指定されている場合に、アーカイブ内の LICENSE ファイルから
 * ライセンスを判定する機能をテストする。
 */
class LicenseIdentifierArchiveTest {

  /** ローカルアーカイブファイルを返す HttpClientWrapper スタブ。 */
  private static class StubHttpClient extends HttpClientWrapper {
    private final Path archiveFile;

    StubHttpClient(Path archiveFile) {
      super(new NoticeCollectorConfig());
      this.archiveFile = archiveFile;
    }

    @Override
    public void downloadToFile(String url, Path destination) throws HttpRequestException {
      try {
        if (archiveFile != null) {
          java.nio.file.Files.copy(archiveFile, destination,
              java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
      } catch (IOException e) {
        throw new HttpRequestException("Failed to copy archive", e);
      }
    }
  }

  /** POM を返さないスタブ。 */
  private static class NoPomFetcher implements LicenseIdentifier.PomFetcher {
    @Override
    public String fetchPom(Dependency dependency) throws IOException {
      throw new IOException("POM not found");
    }
  }

  /** JAR を返さないスタブ。 */
  private static class NoJarLocator implements LicenseIdentifier.JarLocator {
    @Override
    public Path locateJar(Dependency dependency) {
      return null;
    }
  }

  /** GitHub ライセンス情報を返さないスタブ。 */
  private static class NoGitHubFetcher implements LicenseIdentifier.GitHubLicenseFetcher {
    @Override
    public LicenseIdentifier.GitHubLicenseInfo fetchLicense(Dependency dependency)
        throws IOException {
      throw new IOException("GitHub license not found");
    }
  }

  private Path createTarGzWithLicense(Path tempDir, String fileName, String content)
      throws IOException {
    Path archiveFile = tempDir.resolve("test-" + fileName.hashCode() + ".tar.gz");
    try (var fos = java.nio.file.Files.newOutputStream(archiveFile);
         var gzos = new GzipCompressorOutputStream(fos);
         var taos = new TarArchiveOutputStream(gzos)) {
      byte[] data = content.getBytes(StandardCharsets.UTF_8);
      TarArchiveEntry entry = new TarArchiveEntry("test-project/" + fileName);
      entry.setSize(data.length);
      taos.putArchiveEntry(entry);
      taos.write(data);
      taos.closeArchiveEntry();
      taos.finish();
    }
    return archiveFile;
  }

  private Path createTarGzWithApacheLicense(Path tempDir) throws IOException {
    return createTarGzWithLicense(tempDir, "LICENSE",
        "Apache License\nVersion 2.0, January 2004\n"
            + "http://www.apache.org/licenses/\n");
  }

  private Path createTarGzWithoutLicense(Path tempDir) throws IOException {
    Path archiveFile = tempDir.resolve("test-no-license.tar.gz");
    try (var fos = java.nio.file.Files.newOutputStream(archiveFile);
         var gzos = new GzipCompressorOutputStream(fos);
         var taos = new TarArchiveOutputStream(gzos)) {
      byte[] data = "This is a test archive".getBytes(StandardCharsets.UTF_8);
      TarArchiveEntry entry = new TarArchiveEntry("test-project/README.txt");
      entry.setSize(data.length);
      taos.putArchiveEntry(entry);
      taos.write(data);
      taos.closeArchiveEntry();
      taos.finish();
    }
    return archiveFile;
  }

  private Path createTarGzWithMitLicense(Path tempDir) throws IOException {
    return createTarGzWithLicense(tempDir, "LICENSE",
        "MIT License\n\nCopyright (c) 2024\n"
            + "Permission is hereby granted, free of charge...\n");
  }

  private LicenseMapping buildDefaultMapping() {
    return LicenseMapping.builder()
        .addNameMapping(List.of(
            "The Apache Software License, Version 2.0",
            "Apache License, Version 2.0",
            "Apache-2.0", "ASL 2.0", "Apache 2",
            "Apache 2.0", "Apache License 2.0"), "Apache-2.0")
        .addNameMapping(List.of("The MIT License", "MIT License", "MIT"), "MIT")
        .addUrlMapping("apache.org/licenses/license-2.0", "Apache-2.0")
        .build();
  }

  /**
   * テストケース 1: com.mysql:mysql-connector-j で pom.xml、JAR、GitHub API が失敗し、
   * noticeUrl が指定されている場合、アーカイブから Apache-2.0 を判定できる。
   */
  @Test
  void identifiesApacheLicenseFromArchiveForMysqlConnector(@TempDir Path tempDir)
      throws IOException {
    Path archiveFile = createTarGzWithApacheLicense(tempDir);

    NoticeCollectorConfig.OverrideConfig override = new NoticeCollectorConfig.OverrideConfig();
    override.setGroupId("com.mysql");
    override.setArtifactId("mysql-connector-j");
    override.setNoticeUrl(
        "https://github.com/mysql/mysql-connector-j/archive/refs/tags/9.4.0.tar.gz");

    NoticeCollectorConfig config = new NoticeCollectorConfig();
    config.setOverrides(List.of(override));

    LicenseIdentifier identifier = new LicenseIdentifier(
        buildDefaultMapping(), new NoPomFetcher(), new NoJarLocator(), new NoGitHubFetcher(),
        config, new StubHttpClient(archiveFile), new ArchiveNoticeExtractor());

    Dependency dep = new Dependency("com.mysql", "mysql-connector-j", "9.4.0", "compile", "jar");
    LicensedDependency result = identifier.identifyLicense(dep);

    assertEquals("Apache-2.0", result.spdxId(),
        "アーカイブ内の LICENSE ファイルから Apache-2.0 を判定できるべき");
    assertEquals("ARCHIVE", result.licenseSource(),
        "ライセンスソースは ARCHIVE であるべき");
  }

  /**
   * テストケース 2: log4j:log4j で pom.xml、JAR、GitHub API が失敗し、
   * noticeUrl が指定されている場合、アーカイブから Apache-2.0 を判定できる。
   */
  @Test
  void identifiesApacheLicenseFromArchiveForLog4j(@TempDir Path tempDir) throws IOException {
    Path archiveFile = createTarGzWithApacheLicense(tempDir);

    NoticeCollectorConfig.OverrideConfig override = new NoticeCollectorConfig.OverrideConfig();
    override.setGroupId("log4j");
    override.setArtifactId("log4j");
    override.setNoticeUrl(
        "https://github.com/apache/logging-log4j1/archive/refs/tags/v1_2_17.tar.gz");

    NoticeCollectorConfig config = new NoticeCollectorConfig();
    config.setOverrides(List.of(override));

    LicenseIdentifier identifier = new LicenseIdentifier(
        buildDefaultMapping(), new NoPomFetcher(), new NoJarLocator(), new NoGitHubFetcher(),
        config, new StubHttpClient(archiveFile), new ArchiveNoticeExtractor());

    Dependency dep = new Dependency("log4j", "log4j", "1.2.17", "compile", "jar");
    LicensedDependency result = identifier.identifyLicense(dep);

    assertEquals("Apache-2.0", result.spdxId(),
        "アーカイブ内の LICENSE ファイルから Apache-2.0 を判定できるべき");
  }

  /**
   * テストケース 3: アーカイブに LICENSE ファイルが含まれていない場合、
   * UNKNOWN_LICENSE を返す。
   */
  @Test
  void returnsUnknownLicenseWhenArchiveHasNoLicense(@TempDir Path tempDir) throws IOException {
    Path archiveFile = createTarGzWithoutLicense(tempDir);

    NoticeCollectorConfig.OverrideConfig override = new NoticeCollectorConfig.OverrideConfig();
    override.setGroupId("org.example");
    override.setArtifactId("no-license-lib");
    override.setNoticeUrl("https://example.com/archive.tar.gz");

    NoticeCollectorConfig config = new NoticeCollectorConfig();
    config.setOverrides(List.of(override));

    LicenseIdentifier identifier = new LicenseIdentifier(
        buildDefaultMapping(), new NoPomFetcher(), new NoJarLocator(), new NoGitHubFetcher(),
        config, new StubHttpClient(archiveFile), new ArchiveNoticeExtractor());

    Dependency dep = new Dependency("org.example", "no-license-lib", "1.0.0", "compile", "jar");
    LicensedDependency result = identifier.identifyLicense(dep);

    assertEquals("UNKNOWN_LICENSE", result.spdxId(),
        "アーカイブに LICENSE ファイルがない場合は UNKNOWN_LICENSE を返すべき");
  }

  /**
   * テストケース 4: アーカイブに Apache-2.0 以外のライセンスが含まれている場合、
   * そのライセンスを返す（MIT の例）。
   */
  @Test
  void identifiesMitLicenseFromArchive(@TempDir Path tempDir) throws IOException {
    Path archiveFile = createTarGzWithMitLicense(tempDir);

    NoticeCollectorConfig.OverrideConfig override = new NoticeCollectorConfig.OverrideConfig();
    override.setGroupId("org.example");
    override.setArtifactId("mit-lib");
    override.setNoticeUrl("https://example.com/archive.tar.gz");

    NoticeCollectorConfig config = new NoticeCollectorConfig();
    config.setOverrides(List.of(override));

    LicenseIdentifier identifier = new LicenseIdentifier(
        buildDefaultMapping(), new NoPomFetcher(), new NoJarLocator(), new NoGitHubFetcher(),
        config, new StubHttpClient(archiveFile), new ArchiveNoticeExtractor());

    Dependency dep = new Dependency("org.example", "mit-lib", "1.0.0", "compile", "jar");
    LicensedDependency result = identifier.identifyLicense(dep);

    assertEquals("MIT", result.spdxId(),
        "アーカイブ内の LICENSE ファイルから MIT を判定できるべき");
  }
}

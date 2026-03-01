package com.github.noticecollector.notice.source;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.noticecollector.config.NoticeCollectorConfig;
import com.github.noticecollector.dependency.Dependency;
import com.github.noticecollector.http.HttpClientWrapper;
import com.github.noticecollector.http.HttpRequestException;
import com.github.noticecollector.license.LicensedDependency;
import com.github.noticecollector.notice.NoticeSearchResult;
import com.github.noticecollector.notice.NoticeSearchResult.SearchOutcome;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** UserOverrideSource のユニットテスト。 */
@WireMockTest
class UserOverrideSourceTest {

  private static final List<String> PATTERNS = List.of("META-INF/NOTICE", "NOTICE");

  /** テスト用: NOTICE を含む tar.gz アーカイブを作成する。 */
  private void createTarGzWithNotice(Path archivePath, String noticeContent) throws IOException {
    try (var fos = Files.newOutputStream(archivePath);
         var gzos = new org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream(fos);
         var taos = new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(gzos)) {
      
      // NOTICE ファイルを追加
      byte[] noticeBytes = noticeContent.getBytes(StandardCharsets.UTF_8);
      var entry = new org.apache.commons.compress.archivers.tar.TarArchiveEntry("test-project/NOTICE");
      entry.setSize(noticeBytes.length);
      taos.putArchiveEntry(entry);
      taos.write(noticeBytes);
      taos.closeArchiveEntry();
      taos.finish();
    }
  }

  /** テスト用: NOTICE を含まない空の tar.gz アーカイブを作成する。 */
  private void createEmptyTarGz(Path archivePath) throws IOException {
    try (var fos = Files.newOutputStream(archivePath);
         var gzos = new org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream(fos);
         var taos = new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(gzos)) {
      
      // README.txt のみを追加（NOTICE は含まない）
      byte[] readmeBytes = "This is a test archive".getBytes(StandardCharsets.UTF_8);
      var entry = new org.apache.commons.compress.archivers.tar.TarArchiveEntry("test-project/README.txt");
      entry.setSize(readmeBytes.length);
      taos.putArchiveEntry(entry);
      taos.write(readmeBytes);
      taos.closeArchiveEntry();
      taos.finish();
    }
  }

  /** テスト用: HTTP を許可する簡易 HttpClientWrapper スタブ。 */
  private static class StubHttpClient extends HttpClientWrapper {
    private final String responseBody;
    private final Path archiveFile;
    private final boolean shouldFail;

    StubHttpClient(String responseBody, boolean shouldFail) {
      super(new NoticeCollectorConfig());
      this.responseBody = responseBody;
      this.archiveFile = null;
      this.shouldFail = shouldFail;
    }

    StubHttpClient(Path archiveFile, boolean shouldFail) {
      super(new NoticeCollectorConfig());
      this.responseBody = null;
      this.archiveFile = archiveFile;
      this.shouldFail = shouldFail;
    }

    @Override
    public String getString(String url) throws HttpRequestException {
      if (shouldFail) {
        throw new HttpRequestException("HTTP error for test", 404);
      }
      return responseBody;
    }

    @Override
    public void downloadToFile(String url, Path destination) throws HttpRequestException {
      if (shouldFail) {
        throw new HttpRequestException("HTTP error for test", 404);
      }
      try {
        if (archiveFile != null) {
          Files.copy(archiveFile, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } else if (responseBody != null) {
          Files.writeString(destination, responseBody, StandardCharsets.UTF_8);
        }
      } catch (IOException e) {
        throw new HttpRequestException("Failed to write file", e);
      }
    }
  }

  private LicensedDependency createDep(String groupId, String artifactId, String version) {
    return new LicensedDependency(
        new Dependency(groupId, artifactId, version, "compile", "jar"),
        "Apache-2.0", "Apache License 2.0", null, "POM");
  }

  private NoticeCollectorConfig createConfig(List<NoticeCollectorConfig.OverrideConfig> overrides) {
    NoticeCollectorConfig config = new NoticeCollectorConfig();
    config.setOverrides(overrides);
    return config;
  }

  private NoticeCollectorConfig.OverrideConfig createOverride(
      String groupId, String artifactId, String version,
      String noticePath, String noticeUrl) {
    NoticeCollectorConfig.OverrideConfig override = new NoticeCollectorConfig.OverrideConfig();
    override.setGroupId(groupId);
    override.setArtifactId(artifactId);
    override.setVersion(version);
    override.setNoticePath(noticePath);
    override.setNoticeUrl(noticeUrl);
    return override;
  }

  @Test
  void returnsNotFoundWhenNoOverrideConfigured() {
    NoticeCollectorConfig config = createConfig(List.of());
    UserOverrideSource source = new UserOverrideSource(config, new StubHttpClient((String) null, false));
    NoticeSearchResult result = source.search(
        createDep("org.example", "lib", "1.0"), PATTERNS);
    assertEquals(SearchOutcome.NOT_FOUND, result.outcome());
  }

  @Test
  void returnsNotFoundWhenOverrideDoesNotMatch() {
    NoticeCollectorConfig.OverrideConfig override =
        createOverride("org.other", "other-lib", null, "/some/path", null);
    NoticeCollectorConfig config = createConfig(List.of(override));
    UserOverrideSource source = new UserOverrideSource(config, new StubHttpClient((String) null, false));
    NoticeSearchResult result = source.search(
        createDep("org.example", "lib", "1.0"), PATTERNS);
    assertEquals(SearchOutcome.NOT_FOUND, result.outcome());
  }

  @Test
  void readsNoticeFromLocalPath(@TempDir Path tempDir) throws IOException {
    Path noticeFile = tempDir.resolve("NOTICE");
    Files.writeString(noticeFile, "Test NOTICE content", StandardCharsets.UTF_8);

    NoticeCollectorConfig.OverrideConfig override =
        createOverride("org.example", "lib", "1.0", noticeFile.toString(), null);
    NoticeCollectorConfig config = createConfig(List.of(override));
    UserOverrideSource source = new UserOverrideSource(config, new StubHttpClient((String) null, false));
    NoticeSearchResult result = source.search(
        createDep("org.example", "lib", "1.0"), PATTERNS);

    assertEquals(SearchOutcome.FOUND, result.outcome());
    assertEquals("Test NOTICE content", result.noticeContent());
    assertEquals(noticeFile.toString(), result.sourceUrl());
  }

  @Test
  void returnsNotFoundWhenLocalPathDoesNotExist() {
    NoticeCollectorConfig.OverrideConfig override =
        createOverride("org.example", "lib", null, "/nonexistent/path/NOTICE", null);
    NoticeCollectorConfig config = createConfig(List.of(override));
    UserOverrideSource source = new UserOverrideSource(config, new StubHttpClient((String) null, false));
    NoticeSearchResult result = source.search(
        createDep("org.example", "lib", "1.0"), PATTERNS);
    assertEquals(SearchOutcome.NOT_FOUND, result.outcome());
  }

  @Test
  void fetchesNoticeFromUrl() {
    String noticeUrl = "https://example.com/notice/NOTICE.txt";
    NoticeCollectorConfig.OverrideConfig override =
        createOverride("org.example", "lib", "2.0", null, noticeUrl);
    NoticeCollectorConfig config = createConfig(List.of(override));
    UserOverrideSource source =
        new UserOverrideSource(config, new StubHttpClient("NOTICE from URL", false));
    NoticeSearchResult result = source.search(
        createDep("org.example", "lib", "2.0"), PATTERNS);

    assertEquals(SearchOutcome.FOUND, result.outcome());
    assertEquals("NOTICE from URL", result.noticeContent());
    assertEquals(noticeUrl, result.sourceUrl());
  }

  @Test
  void returnsErrorWhenUrlFetchFails() {
    String noticeUrl = "https://example.com/notice/fail";
    NoticeCollectorConfig.OverrideConfig override =
        createOverride("org.example", "lib", "1.0", null, noticeUrl);
    NoticeCollectorConfig config = createConfig(List.of(override));
    UserOverrideSource source =
        new UserOverrideSource(config, new StubHttpClient((String) null, true));
    NoticeSearchResult result = source.search(
        createDep("org.example", "lib", "1.0"), PATTERNS);

    assertEquals(SearchOutcome.ERROR, result.outcome());
    assertNotNull(result.message());
  }

  @Test
  void matchesOverrideWithoutVersion(@TempDir Path tempDir) throws IOException {
    Path noticeFile = tempDir.resolve("NOTICE");
    Files.writeString(noticeFile, "version-agnostic NOTICE", StandardCharsets.UTF_8);

    // version が null のオーバーライドは全バージョンにマッチする
    NoticeCollectorConfig.OverrideConfig override =
        createOverride("org.example", "lib", null, noticeFile.toString(), null);
    NoticeCollectorConfig config = createConfig(List.of(override));
    UserOverrideSource source = new UserOverrideSource(config, new StubHttpClient((String) null, false));
    NoticeSearchResult result = source.search(
        createDep("org.example", "lib", "3.0"), PATTERNS);

    assertEquals(SearchOutcome.FOUND, result.outcome());
    assertEquals("version-agnostic NOTICE", result.noticeContent());
  }

  @Test
  void noticePathTakesPriorityOverNoticeUrl(@TempDir Path tempDir) throws IOException {
    Path noticeFile = tempDir.resolve("NOTICE");
    Files.writeString(noticeFile, "Local NOTICE", StandardCharsets.UTF_8);

    // noticePath と noticeUrl の両方が設定されている場合、noticePath を優先
    NoticeCollectorConfig.OverrideConfig override =
        createOverride("org.example", "lib", "1.0",
            noticeFile.toString(), "https://example.com/NOTICE");
    NoticeCollectorConfig config = createConfig(List.of(override));
    UserOverrideSource source =
        new UserOverrideSource(config, new StubHttpClient("URL NOTICE", false));
    NoticeSearchResult result = source.search(
        createDep("org.example", "lib", "1.0"), PATTERNS);

    assertEquals(SearchOutcome.FOUND, result.outcome());
    assertEquals("Local NOTICE", result.noticeContent());
  }

  @Test
  void priorityIsOne() {
    NoticeCollectorConfig config = createConfig(List.of());
    UserOverrideSource source = new UserOverrideSource(config, new StubHttpClient((String) null, false));
    assertEquals(1, source.getPriority());
    assertEquals("USER_OVERRIDE", source.getSourceName());
  }

  @Test
  void resolvesVersionPlaceholder(@TempDir Path tempDir) throws IOException {
    // 実際の tar.gz アーカイブを作成（NOTICE を含む）
    Path archiveFile = tempDir.resolve("test.tar.gz");
    createTarGzWithNotice(archiveFile, "NOTICE content");

    String noticeUrl = "https://github.com/example/repo/archive/refs/tags/${version}.tar.gz";
    NoticeCollectorConfig.OverrideConfig override =
        createOverride("org.example", "lib", null, null, noticeUrl);
    NoticeCollectorConfig config = createConfig(List.of(override));
    
    // StubHttpClient が archiveFile の内容を返すようにする
    UserOverrideSource source =
        new UserOverrideSource(config, new StubHttpClient(archiveFile, false));
    
    NoticeSearchResult result = source.search(
        createDep("org.example", "lib", "1.2.3"), PATTERNS);

    assertEquals(SearchOutcome.FOUND, result.outcome());
    assertEquals("https://github.com/example/repo/archive/refs/tags/1.2.3.tar.gz", 
        result.sourceUrl());
  }

  @Test
  void resolvesUnderscoredVersionPlaceholder(@TempDir Path tempDir) throws IOException {
    // 実際の tar.gz アーカイブを作成（NOTICE を含む）
    Path archiveFile = tempDir.resolve("test.tar.gz");
    createTarGzWithNotice(archiveFile, "NOTICE content");

    String noticeUrl = "https://github.com/apache/logging-log4j1/archive/refs/tags/v${underscored_version}.tar.gz";
    NoticeCollectorConfig.OverrideConfig override =
        createOverride("log4j", "log4j", null, null, noticeUrl);
    NoticeCollectorConfig config = createConfig(List.of(override));
    
    // StubHttpClient が archiveFile の内容を返すようにする
    UserOverrideSource source =
        new UserOverrideSource(config, new StubHttpClient(archiveFile, false));
    
    NoticeSearchResult result = source.search(
        createDep("log4j", "log4j", "1.2.17"), PATTERNS);

    assertEquals(SearchOutcome.FOUND, result.outcome());
    assertEquals("https://github.com/apache/logging-log4j1/archive/refs/tags/v1_2_17.tar.gz", 
        result.sourceUrl());
  }

  @Test
  void resolvesVersionMajorPlaceholder() {
    String noticeUrl = "https://example.com/v${version_major}/NOTICE";
    NoticeCollectorConfig.OverrideConfig override =
        createOverride("org.example", "lib", null, null, noticeUrl);
    NoticeCollectorConfig config = createConfig(List.of(override));
    UserOverrideSource source =
        new UserOverrideSource(config, new StubHttpClient("NOTICE content", false));
    
    NoticeSearchResult result = source.search(
        createDep("org.example", "lib", "3.5.7"), PATTERNS);

    assertEquals(SearchOutcome.FOUND, result.outcome());
    assertEquals("https://example.com/v3/NOTICE", result.sourceUrl());
  }

  @Test
  void resolvesVersionMinorPlaceholder() {
    String noticeUrl = "https://example.com/v${version_minor}/NOTICE";
    NoticeCollectorConfig.OverrideConfig override =
        createOverride("org.example", "lib", null, null, noticeUrl);
    NoticeCollectorConfig config = createConfig(List.of(override));
    UserOverrideSource source =
        new UserOverrideSource(config, new StubHttpClient("NOTICE content", false));
    
    NoticeSearchResult result = source.search(
        createDep("org.example", "lib", "3.5.7"), PATTERNS);

    assertEquals(SearchOutcome.FOUND, result.outcome());
    assertEquals("https://example.com/v3.5/NOTICE", result.sourceUrl());
  }

  @Test
  void resolvesMultiplePlaceholders() {
    String noticeUrl = "https://example.com/${version_major}.x/${version}/NOTICE";
    NoticeCollectorConfig.OverrideConfig override =
        createOverride("org.example", "lib", null, null, noticeUrl);
    NoticeCollectorConfig config = createConfig(List.of(override));
    UserOverrideSource source =
        new UserOverrideSource(config, new StubHttpClient("NOTICE content", false));
    
    NoticeSearchResult result = source.search(
        createDep("org.example", "lib", "2.1.0"), PATTERNS);

    assertEquals(SearchOutcome.FOUND, result.outcome());
    assertEquals("https://example.com/2.x/2.1.0/NOTICE", result.sourceUrl());
  }

  @Test
  void returnsSourceFoundNoNoticeWhenArchiveHasNoNotice(@TempDir Path tempDir) throws IOException {
    // 実際の tar.gz アーカイブを作成（NOTICE を含まない）
    Path archiveFile = tempDir.resolve("test.tar.gz");
    createEmptyTarGz(archiveFile);

    NoticeCollectorConfig.OverrideConfig override =
        createOverride("org.example", "lib", null, archiveFile.toString(), null);
    NoticeCollectorConfig config = createConfig(List.of(override));
    UserOverrideSource source =
        new UserOverrideSource(config, new StubHttpClient((String) null, false));
    
    NoticeSearchResult result = source.search(
        createDep("org.example", "lib", "1.0.0"), PATTERNS);

    // ユーザが明示的に指定したアーカイブなので、SOURCE_FOUND_NO_NOTICE を返すべき
    assertEquals(SearchOutcome.SOURCE_FOUND_NO_NOTICE, result.outcome());
    assertEquals(archiveFile.toString(), result.sourceUrl());
  }
}

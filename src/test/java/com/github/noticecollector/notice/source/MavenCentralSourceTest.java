package com.github.noticecollector.notice.source;

import static org.junit.jupiter.api.Assertions.*;

import com.github.noticecollector.config.NoticeCollectorConfig;
import com.github.noticecollector.dependency.Dependency;
import com.github.noticecollector.http.HttpClientWrapper;
import com.github.noticecollector.http.HttpRequestException;
import com.github.noticecollector.license.LicensedDependency;
import com.github.noticecollector.notice.NoticeSearchResult;
import com.github.noticecollector.notice.NoticeSearchResult.SearchOutcome;
import com.github.noticecollector.notice.util.JarNoticeExtractor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** MavenCentralSource のユニットテスト。 */
class MavenCentralSourceTest {

  private static final List<String> PATTERNS =
      List.of("META-INF/NOTICE", "META-INF/NOTICE.txt", "NOTICE");

  private LicensedDependency createDep(String groupId, String artifactId, String version) {
    return new LicensedDependency(
        new Dependency(groupId, artifactId, version, "compile", "jar"),
        "Apache-2.0", "Apache License 2.0", null, "POM");
  }

  /** テスト用 JAR バイト列を生成する。 */
  private byte[] createJarBytes(String[][] entries) throws IOException {
    Path tempJar = Files.createTempFile("test-", ".jar");
    try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(tempJar.toFile()))) {
      for (String[] entry : entries) {
        JarEntry je = new JarEntry(entry[0]);
        jos.putNextEntry(je);
        if (entry[1] != null) {
          jos.write(entry[1].getBytes(StandardCharsets.UTF_8));
        }
        jos.closeEntry();
      }
    }
    byte[] bytes = Files.readAllBytes(tempJar);
    Files.delete(tempJar);
    return bytes;
  }

  /** テスト用: HTTP レスポンスを制御する簡易 HttpClientWrapper スタブ。 */
  private static class StubHttpClient extends HttpClientWrapper {
    private final byte[] responseBytes;
    private final HttpRequestException exception;

    StubHttpClient(byte[] responseBytes) {
      super(new NoticeCollectorConfig());
      this.responseBytes = responseBytes;
      this.exception = null;
    }

    StubHttpClient(HttpRequestException exception) {
      super(new NoticeCollectorConfig());
      this.responseBytes = null;
      this.exception = exception;
    }

    @Override
    public byte[] get(String url) throws HttpRequestException {
      if (exception != null) {
        throw exception;
      }
      return responseBytes;
    }
  }

  @Test
  void findsNoticeInSourceJar() throws IOException {
    byte[] jarBytes = createJarBytes(new String[][] {
        {"META-INF/NOTICE", "Maven Central NOTICE content"}
    });

    MavenCentralSource source = new MavenCentralSource(new StubHttpClient(jarBytes));
    NoticeSearchResult result = source.search(
        createDep("org.apache.commons", "commons-lang3", "3.14.0"), PATTERNS);

    assertEquals(SearchOutcome.FOUND, result.outcome());
    assertEquals("Maven Central NOTICE content", result.noticeContent());
    assertNotNull(result.sourceUrl());
    assertTrue(result.sourceUrl().contains("commons-lang3-3.14.0-sources.jar"));
  }

  @Test
  void returnsSourceFoundNoNoticeWhenJarHasNoNotice() throws IOException {
    byte[] jarBytes = createJarBytes(new String[][] {
        {"META-INF/MANIFEST.MF", "Manifest-Version: 1.0"}
    });

    MavenCentralSource source = new MavenCentralSource(new StubHttpClient(jarBytes));
    NoticeSearchResult result = source.search(
        createDep("org.example", "no-notice", "1.0"), PATTERNS);

    assertEquals(SearchOutcome.SOURCE_FOUND_NO_NOTICE, result.outcome());
    assertNull(result.noticeContent());
  }

  @Test
  void returnsNotFoundWhenSourceJarDoesNotExist() {
    MavenCentralSource source = new MavenCentralSource(
        new StubHttpClient(new HttpRequestException("Not Found", 404)));
    NoticeSearchResult result = source.search(
        createDep("org.example", "missing", "1.0"), PATTERNS);

    assertEquals(SearchOutcome.NOT_FOUND, result.outcome());
    assertNull(result.noticeContent());
  }

  @Test
  void returnsErrorOnHttpFailure() {
    MavenCentralSource source = new MavenCentralSource(
        new StubHttpClient(new HttpRequestException("Server Error", 500)));
    NoticeSearchResult result = source.search(
        createDep("org.example", "error", "1.0"), PATTERNS);

    assertEquals(SearchOutcome.ERROR, result.outcome());
    assertNotNull(result.message());
  }

  @Test
  void buildsCorrectSourceJarUrl() {
    MavenCentralSource source = new MavenCentralSource(
        new StubHttpClient(new byte[0]));
    LicensedDependency dep = createDep("org.apache.commons", "commons-lang3", "3.14.0");
    String url = source.buildSourceJarUrl(dep);

    assertEquals(
        "https://repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.14.0/"
            + "commons-lang3-3.14.0-sources.jar",
        url);
  }

  @Test
  void priorityIsThree() {
    MavenCentralSource source = new MavenCentralSource(
        new StubHttpClient(new byte[0]));
    assertEquals(3, source.getPriority());
    assertEquals("MAVEN_CENTRAL_SOURCE_JAR", source.getSourceName());
  }
}

package com.github.noticecollector.notice.source;

import static org.junit.jupiter.api.Assertions.*;

import com.github.noticecollector.config.NoticeCollectorConfig;
import com.github.noticecollector.dependency.Dependency;
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

/** LocalCacheSource のユニットテスト。 */
class LocalCacheSourceTest {

  private static final List<String> PATTERNS =
      List.of("META-INF/NOTICE", "META-INF/NOTICE.txt", "NOTICE");

  private LicensedDependency createDep(String groupId, String artifactId, String version) {
    return new LicensedDependency(
        new Dependency(groupId, artifactId, version, "compile", "jar"),
        "Apache-2.0", "Apache License 2.0", null, "POM");
  }

  private NoticeCollectorConfig createConfig(String localRepo, String gradleCache) {
    NoticeCollectorConfig config = new NoticeCollectorConfig();
    config.getProject().setLocalRepository(localRepo);
    config.getProject().setGradleCache(gradleCache);
    return config;
  }

  /**
   * テスト用 JAR ファイルを作成する。
   *
   * @param jarPath JAR ファイルの出力先
   * @param entries エントリ名と内容のペア（null 内容はディレクトリエントリ）
   */
  private void createTestJar(Path jarPath, String[][] entries) throws IOException {
    Files.createDirectories(jarPath.getParent());
    try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
      for (String[] entry : entries) {
        JarEntry je = new JarEntry(entry[0]);
        jos.putNextEntry(je);
        if (entry[1] != null) {
          jos.write(entry[1].getBytes(StandardCharsets.UTF_8));
        }
        jos.closeEntry();
      }
    }
  }

  @Test
  void findsNoticeInMavenLocalJar(@TempDir Path tempDir) throws IOException {
    // Maven ローカルリポジトリ構造: {repo}/{groupPath}/{artifactId}/{version}/{artifactId}-{version}.jar
    Path jarPath = tempDir.resolve("org/example/mylib/1.0/mylib-1.0.jar");
    createTestJar(jarPath, new String[][] {
        {"META-INF/NOTICE", "Maven local NOTICE content"}
    });

    NoticeCollectorConfig config = createConfig(tempDir.toString(), tempDir.resolve("gradle").toString());
    LocalCacheSource source = new LocalCacheSource(config);
    NoticeSearchResult result = source.search(
        createDep("org.example", "mylib", "1.0"), PATTERNS);

    assertEquals(SearchOutcome.FOUND, result.outcome());
    assertEquals("Maven local NOTICE content", result.noticeContent());
  }

  @Test
  void returnsSourceFoundNoNoticeWhenJarExistsButNoNotice(@TempDir Path tempDir)
      throws IOException {
    Path jarPath = tempDir.resolve("org/example/mylib/1.0/mylib-1.0.jar");
    createTestJar(jarPath, new String[][] {
        {"META-INF/MANIFEST.MF", "Manifest-Version: 1.0"}
    });

    NoticeCollectorConfig config = createConfig(tempDir.toString(), tempDir.resolve("gradle").toString());
    LocalCacheSource source = new LocalCacheSource(config);
    NoticeSearchResult result = source.search(
        createDep("org.example", "mylib", "1.0"), PATTERNS);

    assertEquals(SearchOutcome.SOURCE_FOUND_NO_NOTICE, result.outcome());
    assertNull(result.noticeContent());
  }

  @Test
  void returnsNotFoundWhenJarDoesNotExist(@TempDir Path tempDir) {
    NoticeCollectorConfig config = createConfig(tempDir.toString(), tempDir.resolve("gradle").toString());
    LocalCacheSource source = new LocalCacheSource(config);
    NoticeSearchResult result = source.search(
        createDep("org.nonexistent", "missing", "1.0"), PATTERNS);

    assertEquals(SearchOutcome.NOT_FOUND, result.outcome());
  }

  @Test
  void findsNoticeInGradleCache(@TempDir Path tempDir) throws IOException {
    // Gradle キャッシュ構造: {cache}/{groupId}/{artifactId}/{version}/{hash}/{artifactId}-{version}.jar
    Path hashDir = tempDir.resolve("gradle/org.example/mylib/2.0/abc123def");
    Path jarPath = hashDir.resolve("mylib-2.0.jar");
    createTestJar(jarPath, new String[][] {
        {"META-INF/NOTICE.txt", "Gradle cache NOTICE"}
    });

    // Maven 側は空ディレクトリ（JAR なし）
    NoticeCollectorConfig config = createConfig(
        tempDir.resolve("maven").toString(),
        tempDir.resolve("gradle").toString());
    LocalCacheSource source = new LocalCacheSource(config);
    NoticeSearchResult result = source.search(
        createDep("org.example", "mylib", "2.0"), PATTERNS);

    assertEquals(SearchOutcome.FOUND, result.outcome());
    assertEquals("Gradle cache NOTICE", result.noticeContent());
  }

  @Test
  void prefersFirstPatternMatch(@TempDir Path tempDir) throws IOException {
    // JAR に META-INF/NOTICE と NOTICE の両方が存在する場合、先頭パターンを優先
    Path jarPath = tempDir.resolve("org/example/mylib/1.0/mylib-1.0.jar");
    createTestJar(jarPath, new String[][] {
        {"META-INF/NOTICE", "First pattern match"},
        {"NOTICE", "Second pattern match"}
    });

    NoticeCollectorConfig config = createConfig(tempDir.toString(), tempDir.resolve("gradle").toString());
    LocalCacheSource source = new LocalCacheSource(config);
    NoticeSearchResult result = source.search(
        createDep("org.example", "mylib", "1.0"), PATTERNS);

    assertEquals(SearchOutcome.FOUND, result.outcome());
    assertEquals("First pattern match", result.noticeContent());
  }

  @Test
  void fallsBackToGradleCacheWhenMavenNotFound(@TempDir Path tempDir) throws IOException {
    // Maven 側に JAR なし、Gradle 側に JAR あり
    Path hashDir = tempDir.resolve("gradle/com.example/fallback/1.0/hash1");
    Path jarPath = hashDir.resolve("fallback-1.0.jar");
    createTestJar(jarPath, new String[][] {
        {"NOTICE", "Gradle fallback NOTICE"}
    });

    NoticeCollectorConfig config = createConfig(
        tempDir.resolve("maven").toString(),
        tempDir.resolve("gradle").toString());
    LocalCacheSource source = new LocalCacheSource(config);
    NoticeSearchResult result = source.search(
        createDep("com.example", "fallback", "1.0"), PATTERNS);

    assertEquals(SearchOutcome.FOUND, result.outcome());
    assertEquals("Gradle fallback NOTICE", result.noticeContent());
  }

  @Test
  void priorityIsTwo() {
    NoticeCollectorConfig config = createConfig("/tmp/repo", "/tmp/gradle");
    LocalCacheSource source = new LocalCacheSource(config);
    assertEquals(2, source.getPriority());
    assertEquals("LOCAL_CACHE", source.getSourceName());
  }
}

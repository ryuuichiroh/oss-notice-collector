package com.github.noticecollector.notice.source;

import static org.junit.jupiter.api.Assertions.*;

import com.github.noticecollector.config.NoticeCollectorConfig;
import com.github.noticecollector.dependency.Dependency;
import com.github.noticecollector.http.HttpClientWrapper;
import com.github.noticecollector.http.HttpRequestException;
import com.github.noticecollector.license.LicensedDependency;
import com.github.noticecollector.notice.NoticeSearchResult;
import com.github.noticecollector.notice.NoticeSearchResult.SearchOutcome;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;

/** PrivateRepoSource のユニットテスト。 */
class PrivateRepoSourceTest {

  private static final List<String> PATTERNS =
      List.of("META-INF/NOTICE", "META-INF/NOTICE.txt", "NOTICE");

  private static final List<String> LICENSE_PATTERNS =
      List.of("META-INF/LICENSE", "META-INF/LICENSE.txt", "LICENSE");

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

  /** URL ごとにレスポンスを制御する HttpClientWrapper スタブ。 */
  private static class StubHttpClient extends HttpClientWrapper {
    private final Map<String, byte[]> responses;
    private final Map<String, HttpRequestException> errors;

    StubHttpClient(Map<String, byte[]> responses, Map<String, HttpRequestException> errors) {
      super(new NoticeCollectorConfig());
      this.responses = responses != null ? responses : Map.of();
      this.errors = errors != null ? errors : Map.of();
    }

    @Override
    public byte[] get(String url) throws HttpRequestException {
      // URL のプレフィックスマッチで検索
      for (Map.Entry<String, HttpRequestException> entry : errors.entrySet()) {
        if (url.startsWith(entry.getKey()) || url.equals(entry.getKey())) {
          throw entry.getValue();
        }
      }
      for (Map.Entry<String, byte[]> entry : responses.entrySet()) {
        if (url.startsWith(entry.getKey()) || url.equals(entry.getKey())) {
          return entry.getValue();
        }
      }
      throw new HttpRequestException("Not Found: " + url, 404);
    }
  }

  private NoticeCollectorConfig createConfigWithRepos(
      List<NoticeCollectorConfig.RepositoryConfig> repos) {
    NoticeCollectorConfig config = new NoticeCollectorConfig();
    config.setRepositories(repos);
    return config;
  }

  private NoticeCollectorConfig.RepositoryConfig createRepo(String name, String url) {
    NoticeCollectorConfig.RepositoryConfig repo = new NoticeCollectorConfig.RepositoryConfig();
    repo.setName(name);
    repo.setUrl(url);
    return repo;
  }

  @Test
  void returnsNotFoundWhenNoRepositoriesConfigured() {
    NoticeCollectorConfig config = createConfigWithRepos(List.of());
    PrivateRepoSource source = new PrivateRepoSource(config,
        new StubHttpClient(null, null));
    NoticeSearchResult result = source.search(
        createDep("org.example", "lib", "1.0"), PATTERNS, LICENSE_PATTERNS);

    assertEquals(SearchOutcome.NOT_FOUND, result.outcome());
  }

  @Test
  void findsNoticeInPrivateRepo() throws IOException {
    byte[] jarBytes = createJarBytes(new String[][] {
        {"META-INF/NOTICE", "Private repo NOTICE"}
    });

    String repoUrl = "https://repo.internal.example.com/maven";
    Map<String, byte[]> responses = new HashMap<>();
    responses.put(repoUrl, jarBytes);

    NoticeCollectorConfig config = createConfigWithRepos(
        List.of(createRepo("internal", repoUrl)));
    PrivateRepoSource source = new PrivateRepoSource(config,
        new StubHttpClient(responses, null));
    NoticeSearchResult result = source.search(
        createDep("org.example", "lib", "1.0"), PATTERNS, LICENSE_PATTERNS);

    assertEquals(SearchOutcome.FOUND, result.outcome());
    assertEquals("Private repo NOTICE", result.noticeContent());
    assertTrue(result.sourceUrl().contains("lib-1.0-sources.jar"));
  }

  @Test
  void returnsSourceFoundNoNoticeWhenJarHasNoNotice() throws IOException {
    byte[] jarBytes = createJarBytes(new String[][] {
        {"META-INF/MANIFEST.MF", "Manifest-Version: 1.0"}
    });

    String repoUrl = "https://repo.internal.example.com/maven";
    Map<String, byte[]> responses = new HashMap<>();
    responses.put(repoUrl, jarBytes);

    NoticeCollectorConfig config = createConfigWithRepos(
        List.of(createRepo("internal", repoUrl)));
    PrivateRepoSource source = new PrivateRepoSource(config,
        new StubHttpClient(responses, null));
    NoticeSearchResult result = source.search(
        createDep("org.example", "no-notice", "1.0"), PATTERNS, LICENSE_PATTERNS);

    assertEquals(SearchOutcome.SOURCE_FOUND_NO_NOTICE, result.outcome());
    assertNull(result.noticeContent());
  }

  @Test
  void returnsNotFoundWhenSourceJarNotInRepo() {
    String repoUrl = "https://repo.internal.example.com/maven";
    Map<String, HttpRequestException> errors = new HashMap<>();
    errors.put(repoUrl, new HttpRequestException("Not Found", 404));

    NoticeCollectorConfig config = createConfigWithRepos(
        List.of(createRepo("internal", repoUrl)));
    PrivateRepoSource source = new PrivateRepoSource(config,
        new StubHttpClient(null, errors));
    NoticeSearchResult result = source.search(
        createDep("org.example", "missing", "1.0"), PATTERNS, LICENSE_PATTERNS);

    assertEquals(SearchOutcome.NOT_FOUND, result.outcome());
  }

  @Test
  void triesMultipleReposAndFindsInSecond() throws IOException {
    byte[] jarBytes = createJarBytes(new String[][] {
        {"META-INF/NOTICE", "Second repo NOTICE"}
    });

    String repo1Url = "https://repo1.example.com/maven";
    String repo2Url = "https://repo2.example.com/maven";

    Map<String, HttpRequestException> errors = new HashMap<>();
    errors.put(repo1Url, new HttpRequestException("Not Found", 404));

    Map<String, byte[]> responses = new HashMap<>();
    responses.put(repo2Url, jarBytes);

    List<NoticeCollectorConfig.RepositoryConfig> repos = new ArrayList<>();
    repos.add(createRepo("repo1", repo1Url));
    repos.add(createRepo("repo2", repo2Url));

    NoticeCollectorConfig config = createConfigWithRepos(repos);
    PrivateRepoSource source = new PrivateRepoSource(config,
        new StubHttpClient(responses, errors));
    NoticeSearchResult result = source.search(
        createDep("org.example", "lib", "1.0"), PATTERNS, LICENSE_PATTERNS);

    assertEquals(SearchOutcome.FOUND, result.outcome());
    assertEquals("Second repo NOTICE", result.noticeContent());
    assertTrue(result.sourceUrl().startsWith(repo2Url));
  }

  @Test
  void returnsErrorWhenAllReposReturnError() {
    String repoUrl = "https://repo.internal.example.com/maven";
    Map<String, HttpRequestException> errors = new HashMap<>();
    errors.put(repoUrl, new HttpRequestException("Server Error", 500));

    NoticeCollectorConfig config = createConfigWithRepos(
        List.of(createRepo("internal", repoUrl)));
    PrivateRepoSource source = new PrivateRepoSource(config,
        new StubHttpClient(null, errors));
    NoticeSearchResult result = source.search(
        createDep("org.example", "error", "1.0"), PATTERNS, LICENSE_PATTERNS);

    assertEquals(SearchOutcome.ERROR, result.outcome());
    assertNotNull(result.message());
  }

  @Test
  void buildsCorrectSourceJarUrl() {
    NoticeCollectorConfig config = createConfigWithRepos(List.of());
    PrivateRepoSource source = new PrivateRepoSource(config,
        new StubHttpClient(null, null));
    LicensedDependency dep = createDep("org.apache.commons", "commons-lang3", "3.14.0");

    String url = source.buildSourceJarUrl(
        "https://repo.example.com/maven", dep);
    assertEquals(
        "https://repo.example.com/maven/org/apache/commons/commons-lang3/3.14.0/"
            + "commons-lang3-3.14.0-sources.jar",
        url);
  }

  @Test
  void buildsCorrectUrlWithTrailingSlash() {
    NoticeCollectorConfig config = createConfigWithRepos(List.of());
    PrivateRepoSource source = new PrivateRepoSource(config,
        new StubHttpClient(null, null));
    LicensedDependency dep = createDep("org.example", "lib", "1.0");

    String url = source.buildSourceJarUrl(
        "https://repo.example.com/maven/", dep);
    assertEquals(
        "https://repo.example.com/maven/org/example/lib/1.0/lib-1.0-sources.jar",
        url);
  }

  @Test
  void priorityIsFour() {
    NoticeCollectorConfig config = createConfigWithRepos(List.of());
    PrivateRepoSource source = new PrivateRepoSource(config,
        new StubHttpClient(null, null));
    assertEquals(4, source.getPriority());
    assertEquals("PRIVATE_REPO", source.getSourceName());
  }
}

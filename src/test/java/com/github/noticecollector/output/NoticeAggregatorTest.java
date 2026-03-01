package com.github.noticecollector.output;

import static org.junit.jupiter.api.Assertions.*;

import com.github.noticecollector.dependency.Dependency;
import com.github.noticecollector.model.CollectionResult;
import com.github.noticecollector.model.CollectionStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NoticeAggregatorTest {

  @TempDir Path tempDir;

  private static Dependency dep(String groupId, String artifactId, String version) {
    return new Dependency(groupId, artifactId, version, "compile", "jar");
  }

  private static CollectionResult successResult(
      Dependency d, String sourceUrl, String noticeContent) {
    return new CollectionResult(
        d, "Apache-2.0", CollectionStatus.SUCCESS, "LOCAL_CACHE", sourceUrl, null, noticeContent,
        null);
  }

  private static CollectionResult failedResult(Dependency d) {
    return new CollectionResult(
        d, "Apache-2.0", CollectionStatus.FAILED, null, null, null, null, "No source found");
  }

  @Test
  void aggregate_writesFile() throws Exception {
    NoticeAggregator aggregator = new NoticeAggregator(tempDir, "THIRD-PARTY-NOTICES.txt");
    Dependency d1 = dep("org.apache", "commons-lang3", "3.14.0");

    aggregator.aggregate(List.of(successResult(d1, "https://example.com", "Sample NOTICE")));

    Path outputPath = tempDir.resolve("THIRD-PARTY-NOTICES.txt");
    assertTrue(Files.exists(outputPath));
    String content = Files.readString(outputPath);
    assertFalse(content.isEmpty());
  }

  @Test
  void aggregate_entryContainsHeaderAndSeparator() throws Exception {
    NoticeAggregator aggregator = new NoticeAggregator(tempDir, "THIRD-PARTY-NOTICES.txt");
    Dependency d1 = dep("org.apache", "commons-lang3", "3.14.0");

    aggregator.aggregate(
        List.of(successResult(d1, "https://repo1.maven.org/source.jar", "Apache NOTICE content")));

    String content = Files.readString(tempDir.resolve("THIRD-PARTY-NOTICES.txt"));

    assertTrue(content.contains("artifactId: commons-lang3"));
    assertTrue(content.contains("version: 3.14.0"));
    assertTrue(content.contains("groupId: org.apache"));
    assertTrue(content.contains("sourceUrl: https://repo1.maven.org/source.jar"));
    assertTrue(content.contains(NoticeAggregator.SEPARATOR));
    assertTrue(content.contains("Apache NOTICE content"));
  }

  @Test
  void aggregate_multipleEntries_allIncluded() throws Exception {
    NoticeAggregator aggregator = new NoticeAggregator(tempDir, "THIRD-PARTY-NOTICES.txt");
    Dependency d1 = dep("org.apache", "lib-a", "1.0");
    Dependency d2 = dep("com.google", "lib-b", "2.0");

    aggregator.aggregate(List.of(
        successResult(d1, "https://url-a", "NOTICE A"),
        successResult(d2, "https://url-b", "NOTICE B")));

    String content = Files.readString(tempDir.resolve("THIRD-PARTY-NOTICES.txt"));

    assertTrue(content.contains("artifactId: lib-a"));
    assertTrue(content.contains("NOTICE A"));
    assertTrue(content.contains("artifactId: lib-b"));
    assertTrue(content.contains("NOTICE B"));
  }

  @Test
  void aggregate_filtersOnlySuccess() throws Exception {
    NoticeAggregator aggregator = new NoticeAggregator(tempDir, "THIRD-PARTY-NOTICES.txt");
    Dependency d1 = dep("org.apache", "lib-a", "1.0");
    Dependency d2 = dep("org.apache", "lib-b", "2.0");

    aggregator.aggregate(List.of(
        successResult(d1, "https://url-a", "NOTICE A"),
        failedResult(d2)));

    String content = Files.readString(tempDir.resolve("THIRD-PARTY-NOTICES.txt"));

    assertTrue(content.contains("NOTICE A"));
    assertFalse(content.contains("lib-b"));
  }

  @Test
  void aggregate_emptyResults_producesEmptyFile() throws Exception {
    NoticeAggregator aggregator = new NoticeAggregator(tempDir, "THIRD-PARTY-NOTICES.txt");

    aggregator.aggregate(List.of());

    String content = Files.readString(tempDir.resolve("THIRD-PARTY-NOTICES.txt"));
    assertEquals("", content);
  }

  @Test
  void aggregate_nullSourceUrl_showsNA() throws Exception {
    NoticeAggregator aggregator = new NoticeAggregator(tempDir, "THIRD-PARTY-NOTICES.txt");
    Dependency d1 = dep("org.apache", "lib-a", "1.0");

    aggregator.aggregate(List.of(successResult(d1, null, "NOTICE content")));

    String content = Files.readString(tempDir.resolve("THIRD-PARTY-NOTICES.txt"));
    assertTrue(content.contains("sourceUrl: N/A"));
  }

  @Test
  void buildContent_successCountMatchesEntries() {
    NoticeAggregator aggregator = new NoticeAggregator(tempDir, "THIRD-PARTY-NOTICES.txt");
    Dependency d1 = dep("org.apache", "lib-a", "1.0");
    Dependency d2 = dep("org.apache", "lib-b", "2.0");
    Dependency d3 = dep("org.apache", "lib-c", "3.0");

    String content = aggregator.buildContent(List.of(
        successResult(d1, "https://url-a", "NOTICE A"),
        failedResult(d2),
        successResult(d3, "https://url-c", "NOTICE C")));

    // Count separator pairs (each entry has 2 separators)
    long separatorCount =
        content.lines().filter(line -> line.equals(NoticeAggregator.SEPARATOR)).count();
    assertEquals(4, separatorCount); // 2 SUCCESS entries × 2 separators each
  }

  @Test
  void constructor_rejectsNullOutputDirectory() {
    assertThrows(
        NullPointerException.class,
        () -> new NoticeAggregator(null, "THIRD-PARTY-NOTICES.txt"));
  }

  @Test
  void constructor_rejectsNullFileName() {
    assertThrows(NullPointerException.class, () -> new NoticeAggregator(tempDir, null));
  }
}

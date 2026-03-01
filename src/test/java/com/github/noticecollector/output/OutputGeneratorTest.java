package com.github.noticecollector.output;

import static org.junit.jupiter.api.Assertions.*;

import com.github.noticecollector.dependency.Dependency;
import com.github.noticecollector.license.LicensedDependency;
import com.github.noticecollector.model.CollectionResult;
import com.github.noticecollector.model.CollectionStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutputGeneratorTest {

  @TempDir
  Path tempDir;

  private Dependency dep(String groupId, String artifactId, String version) {
    return new Dependency(groupId, artifactId, version, "compile", "jar");
  }

  @Test
  void generateOutput_savesNoticesAndGeneratesReportAndAggregated() throws Exception {
    NoticeFileSaver saver = new NoticeFileSaver(tempDir);
    ReportGenerator reporter = new ReportGenerator(tempDir, "collection-report.json");
    NoticeAggregator aggregator = new NoticeAggregator(tempDir, "THIRD-PARTY-NOTICES.txt");
    OutputGenerator generator = new OutputGenerator(saver, reporter, aggregator);

    Dependency d1 = dep("org.example", "lib-a", "1.0.0");
    Dependency d2 = dep("org.example", "lib-b", "2.0.0");

    List<CollectionResult> results = List.of(
        new CollectionResult(d1, "Apache-2.0", CollectionStatus.SUCCESS,
            "MAVEN_CENTRAL", "https://repo1.maven.org", null, "Notice A content", null),
        new CollectionResult(d2, "Apache-2.0", CollectionStatus.FAILED,
            null, null, null, null, "Not found in any source"));

    List<LicensedDependency> allDeps = List.of(
        new LicensedDependency(d1, "Apache-2.0", "Apache License 2.0", null, "POM"),
        new LicensedDependency(d2, "Apache-2.0", "Apache License 2.0", null, "POM"));

    generator.generateOutput(results, allDeps, null);

    // Individual NOTICE saved
    Path noticePath = tempDir.resolve("notices/org.example/lib-a/1.0.0/NOTICE");
    assertTrue(Files.exists(noticePath));
    assertEquals("Notice A content", Files.readString(noticePath));

    // JSON report generated
    assertTrue(Files.exists(tempDir.resolve("collection-report.json")));

    // Aggregated NOTICE generated
    Path aggregatedPath = tempDir.resolve("THIRD-PARTY-NOTICES.txt");
    assertTrue(Files.exists(aggregatedPath));
    String aggregated = Files.readString(aggregatedPath);
    assertTrue(aggregated.contains("lib-a"));
    assertTrue(aggregated.contains("Notice A content"));
  }

  @Test
  void generateOutput_emptyResults_generatesEmptyOutputs() throws Exception {
    NoticeFileSaver saver = new NoticeFileSaver(tempDir);
    ReportGenerator reporter = new ReportGenerator(tempDir, "collection-report.json");
    NoticeAggregator aggregator = new NoticeAggregator(tempDir, "THIRD-PARTY-NOTICES.txt");
    OutputGenerator generator = new OutputGenerator(saver, reporter, aggregator);

    generator.generateOutput(List.of(), List.of(), null);

    assertTrue(Files.exists(tempDir.resolve("collection-report.json")));
    assertTrue(Files.exists(tempDir.resolve("THIRD-PARTY-NOTICES.txt")));
  }

  @Test
  void constructor_nullComponent_throwsNpe() {
    assertThrows(NullPointerException.class,
        () -> new OutputGenerator(null, null, null));
  }

  @Test
  void generateOutput_nullResults_throwsNpe() {
    NoticeFileSaver saver = new NoticeFileSaver(tempDir);
    ReportGenerator reporter = new ReportGenerator(tempDir, "report.json");
    NoticeAggregator aggregator = new NoticeAggregator(tempDir, "NOTICES.txt");
    OutputGenerator generator = new OutputGenerator(saver, reporter, aggregator);

    assertThrows(NullPointerException.class,
        () -> generator.generateOutput(null, List.of(), null));
  }
}

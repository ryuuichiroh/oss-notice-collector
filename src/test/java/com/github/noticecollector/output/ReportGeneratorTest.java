package com.github.noticecollector.output;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.noticecollector.dependency.Dependency;
import com.github.noticecollector.license.LicensedDependency;
import com.github.noticecollector.model.CollectionResult;
import com.github.noticecollector.model.CollectionStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportGeneratorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path tempDir;

  private static Dependency dep(String groupId, String artifactId, String version) {
    return new Dependency(groupId, artifactId, version, "compile", "jar");
  }

  private static LicensedDependency licensed(Dependency d, String spdxId) {
    return new LicensedDependency(d, spdxId, spdxId, null, "POM");
  }

  private static CollectionResult result(
      Dependency d, String spdxId, CollectionStatus status, String sourceName, String sourceUrl,
      String failureReason) {
    return new CollectionResult(d, spdxId, status, sourceName, sourceUrl, null, null,
        null, null, failureReason);
  }

  @Test
  void generate_writesJsonFile() throws Exception {
    ReportGenerator generator = new ReportGenerator(tempDir, "collection-report.json");
    Dependency d1 = dep("org.apache", "commons-lang3", "3.14.0");

    List<CollectionResult> results = List.of(
        result(d1, "Apache-2.0", CollectionStatus.SUCCESS, "MAVEN_CENTRAL", "https://example.com",
            null));
    List<LicensedDependency> all = List.of(licensed(d1, "Apache-2.0"));

    generator.generate(results, all);

    Path reportPath = tempDir.resolve("collection-report.json");
    assertTrue(Files.exists(reportPath));

    JsonNode root = MAPPER.readTree(reportPath.toFile());
    assertNotNull(root.get("summary"));
    assertNotNull(root.get("results"));
  }

  @Test
  void generate_summaryCountsMatchResults() throws Exception {
    ReportGenerator generator = new ReportGenerator(tempDir, "report.json");

    Dependency d1 = dep("org.apache", "lib-a", "1.0");
    Dependency d2 = dep("org.apache", "lib-b", "2.0");
    Dependency d3 = dep("com.example", "lib-c", "3.0");
    Dependency d4 = dep("com.example", "lib-d", "4.0");

    List<LicensedDependency> all = List.of(
        licensed(d1, "Apache-2.0"),
        licensed(d2, "Apache-2.0"),
        licensed(d3, "MIT"),
        licensed(d4, "UNKNOWN_LICENSE"));

    List<CollectionResult> results = List.of(
        result(d1, "Apache-2.0", CollectionStatus.SUCCESS, "LOCAL_CACHE", null, null),
        result(d2, "Apache-2.0", CollectionStatus.FAILED, null, null, "No source found"),
        result(d4, "UNKNOWN_LICENSE", CollectionStatus.UNKNOWN_LICENSE, null, null, null));

    generator.generate(results, all);

    JsonNode root = MAPPER.readTree(tempDir.resolve("report.json").toFile());
    JsonNode summary = root.get("summary");

    assertEquals(4, summary.get("totalDependencies").asInt());
    assertEquals(2, summary.get("apache2Count").asInt());
    assertEquals(1, summary.get("successCount").asInt());
    assertEquals(0, summary.get("notRequiredCount").asInt());
    assertEquals(1, summary.get("failedCount").asInt());
    assertEquals(1, summary.get("unknownLicenseCount").asInt());
    assertEquals(3, root.get("results").size());
  }

  @Test
  void generate_failedEntry_hasFailureReason() throws Exception {
    ReportGenerator generator = new ReportGenerator(tempDir, "report.json");
    Dependency d1 = dep("org.apache", "lib-a", "1.0");

    List<CollectionResult> results = List.of(
        result(d1, "Apache-2.0", CollectionStatus.FAILED, null, null, "All sources exhausted"));
    List<LicensedDependency> all = List.of(licensed(d1, "Apache-2.0"));

    generator.generate(results, all);

    JsonNode root = MAPPER.readTree(tempDir.resolve("report.json").toFile());
    JsonNode entry = root.get("results").get(0);

    assertEquals("FAILED", entry.get("status").asText());
    assertEquals("All sources exhausted", entry.get("failureReason").asText());
  }

  @Test
  void generate_notRequiredEntry_hasSourceUrlAndReason() throws Exception {
    ReportGenerator generator = new ReportGenerator(tempDir, "report.json");
    Dependency d1 = dep("org.apache", "lib-a", "1.0");

    List<CollectionResult> results = List.of(
        result(d1, "Apache-2.0", CollectionStatus.NOT_REQUIRED, "LOCAL_CACHE",
            "https://repo1.maven.org/jar", null));
    List<LicensedDependency> all = List.of(licensed(d1, "Apache-2.0"));

    generator.generate(results, all);

    JsonNode root = MAPPER.readTree(tempDir.resolve("report.json").toFile());
    JsonNode entry = root.get("results").get(0);

    assertEquals("NOT_REQUIRED", entry.get("status").asText());
    assertEquals("https://repo1.maven.org/jar", entry.get("notRequiredSourceUrl").asText());
    assertNotNull(entry.get("notRequiredReason"));
    assertTrue(entry.get("notRequiredReason").asText().contains("LOCAL_CACHE"));
  }

  @Test
  void generate_emptyResults_producesValidJson() throws Exception {
    ReportGenerator generator = new ReportGenerator(tempDir, "report.json");

    generator.generate(List.of(), List.of());

    JsonNode root = MAPPER.readTree(tempDir.resolve("report.json").toFile());
    assertEquals(0, root.get("summary").get("totalDependencies").asInt());
    assertEquals(0, root.get("results").size());
  }

  @Test
  void generate_nullFieldsOmittedInJson() throws Exception {
    ReportGenerator generator = new ReportGenerator(tempDir, "report.json");
    Dependency d1 = dep("org.apache", "lib-a", "1.0");

    List<CollectionResult> results = List.of(
        result(d1, "Apache-2.0", CollectionStatus.SUCCESS, "LOCAL_CACHE", null, null));
    List<LicensedDependency> all = List.of(licensed(d1, "Apache-2.0"));

    generator.generate(results, all);

    JsonNode root = MAPPER.readTree(tempDir.resolve("report.json").toFile());
    JsonNode entry = root.get("results").get(0);

    // null fields should be omitted due to @JsonInclude(NON_NULL)
    assertNull(entry.get("failureReason"));
    assertNull(entry.get("noticeSavedPath"));
    assertNull(entry.get("licenseSavedPath"));
    assertNull(entry.get("notRequiredSourceUrl"));
    assertNull(entry.get("notRequiredReason"));
  }

  @Test
  void constructor_rejectsNullOutputDirectory() {
    assertThrows(NullPointerException.class, () -> new ReportGenerator(null, "report.json"));
  }

  @Test
  void constructor_rejectsNullReportFileName() {
    assertThrows(NullPointerException.class, () -> new ReportGenerator(tempDir, null));
  }
}

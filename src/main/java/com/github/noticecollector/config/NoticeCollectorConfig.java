package com.github.noticecollector.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;

/**
 * YAML 設定ファイルに対応する設定 POJO。全フィールドにデフォルト値を持つ。
 *
 * <p>SnakeYAML によるデシリアライズ対象のため、getter/setter は内部表現を直接公開する。
 */
@SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification = "YAML デシリアライズ用 POJO のため防御的コピーは不要")
public class NoticeCollectorConfig {

  private ProjectConfig project = new ProjectConfig();
  private OutputConfig output = new OutputConfig();
  private List<RepositoryConfig> repositories = new ArrayList<>();
  private GithubConfig github = new GithubConfig();
  private ApacheArchiveConfig apacheArchive = new ApacheArchiveConfig();
  private List<OverrideConfig> overrides = new ArrayList<>();
  private List<SourceRepoConfig> sourceRepositories = new ArrayList<>();
  private List<String> targetLicenses = new ArrayList<>();
  private ExternalDefinitionsConfig externalDefinitions = new ExternalDefinitionsConfig();

  public ProjectConfig getProject() {
    return project;
  }

  public void setProject(ProjectConfig project) {
    this.project = project;
  }

  public OutputConfig getOutput() {
    return output;
  }

  public void setOutput(OutputConfig output) {
    this.output = output;
  }

  public List<RepositoryConfig> getRepositories() {
    return repositories;
  }

  public void setRepositories(List<RepositoryConfig> repositories) {
    this.repositories = repositories;
  }

  public GithubConfig getGithub() {
    return github;
  }

  public void setGithub(GithubConfig github) {
    this.github = github;
  }

  public ApacheArchiveConfig getApacheArchive() {
    return apacheArchive;
  }

  public void setApacheArchive(ApacheArchiveConfig apacheArchive) {
    this.apacheArchive = apacheArchive;
  }

  public List<OverrideConfig> getOverrides() {
    return overrides;
  }

  public void setOverrides(List<OverrideConfig> overrides) {
    this.overrides = overrides;
  }

  public List<SourceRepoConfig> getSourceRepositories() {
    return sourceRepositories;
  }

  public void setSourceRepositories(List<SourceRepoConfig> sourceRepositories) {
    this.sourceRepositories = sourceRepositories;
  }

  public List<String> getTargetLicenses() {
    return targetLicenses;
  }

  public void setTargetLicenses(List<String> targetLicenses) {
    this.targetLicenses = targetLicenses;
  }

  public ExternalDefinitionsConfig getExternalDefinitions() {
    return externalDefinitions;
  }

  public void setExternalDefinitions(ExternalDefinitionsConfig externalDefinitions) {
    this.externalDefinitions = externalDefinitions;
  }

  /** プロジェクト設定。 */
  @SuppressFBWarnings(
      value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
      justification = "YAML デシリアライズ用 POJO")
  public static class ProjectConfig {
    private String path = ".";
    private String buildTool = "auto";
    private String localRepository;
    private String gradleCache;
    private MavenScopeConfig maven = new MavenScopeConfig();
    private GradleConfigurationConfig gradle = new GradleConfigurationConfig();

    public String getPath() {
      return path;
    }

    public void setPath(String path) {
      this.path = path;
    }

    public String getBuildTool() {
      return buildTool;
    }

    public void setBuildTool(String buildTool) {
      this.buildTool = buildTool;
    }

    public String getLocalRepository() {
      return localRepository;
    }

    public void setLocalRepository(String localRepository) {
      this.localRepository = localRepository;
    }

    public String getGradleCache() {
      return gradleCache;
    }

    public void setGradleCache(String gradleCache) {
      this.gradleCache = gradleCache;
    }

    public MavenScopeConfig getMaven() {
      return maven;
    }

    public void setMaven(MavenScopeConfig maven) {
      this.maven = maven;
    }

    public GradleConfigurationConfig getGradle() {
      return gradle;
    }

    public void setGradle(GradleConfigurationConfig gradle) {
      this.gradle = gradle;
    }
  }

  /** Maven スコープ設定。 */
  @SuppressFBWarnings(
      value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
      justification = "YAML デシリアライズ用 POJO")
  public static class MavenScopeConfig {
    private List<String> scopes = new ArrayList<>(List.of("compile", "runtime"));

    public List<String> getScopes() {
      return scopes;
    }

    public void setScopes(List<String> scopes) {
      this.scopes = scopes;
    }
  }

  /** Gradle configuration 設定。 */
  @SuppressFBWarnings(
      value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
      justification = "YAML デシリアライズ用 POJO")
  public static class GradleConfigurationConfig {
    private List<String> configurations = new ArrayList<>(List.of("runtimeClasspath"));

    public List<String> getConfigurations() {
      return configurations;
    }

    public void setConfigurations(List<String> configurations) {
      this.configurations = configurations;
    }
  }

  /** 出力設定。 */
  public static class OutputConfig {
    private String directory = "./output";
    private String aggregatedFile = "THIRD-PARTY-LEGAL.txt";
    private String reportFile = "collection-report.json";

    public String getDirectory() {
      return directory;
    }

    public void setDirectory(String directory) {
      this.directory = directory;
    }

    public String getAggregatedFile() {
      return aggregatedFile;
    }

    public void setAggregatedFile(String aggregatedFile) {
      this.aggregatedFile = aggregatedFile;
    }

    public String getReportFile() {
      return reportFile;
    }

    public void setReportFile(String reportFile) {
      this.reportFile = reportFile;
    }
  }

  /** 社内リポジトリ設定。 */
  @SuppressFBWarnings(
      value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
      justification = "YAML デシリアライズ用 POJO")
  public static class RepositoryConfig {
    private String name;
    private String url;
    private String type = "maven";
    private AuthConfig auth;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getUrl() {
      return url;
    }

    public void setUrl(String url) {
      this.url = url;
    }

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }

    public AuthConfig getAuth() {
      return auth;
    }

    public void setAuth(AuthConfig auth) {
      this.auth = auth;
    }
  }

  /** 認証設定。 */
  public static class AuthConfig {
    private String type = "basic";
    private String usernameEnv;
    private String passwordEnv;
    private String tokenEnv;

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }

    public String getUsernameEnv() {
      return usernameEnv;
    }

    public void setUsernameEnv(String usernameEnv) {
      this.usernameEnv = usernameEnv;
    }

    public String getPasswordEnv() {
      return passwordEnv;
    }

    public void setPasswordEnv(String passwordEnv) {
      this.passwordEnv = passwordEnv;
    }

    public String getTokenEnv() {
      return tokenEnv;
    }

    public void setTokenEnv(String tokenEnv) {
      this.tokenEnv = tokenEnv;
    }
  }

  /** GitHub API 設定。 */
  public static class GithubConfig {
    private String tokenEnv = "GITHUB_TOKEN";
    private String apiBaseUrl = "https://api.github.com";

    public String getTokenEnv() {
      return tokenEnv;
    }

    public void setTokenEnv(String tokenEnv) {
      this.tokenEnv = tokenEnv;
    }

    public String getApiBaseUrl() {
      return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
      this.apiBaseUrl = apiBaseUrl;
    }
  }

  /** Apache Archive 設定。 */
  @SuppressFBWarnings(
      value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
      justification = "YAML デシリアライズ用 POJO")
  public static class ApacheArchiveConfig {
    private boolean enabled = true;
    private String baseUrl = "https://archive.apache.org/dist/";
    private int maxDownloadSizeMb = 50;
    private List<ArchiveMappingConfig> mappings = new ArrayList<>();

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public int getMaxDownloadSizeMb() {
      return maxDownloadSizeMb;
    }

    public void setMaxDownloadSizeMb(int maxDownloadSizeMb) {
      this.maxDownloadSizeMb = maxDownloadSizeMb;
    }

    public List<ArchiveMappingConfig> getMappings() {
      return mappings;
    }

    public void setMappings(List<ArchiveMappingConfig> mappings) {
      this.mappings = mappings;
    }
  }

  /** Apache Archive マッピング設定。 */
  public static class ArchiveMappingConfig {
    private String groupId;
    private String artifactId;
    private String archivePath;

    public String getGroupId() {
      return groupId;
    }

    public void setGroupId(String groupId) {
      this.groupId = groupId;
    }

    public String getArtifactId() {
      return artifactId;
    }

    public void setArtifactId(String artifactId) {
      this.artifactId = artifactId;
    }

    public String getArchivePath() {
      return archivePath;
    }

    public void setArchivePath(String archivePath) {
      this.archivePath = archivePath;
    }
  }

  /** バージョン単位のオーバーライド設定。 */
  public static class OverrideConfig {
    private String groupId;
    private String artifactId;
    private String version;
    private String spdxId;
    private String noticePath;
    private String noticeUrl;

    public String getGroupId() {
      return groupId;
    }

    public void setGroupId(String groupId) {
      this.groupId = groupId;
    }

    public String getArtifactId() {
      return artifactId;
    }

    public void setArtifactId(String artifactId) {
      this.artifactId = artifactId;
    }

    public String getVersion() {
      return version;
    }

    public void setVersion(String version) {
      this.version = version;
    }

    public String getSpdxId() {
      return spdxId;
    }

    public void setSpdxId(String spdxId) {
      this.spdxId = spdxId;
    }

    public String getNoticePath() {
      return noticePath;
    }

    public void setNoticePath(String noticePath) {
      this.noticePath = noticePath;
    }

    public String getNoticeUrl() {
      return noticeUrl;
    }

    public void setNoticeUrl(String noticeUrl) {
      this.noticeUrl = noticeUrl;
    }
  }

  /** ソースコードリポジトリ設定。 */
  public static class SourceRepoConfig {
    private String groupId;
    private String artifactId;
    private String repoUrl;

    public String getGroupId() {
      return groupId;
    }

    public void setGroupId(String groupId) {
      this.groupId = groupId;
    }

    public String getArtifactId() {
      return artifactId;
    }

    public void setArtifactId(String artifactId) {
      this.artifactId = artifactId;
    }

    public String getRepoUrl() {
      return repoUrl;
    }

    public void setRepoUrl(String repoUrl) {
      this.repoUrl = repoUrl;
    }
  }

  /** 外部定義ファイル設定。 */
  public static class ExternalDefinitionsConfig {
    private String licenseMappings = "license-mappings.yaml";
    private String noticePatterns = "notice-patterns.yaml";

    public String getLicenseMappings() {
      return licenseMappings;
    }

    public void setLicenseMappings(String licenseMappings) {
      this.licenseMappings = licenseMappings;
    }

    public String getNoticePatterns() {
      return noticePatterns;
    }

    public void setNoticePatterns(String noticePatterns) {
      this.noticePatterns = noticePatterns;
    }
  }
}

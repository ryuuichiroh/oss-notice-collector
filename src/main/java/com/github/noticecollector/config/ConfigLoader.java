package com.github.noticecollector.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

/** SnakeYAML による YAML 設定ファイル読込。デフォルト値適用とパストラバーサル検証を行う。 */
public class ConfigLoader {

  private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);

  /**
   * 指定パスの YAML 設定ファイルを読み込み、NoticeCollectorConfig を返す。 ファイルに記載のない項目にはデフォルト値が適用される。
   *
   * @param configPath 設定ファイルのパス
   * @return 設定オブジェクト
   * @throws ConfigLoadException 読込失敗時
   */
  public NoticeCollectorConfig load(Path configPath) throws ConfigLoadException {
    validatePath(configPath.toString());

    if (!Files.exists(configPath)) {
      throw new ConfigLoadException("設定ファイルが見つかりません: " + configPath);
    }

    try (InputStream in = Files.newInputStream(configPath)) {
      LoaderOptions loaderOptions = new LoaderOptions();
      Constructor constructor = new Constructor(NoticeCollectorConfig.class, loaderOptions);
      Yaml yaml = new Yaml(constructor);
      NoticeCollectorConfig config = yaml.load(in);

      if (config == null) {
        config = new NoticeCollectorConfig();
      }

      applyDefaults(config);
      validateConfig(config);

      logger.info("設定ファイルを読み込みました: {}", configPath);
      return config;
    } catch (IOException e) {
      throw new ConfigLoadException("設定ファイルの読込に失敗しました: " + configPath, e);
    }
  }

  /**
   * デフォルト設定を返す（設定ファイル未指定時）。
   *
   * @return デフォルト設定オブジェクト
   */
  public NoticeCollectorConfig loadDefaults() {
    return new NoticeCollectorConfig();
  }

  /**
   * null のネストオブジェクトにデフォルトインスタンスを適用する。 YAML で一部の項目のみ記載された場合に、未記載のネストオブジェクトが null になるのを防ぐ。
   */
  private void applyDefaults(NoticeCollectorConfig config) {
    if (config.getProject() == null) {
      config.setProject(new NoticeCollectorConfig.ProjectConfig());
    }
    NoticeCollectorConfig.ProjectConfig project = config.getProject();
    if (project.getMaven() == null) {
      project.setMaven(new NoticeCollectorConfig.MavenScopeConfig());
    }
    if (project.getGradle() == null) {
      project.setGradle(new NoticeCollectorConfig.GradleConfigurationConfig());
    }
    if (config.getOutput() == null) {
      config.setOutput(new NoticeCollectorConfig.OutputConfig());
    }
    if (config.getRepositories() == null) {
      config.setRepositories(new java.util.ArrayList<>());
    }
    if (config.getGithub() == null) {
      config.setGithub(new NoticeCollectorConfig.GithubConfig());
    }
    if (config.getApacheArchive() == null) {
      config.setApacheArchive(new NoticeCollectorConfig.ApacheArchiveConfig());
    }
    if (config.getOverrides() == null) {
      config.setOverrides(new java.util.ArrayList<>());
    }
    if (config.getSourceRepositories() == null) {
      config.setSourceRepositories(new java.util.ArrayList<>());
    }
    if (config.getTargetLicenses() == null) {
      config.setTargetLicenses(new java.util.ArrayList<>(java.util.List.of("Apache-2.0")));
    }
    if (config.getExternalDefinitions() == null) {
      config.setExternalDefinitions(new NoticeCollectorConfig.ExternalDefinitionsConfig());
    }
  }

  /** 設定値のバリデーション。パストラバーサルを含むパス・URL を拒否する。 */
  private void validateConfig(NoticeCollectorConfig config) throws ConfigLoadException {
    validatePathField(config.getProject().getPath(), "project.path");
    validateOptionalPath(config.getProject().getLocalRepository(), "project.localRepository");
    validateOptionalPath(config.getProject().getGradleCache(), "project.gradleCache");
    validatePathField(config.getOutput().getDirectory(), "output.directory");
    validateOptionalPath(
        config.getExternalDefinitions().getLicenseMappings(),
        "externalDefinitions.licenseMappings");
    validateOptionalPath(
        config.getExternalDefinitions().getNoticePatterns(),
        "externalDefinitions.noticePatterns");

    for (NoticeCollectorConfig.RepositoryConfig repo : config.getRepositories()) {
      validateUrlField(repo.getUrl(), "repositories[].url");
    }
    for (NoticeCollectorConfig.OverrideConfig override : config.getOverrides()) {
      validateOptionalPath(override.getNoticePath(), "overrides[].noticePath");
      validateOptionalUrl(override.getNoticeUrl(), "overrides[].noticeUrl");
    }
    for (NoticeCollectorConfig.SourceRepoConfig srcRepo : config.getSourceRepositories()) {
      validateUrlField(srcRepo.getRepoUrl(), "sourceRepositories[].repoUrl");
    }
  }

  private void validateOptionalPath(String path, String fieldName) throws ConfigLoadException {
    if (path != null) {
      validatePathField(path, fieldName);
    }
  }

  private void validateOptionalUrl(String url, String fieldName) throws ConfigLoadException {
    if (url != null) {
      validateUrlField(url, fieldName);
    }
  }

  private void validatePathField(String path, String fieldName) throws ConfigLoadException {
    if (path == null) {
      return;
    }
    validatePath(path);
  }

  private void validateUrlField(String url, String fieldName) throws ConfigLoadException {
    if (url == null) {
      return;
    }
    validatePath(url);
  }

  /**
   * パストラバーサルシーケンスを検出して拒否する。
   *
   * @param value 検証対象のパスまたは URL
   * @throws ConfigLoadException パストラバーサルが検出された場合
   */
  public static void validatePath(String value) throws ConfigLoadException {
    if (value == null) {
      return;
    }
    // 正規化前のパスに ../ や ..\\ が含まれていないか検証
    if (value.contains("../") || value.contains("..\\")) {
      throw new ConfigLoadException("パストラバーサルが検出されました: " + value);
    }
    // パスコンポーネントが ".." そのものであるケース（末尾が .. で終わる場合）
    if (value.equals("..") || value.endsWith("/..") || value.endsWith("\\..")) {
      throw new ConfigLoadException("パストラバーサルが検出されました: " + value);
    }
  }

  /** 設定ファイル読込時の例外。 */
  public static class ConfigLoadException extends Exception {
    public ConfigLoadException(String message) {
      super(message);
    }

    public ConfigLoadException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}

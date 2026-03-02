package com.github.noticecollector.app;

import com.github.noticecollector.cache.ArchiveCache;
import com.github.noticecollector.config.ConfigLoader;
import com.github.noticecollector.config.LicenseMappingLoader;
import com.github.noticecollector.config.NoticeCollectorConfig;
import com.github.noticecollector.dependency.Dependency;
import com.github.noticecollector.dependency.DependencyResolutionException;
import com.github.noticecollector.dependency.DependencyResolver;
import com.github.noticecollector.dependency.FileDependencyResolver;
import com.github.noticecollector.dependency.GradleDependencyResolver;
import com.github.noticecollector.dependency.MavenDependencyResolver;
import com.github.noticecollector.http.HttpClientWrapper;
import com.github.noticecollector.license.LicenseIdentifier;
import com.github.noticecollector.license.LicenseMapping;
import com.github.noticecollector.license.LicensedDependency;
import com.github.noticecollector.model.CollectionResult;
import com.github.noticecollector.model.CollectionStatus;
import com.github.noticecollector.notice.NoticeCollector;
import com.github.noticecollector.notice.NoticePatternLoader;
import com.github.noticecollector.notice.NoticeSource;
import com.github.noticecollector.notice.source.ApacheArchiveSource;
import com.github.noticecollector.notice.source.CustomRepoSource;
import com.github.noticecollector.notice.source.GitHubSource;
import com.github.noticecollector.notice.source.LocalCacheSource;
import com.github.noticecollector.notice.source.MavenCentralSource;
import com.github.noticecollector.notice.source.PrivateRepoSource;
import com.github.noticecollector.notice.source.UserOverrideSource;
import com.github.noticecollector.output.OutputGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * OSS NOTICE ファイル自動収集ツールの CLI エントリポイント。
 *
 * <p>picocli による CLI 引数パースを行い、設定読込 → ビルドツール自動検出 → 依存関係解析 → ライセンス特定 → Apache-2.0
 * フィルタリング → NOTICE 収集 → 結果出力 の順で処理を実行する。
 */
@Command(
    name = "oss-notice-collector",
    mixinStandardHelpOptions = true,
    version = "1.0.0",
    description = "OSS 依存関係の NOTICE ファイルを自動収集するツール")
public class NoticeCollectorApp implements Callable<Integer> {

  private static final Logger LOG = LoggerFactory.getLogger(NoticeCollectorApp.class);

  @Option(
      names = {"--config", "-c"},
      description = "YAML 設定ファイルのパス")
  private Path configPath;

  @Option(
      names = {"--build-tool", "-b"},
      description = "ビルドツールを指定 (maven, gradle, auto)")
  private String buildTool;

  @Option(
      names = {"--deps-file", "-d"},
      description = "依存関係リストファイルのパス (groupId:artifactId:version 形式)")
  private Path depsFile;

  public static void main(String[] args) {
    int exitCode = new CommandLine(new NoticeCollectorApp()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public Integer call() {
    try {
      // 1. 設定読込
      NoticeCollectorConfig config = loadConfig();
      applyCliOverrides(config);

      Path projectPath = Path.of(config.getProject().getPath()).toAbsolutePath();
      LOG.info("プロジェクトパス: {}", projectPath);

      // 2. 依存関係解析
      List<Dependency> dependencies = resolveDependencies(config, projectPath);
      if (dependencies.isEmpty()) {
        System.out.println("依存関係が見つかりませんでした。");
        return 0;
      }
      System.out.printf("依存関係を %d 件解析しました。%n", dependencies.size());

      // 3. ライセンス特定（ArchiveCache を使用）
      ArchiveCache archiveCache = new ArchiveCache();
      LicenseMapping licenseMapping = loadLicenseMapping(config);
      LicenseIdentifier licenseIdentifier =
          createLicenseIdentifier(config, licenseMapping, archiveCache);
      List<LicensedDependency> licensedDeps = licenseIdentifier.identifyLicenses(dependencies);

      // 4. targetLicenses フィルタリング
      List<String> targetLicenses = config.getTargetLicenses();
      List<LicensedDependency> unknownDeps =
          licensedDeps.stream().filter(LicensedDependency::isUnknown).toList();

      List<LicensedDependency> targetDeps;
      List<LicensedDependency> otherLicenseDeps;

      if (targetLicenses == null || targetLicenses.isEmpty()) {
        // targetLicenses が未設定 → UNKNOWN_LICENSE 以外の全てを対象
        targetDeps = licensedDeps.stream()
            .filter(dep -> !dep.isUnknown())
            .toList();
        otherLicenseDeps = List.of();
      } else {
        // targetLicenses が設定済み → 指定されたライセンスのみ対象
        targetDeps = licensedDeps.stream()
            .filter(dep -> targetLicenses.contains(dep.spdxId()))
            .toList();
        otherLicenseDeps = licensedDeps.stream()
            .filter(dep -> !targetLicenses.contains(dep.spdxId()) && !dep.isUnknown())
            .toList();
      }

      String targetLabel = (targetLicenses == null || targetLicenses.isEmpty())
          ? "全ライセンス" : String.join(", ", targetLicenses);
      System.out.printf(
          "ライセンス特定完了: 対象(%s) = %d 件, UNKNOWN = %d 件, 対象外 = %d 件%n",
          targetLabel,
          targetDeps.size(),
          unknownDeps.size(),
          otherLicenseDeps.size());

      // 5. NOTICE/LICENSE 収集（対象ライセンス + UNKNOWN_LICENSE を対象）
      List<LicensedDependency> collectTargets = new ArrayList<>(targetDeps);
      collectTargets.addAll(unknownDeps);

      List<CollectionResult> results;
      try (HttpClientWrapper httpClient = new HttpClientWrapper(config)) {
        NoticeCollector noticeCollector = createNoticeCollector(config, httpClient, archiveCache);
        results = noticeCollector.collectNotices(collectTargets);
      }

      // 6. その他のライセンスに対する CollectionResult を追加
      List<CollectionResult> otherLicenseResults = otherLicenseDeps.stream()
          .map(dep -> new CollectionResult(
              dep.dependency(),
              dep.spdxId(),
              CollectionStatus.NOT_TARGET_LICENSE,
              null,
              null,
              null,
              null,
              null,
              null,
              "targetLicenses に含まれないライセンス (" + dep.spdxId() + ") のため収集対象外"))
          .toList();
      
      // 全ての結果を統合
      List<CollectionResult> allResults = new ArrayList<>(results);
      allResults.addAll(otherLicenseResults);

      // 7. アーカイブキャッシュをクリア
      archiveCache.clear();
      LOG.debug("アーカイブキャッシュをクリアしました");

      // 8. 結果出力
      OutputGenerator outputGenerator = new OutputGenerator(config);
      outputGenerator.generateOutput(allResults, licensedDeps, config);

      // 9. サマリ表示
      printSummary(licensedDeps, allResults);

      // FAILED がある場合は終了コード 1
      boolean hasFailed =
          allResults.stream().anyMatch(r -> r.status() == CollectionStatus.FAILED);
      return hasFailed ? 1 : 0;

    } catch (Exception e) {
      LOG.error("処理中にエラーが発生しました", e);
      System.err.println("エラー: " + e.getMessage());
      return 2;
    }
  }

  /** 設定ファイルを読み込む。--config 未指定時はデフォルト設定を使用する。 */
  private NoticeCollectorConfig loadConfig() throws ConfigLoader.ConfigLoadException {
    ConfigLoader configLoader = new ConfigLoader();
    if (configPath != null) {
      LOG.info("設定ファイルを読み込みます: {}", configPath);
      return configLoader.load(configPath);
    }
    LOG.info("設定ファイル未指定のため、デフォルト設定を使用します");
    return configLoader.loadDefaults();
  }

  /** CLI オプションで設定を上書きする。 */
  private void applyCliOverrides(NoticeCollectorConfig config) {
    if (buildTool != null) {
      config.getProject().setBuildTool(buildTool);
    }
  }

  /** 依存関係を解析する。 */
  private List<Dependency> resolveDependencies(NoticeCollectorConfig config, Path projectPath)
      throws DependencyResolutionException {
    DependencyResolver resolver = selectDependencyResolver(config, projectPath);
    return resolver.resolve(projectPath, config);
  }

  /**
   * ビルドツールを自動検出または CLI 指定に基づいて DependencyResolver を選択する。
   *
   * <p>選択優先順:
   * <ol>
   *   <li>--deps-file が指定されている場合: FileDependencyResolver</li>
   *   <li>--build-tool または設定ファイルで指定されている場合: 指定されたビルドツール</li>
   *   <li>auto（デフォルト）: pom.xml / build.gradle の存在で自動検出</li>
   * </ol>
   */
  private DependencyResolver selectDependencyResolver(
      NoticeCollectorConfig config, Path projectPath) throws DependencyResolutionException {
    // --deps-file が指定されている場合はファイルから読み込む
    if (depsFile != null) {
      LOG.info("依存関係リストファイルを使用します: {}", depsFile);
      return new FileDependencyResolver(depsFile);
    }

    String tool = config.getProject().getBuildTool();

    if ("maven".equalsIgnoreCase(tool)) {
      return new MavenDependencyResolver();
    }
    if ("gradle".equalsIgnoreCase(tool)) {
      return new GradleDependencyResolver();
    }

    // auto: ビルドファイルの存在で自動検出
    if (Files.exists(projectPath.resolve("pom.xml"))) {
      LOG.info("pom.xml を検出しました。Maven プロジェクトとして処理します。");
      return new MavenDependencyResolver();
    }
    if (Files.exists(projectPath.resolve("build.gradle"))
        || Files.exists(projectPath.resolve("build.gradle.kts"))) {
      LOG.info("build.gradle を検出しました。Gradle プロジェクトとして処理します。");
      return new GradleDependencyResolver();
    }

    throw new DependencyResolutionException(
        "ビルドツールを検出できませんでした。"
            + "プロジェクトディレクトリに pom.xml または build.gradle が存在しません。\n"
            + "以下のオプションで手動指定してください:\n"
            + "  --build-tool maven|gradle  ビルドツールを指定\n"
            + "  --deps-file <ファイルパス>  依存関係リストファイルを指定");
  }

  /** ライセンスマッピングを読み込む。 */
  private LicenseMapping loadLicenseMapping(NoticeCollectorConfig config)
      throws LicenseMappingLoader.LicenseMappingLoadException {
    LicenseMappingLoader loader = new LicenseMappingLoader();
    String mappingsFile = config.getExternalDefinitions().getLicenseMappings();
    if (mappingsFile != null) {
      Path mappingsPath = Path.of(mappingsFile);
      if (Files.exists(mappingsPath)) {
        LOG.info("外部ライセンスマッピングを読み込みます: {}", mappingsPath);
        return loader.load(mappingsPath);
      }
    }
    LOG.info("デフォルトのライセンスマッピングを使用します");
    return loader.loadDefaults();
  }

  /** LicenseIdentifier を構築する。 */
  private LicenseIdentifier createLicenseIdentifier(
      NoticeCollectorConfig config, LicenseMapping licenseMapping, ArchiveCache archiveCache) {
    HttpClientWrapper httpClient = new HttpClientWrapper(config);

    // pom.xml 取得: Maven Central から HTTP で取得
    LicenseIdentifier.PomFetcher pomFetcher =
        dep -> {
          try {
            return httpClient.getString(dep.toPomUrl());
          } catch (Exception e) {
            LOG.debug("pom.xml 取得失敗: {} - {}", dep.toGav(), e.getMessage());
            return null;
          }
        };

    // JAR ロケータ: ローカルリポジトリから検索
    Path localRepo;
    String localRepoPath = config.getProject().getLocalRepository();
    if (localRepoPath != null) {
      localRepo = Path.of(localRepoPath);
    } else {
      localRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
    }
    LicenseIdentifier.JarLocator jarLocator = dep -> dep.toLocalJarPath(localRepo);

    // GitHub ライセンス取得は null（NoticeCollector の GitHubSource で対応）
    return new LicenseIdentifier(licenseMapping, pomFetcher, jarLocator, null, config,
        httpClient, new com.github.noticecollector.notice.util.ArchiveNoticeExtractor(),
        archiveCache);
  }

  /** NOTICE 検索パターンを読み込む。 */
  private List<String> loadNoticePatterns(NoticeCollectorConfig config)
      throws NoticePatternLoader.NoticePatternLoadException {
    NoticePatternLoader loader = new NoticePatternLoader();
    String patternsFile = config.getExternalDefinitions().getNoticePatterns();
    if (patternsFile != null) {
      Path patternsPath = Path.of(patternsFile);
      if (Files.exists(patternsPath)) {
        LOG.info("外部 NOTICE パターンを読み込みます: {}", patternsPath);
        return loader.load(patternsPath);
      }
    }
    return loader.loadDefaults();
  }

  /** デフォルトの LICENSE 検索パターン。 */
  private static final List<String> DEFAULT_LICENSE_PATTERNS = List.of(
      "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/LICENSE.md",
      "LICENSE", "LICENSE.txt", "LICENSE.md", "COPYING", "COPYING.txt");

  /** NoticeCollector を構築する（7段階の NoticeSource を登録）。 */
  private NoticeCollector createNoticeCollector(
      NoticeCollectorConfig config, HttpClientWrapper httpClient, ArchiveCache archiveCache)
      throws NoticePatternLoader.NoticePatternLoadException {
    List<String> noticePatterns = loadNoticePatterns(config);
    List<String> licensePatterns = DEFAULT_LICENSE_PATTERNS;

    List<NoticeSource> sources = new ArrayList<>();
    sources.add(new UserOverrideSource(config, httpClient, archiveCache));
    sources.add(new LocalCacheSource(config));
    sources.add(new MavenCentralSource(httpClient));
    sources.add(new PrivateRepoSource(config, httpClient));
    sources.add(new ApacheArchiveSource(config, httpClient));
    sources.add(new GitHubSource(config, httpClient));
    sources.add(new CustomRepoSource(config));

    return new NoticeCollector(sources, noticePatterns, licensePatterns);
  }

  /** 収集結果のサマリをコンソールに表示する。 */
  private void printSummary(
      List<LicensedDependency> allDeps, List<CollectionResult> results) {
    long totalDeps = allDeps.size();
    long successCount =
        results.stream().filter(r -> r.status() == CollectionStatus.SUCCESS).count();
    long notRequiredCount =
        results.stream().filter(r -> r.status() == CollectionStatus.NOT_REQUIRED).count();
    long failedCount =
        results.stream().filter(r -> r.status() == CollectionStatus.FAILED).count();
    long unknownCount =
        results.stream().filter(r -> r.status() == CollectionStatus.UNKNOWN_LICENSE).count();
    long notTargetCount =
        results.stream().filter(r -> r.status() == CollectionStatus.NOT_TARGET_LICENSE).count();

    System.out.println();
    System.out.println("========================================");
    System.out.println("  NOTICE/LICENSE 収集結果サマリ");
    System.out.println("========================================");
    System.out.printf("  総依存関係数:              %d%n", totalDeps);
    System.out.printf("  収集成功 (SUCCESS):        %d%n", successCount);
    System.out.printf("  不要 (NOT_REQUIRED):       %d%n", notRequiredCount);
    System.out.printf("  失敗 (FAILED):             %d%n", failedCount);
    System.out.printf("  不明 (UNKNOWN):            %d%n", unknownCount);
    System.out.printf("  対象外 (NOT_TARGET_LICENSE):%d%n", notTargetCount);
    System.out.println("========================================");

    if (failedCount > 0) {
      System.out.println();
      System.out.println("NOTICE 収集に失敗した依存関係:");
      results.stream()
          .filter(r -> r.status() == CollectionStatus.FAILED)
          .forEach(
              r ->
                  System.out.printf(
                      "  - %s: %s%n", r.dependency().toGav(), r.failureReason()));
    }

    if (unknownCount > 0) {
      System.out.println();
      System.out.println("ライセンス不明の依存関係:");
      results.stream()
          .filter(r -> r.status() == CollectionStatus.UNKNOWN_LICENSE)
          .forEach(r -> System.out.printf("  - %s%n", r.dependency().toGav()));
    }
  }
}

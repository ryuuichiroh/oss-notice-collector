package com.github.noticecollector.license;

import com.github.noticecollector.cache.ArchiveCache;
import com.github.noticecollector.config.NoticeCollectorConfig;
import com.github.noticecollector.dependency.Dependency;
import com.github.noticecollector.http.HttpClientWrapper;
import com.github.noticecollector.http.HttpRequestException;
import com.github.noticecollector.notice.util.ArchiveNoticeExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.apache.maven.model.License;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 各依存関係のライセンスを特定し、SPDX 識別子に正規化する。
 *
 * <p>以下の3段階の優先順でライセンス情報を取得する:
 *
 * <ol>
 *   <li>Maven Central の pom.xml の {@code <licenses>} セクション
 *   <li>JAR 内の {@code META-INF/LICENSE} ファイル
 *   <li>GitHub API によるリポジトリライセンス情報
 * </ol>
 */
public class LicenseIdentifier {

  private static final Logger logger = LoggerFactory.getLogger(LicenseIdentifier.class);

  private static final String LICENSE_SOURCE_POM = "POM";
  private static final String LICENSE_SOURCE_JAR = "JAR_META_INF";
  private static final String LICENSE_SOURCE_SOURCES_JAR = "MAVEN_CENTRAL_SOURCES_JAR";
  private static final String LICENSE_SOURCE_GITHUB = "GITHUB_API";
  private static final String LICENSE_SOURCE_ARCHIVE = "ARCHIVE";

  private static final String MAVEN_CENTRAL_BASE = "https://repo1.maven.org/maven2/";

  /** parent POM を辿る最大深度（無限ループ防止）。 */
  private static final int MAX_PARENT_DEPTH = 5;

  /** JAR 内で検索する LICENSE ファイルパス（優先順）。 */
  private static final List<String> JAR_LICENSE_PATHS =
      List.of("META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/LICENSE.md");

  /** アーカイブ内で検索する LICENSE ファイルパターン（優先順）。 */
  private static final List<String> ARCHIVE_LICENSE_PATTERNS =
      List.of(
          "LICENSE",
          "LICENSE.txt",
          "LICENSE.md",
          "META-INF/LICENSE",
          "META-INF/LICENSE.txt",
          "META-INF/LICENSE.md",
          "COPYING",
          "COPYING.txt");

  private final LicenseMapping licenseMapping;
  private final PomFetcher pomFetcher;
  private final JarLocator jarLocator;
  private final GitHubLicenseFetcher githubFetcher;
  private final List<NoticeCollectorConfig.OverrideConfig> overrides;
  private final HttpClientWrapper httpClient;
  private final ArchiveNoticeExtractor archiveExtractor;
  private final ArchiveCache archiveCache;

  /**
   * LicenseIdentifier を構築する。
   *
   * @param licenseMapping ライセンス名→SPDX マッピング
   * @param pomFetcher pom.xml 取得関数
   * @param jarLocator ローカル JAR パス解決関数
   * @param githubFetcher GitHub API ライセンス取得関数
   * @param config 設定オブジェクト（overrides 取得用）
   */
  public LicenseIdentifier(
      LicenseMapping licenseMapping,
      PomFetcher pomFetcher,
      JarLocator jarLocator,
      GitHubLicenseFetcher githubFetcher,
      NoticeCollectorConfig config) {
    this(licenseMapping, pomFetcher, jarLocator, githubFetcher, config, null, null);
  }

  /**
   * LicenseIdentifier を構築する（アーカイブからのライセンス特定対応）。
   *
   * @param licenseMapping ライセンス名→SPDX マッピング
   * @param pomFetcher pom.xml 取得関数
   * @param jarLocator ローカル JAR パス解決関数
   * @param githubFetcher GitHub API ライセンス取得関数
   * @param config 設定オブジェクト（overrides 取得用）
   * @param httpClient HTTP クライアント（アーカイブダウンロード用、nullable）
   * @param archiveExtractor アーカイブ抽出ユーティリティ（nullable）
   */
  public LicenseIdentifier(
      LicenseMapping licenseMapping,
      PomFetcher pomFetcher,
      JarLocator jarLocator,
      GitHubLicenseFetcher githubFetcher,
      NoticeCollectorConfig config,
      HttpClientWrapper httpClient,
      ArchiveNoticeExtractor archiveExtractor) {
    this(licenseMapping, pomFetcher, jarLocator, githubFetcher, config, httpClient,
        archiveExtractor, null);
  }

  /**
   * LicenseIdentifier を構築する（ArchiveCache 対応）。
   *
   * @param licenseMapping ライセンス名→SPDX マッピング
   * @param pomFetcher pom.xml 取得関数
   * @param jarLocator ローカル JAR パス解決関数
   * @param githubFetcher GitHub API ライセンス取得関数
   * @param config 設定オブジェクト（overrides 取得用）
   * @param httpClient HTTP クライアント（アーカイブダウンロード用、nullable）
   * @param archiveExtractor アーカイブ抽出ユーティリティ（nullable）
   * @param archiveCache アーカイブキャッシュ（nullable）
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "ArchiveCache is intentionally shared across components")
  public LicenseIdentifier(
      LicenseMapping licenseMapping,
      PomFetcher pomFetcher,
      JarLocator jarLocator,
      GitHubLicenseFetcher githubFetcher,
      NoticeCollectorConfig config,
      HttpClientWrapper httpClient,
      ArchiveNoticeExtractor archiveExtractor,
      ArchiveCache archiveCache) {
    this.licenseMapping = licenseMapping;
    this.pomFetcher = pomFetcher;
    this.jarLocator = jarLocator;
    this.githubFetcher = githubFetcher;
    this.overrides = config != null ? config.getOverrides() : List.of();
    this.httpClient = httpClient;
    this.archiveExtractor = archiveExtractor;
    this.archiveCache = archiveCache;
  }

  /**
   * 依存関係リストのライセンスを特定する。
   *
   * @param dependencies 依存関係リスト
   * @return ライセンス付き依存関係リスト
   */
  public List<LicensedDependency> identifyLicenses(List<Dependency> dependencies) {
    List<LicensedDependency> results = new ArrayList<>();
    for (Dependency dep : dependencies) {
      results.add(identifyLicense(dep));
    }
    return results;
  }

  /**
   * 単一の依存関係のライセンスを特定する。
   *
   * @param dependency 対象の依存関係
   * @return ライセンス付き依存関係
   */
  LicensedDependency identifyLicense(Dependency dependency) {
    // 優先順位 0: ユーザ指定オーバーライド（spdxId）
    LicensedDependency fromOverride = identifyFromOverride(dependency);
    if (fromOverride != null) {
      return fromOverride;
    }

    // 優先順位 1: pom.xml の <licenses> セクション
    LicensedDependency fromPom = identifyFromPom(dependency);
    if (fromPom != null && !fromPom.isUnknown()) {
      return fromPom;
    }

    // pom.xml で UNKNOWN_LICENSE だった場合も、名前と URL は保持しておく
    String pomRawName = fromPom != null ? fromPom.rawLicenseName() : null;
    String pomUrl = fromPom != null ? fromPom.licenseUrl() : null;

    // 優先順位 2: JAR 内の META-INF/LICENSE
    LicensedDependency fromJar = identifyFromJar(dependency);
    if (fromJar != null && !fromJar.isUnknown()) {
      return fromJar;
    }

    // 優先順位 3: Maven Central の sources.jar の LICENSE ファイル
    LicensedDependency fromSourcesJar = identifyFromSourcesJar(dependency);
    if (fromSourcesJar != null && !fromSourcesJar.isUnknown()) {
      return fromSourcesJar;
    }

    // 優先順位 4: GitHub API
    LicensedDependency fromGithub = identifyFromGithub(dependency);
    if (fromGithub != null && !fromGithub.isUnknown()) {
      return fromGithub;
    }

    // 優先順位 5: noticeUrl で指定されたアーカイブ内の LICENSE ファイル
    LicensedDependency fromArchive = identifyFromArchive(dependency);
    if (fromArchive != null && !fromArchive.isUnknown()) {
      return fromArchive;
    }

    // 全段階で特定できなかった場合は UNKNOWN_LICENSE
    logger.warn("ライセンスを特定できませんでした: {}", dependency.toGav());
    return new LicensedDependency(
        dependency, LicenseMapping.UNKNOWN_LICENSE, pomRawName, pomUrl, null);
  }

  /**
   * ユーザ指定オーバーライドからライセンスを特定する。
   *
   * @param dependency 対象の依存関係
   * @return ライセンス付き依存関係。オーバーライド未設定または spdxId が未指定の場合は null
   */
  private LicensedDependency identifyFromOverride(Dependency dependency) {
    if (overrides == null || overrides.isEmpty()) {
      return null;
    }

    String groupId = dependency.groupId();
    String artifactId = dependency.artifactId();
    String version = dependency.version();

    for (NoticeCollectorConfig.OverrideConfig override : overrides) {
      if (groupId.equals(override.getGroupId())
          && artifactId.equals(override.getArtifactId())
          && (override.getVersion() == null || version.equals(override.getVersion()))
          && override.getSpdxId() != null
          && !override.getSpdxId().isBlank()) {
        logger.info("ユーザ指定オーバーライドからライセンスを取得: {} -> {}",
            dependency.toGav(), override.getSpdxId());
        return new LicensedDependency(
            dependency,
            override.getSpdxId(),
            null,
            null,
            "USER_OVERRIDE");
      }
    }

    return null;
  }

  /**
   * pom.xml の {@code <licenses>} セクションからライセンスを特定する。
   *
   * @return ライセンス付き依存関係。取得失敗時は {@code null}
   */
  private LicensedDependency identifyFromPom(Dependency dependency) {
    return identifyFromPomWithParentChain(dependency, 0);
  }

  /**
   * pom.xml からライセンスを特定する（parent POM チェーン対応）。
   *
   * <p>対象 POM に {@code <licenses>} セクションがない場合、{@code <parent>} を辿って
   * 親 POM のライセンス情報を継承する。無限ループ防止のため、探索深度に上限を設ける。
   *
   * @param dependency 依存関係
   * @param depth 現在の探索深度（0 から開始）
   * @return ライセンス付き依存関係。取得失敗時は {@code null}
   */
  private LicensedDependency identifyFromPomWithParentChain(Dependency dependency, int depth) {
    if (depth > MAX_PARENT_DEPTH) {
      logger.debug("parent POM の探索深度が上限に達しました: {}", dependency.toGav());
      return null;
    }

    String pomContent;
    try {
      pomContent = pomFetcher.fetchPom(dependency);
    } catch (Exception e) {
      logger.debug("pom.xml の取得に失敗しました: {} - {}", dependency.toGav(), e.getMessage());
      return null;
    }

    if (pomContent == null || pomContent.isBlank()) {
      return null;
    }

    try {
      Model model = parsePom(pomContent);

      // 1. 自身の <licenses> を確認
      List<License> licenses = model.getLicenses();
      if (licenses != null && !licenses.isEmpty()) {
        // 最初のライセンスエントリを使用
        License license = licenses.get(0);
        String rawName = license.getName();
        String url = license.getUrl();
        String spdxId = licenseMapping.resolve(rawName, url);

        return new LicensedDependency(dependency, spdxId, rawName, url, LICENSE_SOURCE_POM);
      }

      // 2. <parent> を辿る
      Parent parent = model.getParent();
      if (parent != null
          && parent.getGroupId() != null
          && parent.getArtifactId() != null
          && parent.getVersion() != null) {
        logger.debug(
            "parent POM を探索します: {} -> {}:{}:{}",
            dependency.toGav(),
            parent.getGroupId(),
            parent.getArtifactId(),
            parent.getVersion());

        Dependency parentDep =
            new Dependency(
                parent.getGroupId(), parent.getArtifactId(), parent.getVersion(), null, "pom");
        LicensedDependency fromParent = identifyFromPomWithParentChain(parentDep, depth + 1);

        if (fromParent != null && !fromParent.isUnknown()) {
          // parent のライセンス情報を元の dependency に適用
          logger.debug(
              "parent POM からライセンスを継承しました: {} <- {}",
              dependency.toGav(),
              fromParent.spdxId());
          return new LicensedDependency(
              dependency,
              fromParent.spdxId(),
              fromParent.rawLicenseName(),
              fromParent.licenseUrl(),
              LICENSE_SOURCE_POM);
        }
      }

      logger.debug("pom.xml に <licenses> セクションがありません: {}", dependency.toGav());
      return null;
    } catch (Exception e) {
      logger.debug("pom.xml のパースに失敗しました: {} - {}", dependency.toGav(), e.getMessage());
      return null;
    }
  }

  /**
   * JAR 内の META-INF/LICENSE ファイルからライセンスを推定する。
   *
   * @return ライセンス付き依存関係。取得失敗時は {@code null}
   */
  private LicensedDependency identifyFromJar(Dependency dependency) {
    Path jarPath;
    try {
      jarPath = jarLocator.locateJar(dependency);
    } catch (Exception e) {
      logger.debug("JAR パスの解決に失敗しました: {} - {}", dependency.toGav(), e.getMessage());
      return null;
    }

    if (jarPath == null || !Files.exists(jarPath)) {
      return null;
    }

    try (JarFile jarFile = new JarFile(jarPath.toFile())) {
      for (String licensePath : JAR_LICENSE_PATHS) {
        String content = extractJarEntry(jarFile, licensePath);
        if (content != null) {
          String spdxId = inferLicenseFromContent(content);
          return new LicensedDependency(
              dependency, spdxId, null, null, LICENSE_SOURCE_JAR);
        }
      }
    } catch (IOException e) {
      logger.debug("JAR ファイルの読込に失敗しました: {} - {}", jarPath, e.getMessage());
    }

    return null;
  }

  /**
   * Maven Central の sources.jar の LICENSE ファイルからライセンスを特定する。
   *
   * <p>ローカル JAR に LICENSE ファイルが含まれていない場合のフォールバックとして、
   * Maven Central から sources.jar をダウンロードして LICENSE ファイルを確認する。
   *
   * @param dependency 対象の依存関係
   * @return ライセンス付き依存関係。特定できない場合は {@code null}
   */
  private LicensedDependency identifyFromSourcesJar(Dependency dependency) {
    if (httpClient == null || archiveExtractor == null) {
      return null;
    }

    String sourcesJarUrl = buildSourcesJarUrl(dependency);
    String cacheKey = dependency.toGav() + ":sources";

    Path tempFile = null;
    boolean shouldDeleteTempFile = true;

    try {
      // キャッシュを確認
      if (archiveCache != null && archiveCache.contains(cacheKey)) {
        tempFile = archiveCache.get(cacheKey);
        shouldDeleteTempFile = false;
        logger.debug("sources.jar のキャッシュを使用: {} ({})", tempFile, dependency.toGav());
      } else {
        // ダウンロード
        tempFile = Files.createTempFile("license-sources-", ".jar");
        httpClient.downloadToFile(sourcesJarUrl, tempFile);
        logger.debug("sources.jar をダウンロード: {} ({})", sourcesJarUrl, dependency.toGav());

        // キャッシュに登録
        if (archiveCache != null) {
          archiveCache.put(cacheKey, tempFile);
          shouldDeleteTempFile = false;
        }
      }

      // LICENSE ファイルを検索
      Optional<String> licenseContent =
          archiveExtractor.extract(tempFile, ARCHIVE_LICENSE_PATTERNS);
      if (licenseContent.isEmpty()) {
        logger.debug("sources.jar に LICENSE ファイルが見つかりません: {} ({})",
            sourcesJarUrl, dependency.toGav());
        return null;
      }

      String spdxId = inferLicenseFromContent(licenseContent.get());
      if (LicenseMapping.UNKNOWN_LICENSE.equals(spdxId)) {
        logger.debug("sources.jar の LICENSE ファイルからライセンスを判定できません: {} ({})",
            sourcesJarUrl, dependency.toGav());
        return null;
      }

      logger.info("Maven Central の sources.jar からライセンスを特定しました: {} -> {} ({})",
          dependency.toGav(), spdxId, sourcesJarUrl);
      return new LicensedDependency(
          dependency, spdxId, null, sourcesJarUrl, LICENSE_SOURCE_SOURCES_JAR);

    } catch (HttpRequestException e) {
      logger.debug("sources.jar のダウンロードに失敗: {} ({}) - {}",
          sourcesJarUrl, dependency.toGav(), e.getMessage());
      return null;
    } catch (IOException e) {
      logger.debug("sources.jar の処理に失敗: {} ({}) - {}",
          sourcesJarUrl, dependency.toGav(), e.getMessage());
      return null;
    } finally {
      if (shouldDeleteTempFile && tempFile != null) {
        try {
          Files.deleteIfExists(tempFile);
        } catch (IOException e) {
          logger.debug("一時ファイルの削除に失敗: {}", tempFile, e);
        }
      }
    }
  }

  /**
   * Maven Central の sources.jar URL を構築する。
   *
   * <p>形式: {@code https://repo1.maven.org/maven2/{groupPath}/{artifactId}/{version}/{artifactId}-{version}-sources.jar}
   */
  private String buildSourcesJarUrl(Dependency dependency) {
    String groupId = dependency.groupId();
    String artifactId = dependency.artifactId();
    String version = dependency.version();
    String groupPath = groupId.replace('.', '/');
    return String.format("%s%s/%s/%s/%s-%s-sources.jar",
        MAVEN_CENTRAL_BASE, groupPath, artifactId, version, artifactId, version);
  }

  /**
   * GitHub API からリポジトリのライセンス情報を取得する。
   *
   * @return ライセンス付き依存関係。取得失敗時は {@code null}
   */
  private LicensedDependency identifyFromGithub(Dependency dependency) {
    if (githubFetcher == null) {
      return null;
    }

    try {
      GitHubLicenseInfo info = githubFetcher.fetchLicense(dependency);
      if (info == null) {
        return null;
      }

      String spdxId = info.spdxId();
      if (spdxId == null || spdxId.isBlank()) {
        spdxId = licenseMapping.resolve(info.licenseName(), null);
      }

      return new LicensedDependency(
          dependency, spdxId, info.licenseName(), info.htmlUrl(), LICENSE_SOURCE_GITHUB);
    } catch (Exception e) {
      logger.debug(
          "GitHub API からのライセンス取得に失敗しました: {} - {}",
          dependency.toGav(),
          e.getMessage());
      return null;
    }
  }

  /**
   * noticeUrl で指定されたアーカイブ内の LICENSE ファイルからライセンスを特定する。
   *
   * <p>通常の方法（pom.xml、JAR、GitHub API）でライセンスを特定できない場合の
   * フォールバックとして使用する。overrides で noticeUrl が指定されている場合のみ動作する。
   *
   * @param dependency 対象の依存関係
   * @return ライセンス付き依存関係。特定できない場合は {@code null}
   */
  private LicensedDependency identifyFromArchive(Dependency dependency) {
    if (httpClient == null || archiveExtractor == null
        || overrides == null || overrides.isEmpty()) {
      return null;
    }

    // 該当する OverrideConfig を検索
    NoticeCollectorConfig.OverrideConfig override = findOverrideWithNoticeUrl(dependency);
    if (override == null) {
      return null;
    }

    String noticeUrl = resolveVersionPlaceholders(override.getNoticeUrl(), dependency);
    String cacheKey = dependency.toGav() + ":noticeUrl";

    Path tempFile = null;
    boolean shouldDeleteTempFile = true;

    try {
      // キャッシュを確認
      if (archiveCache != null && archiveCache.contains(cacheKey)) {
        tempFile = archiveCache.get(cacheKey);
        shouldDeleteTempFile = false;
        logger.debug("noticeUrl アーカイブのキャッシュを使用: {} ({})", tempFile, dependency.toGav());
      } else {
        // ダウンロード
        String suffix = determineTempFileSuffix(noticeUrl);
        tempFile = Files.createTempFile("license-archive-", suffix);
        httpClient.downloadToFile(noticeUrl, tempFile);
        logger.debug("noticeUrl アーカイブをダウンロード: {} ({})", noticeUrl, dependency.toGav());

        // キャッシュに登録
        if (archiveCache != null) {
          archiveCache.put(cacheKey, tempFile);
          shouldDeleteTempFile = false;
        }
      }

      Optional<String> licenseContent =
          archiveExtractor.extract(tempFile, ARCHIVE_LICENSE_PATTERNS);
      if (licenseContent.isEmpty()) {
        logger.debug("アーカイブに LICENSE ファイルが見つかりません: {} ({})",
            noticeUrl, dependency.toGav());
        return null;
      }

      String spdxId = inferLicenseFromContent(licenseContent.get());
      if (LicenseMapping.UNKNOWN_LICENSE.equals(spdxId)) {
        logger.debug("アーカイブの LICENSE ファイルからライセンスを判定できません: {} ({})",
            noticeUrl, dependency.toGav());
        return null;
      }

      logger.info("アーカイブからライセンスを特定しました: {} -> {} ({})",
          dependency.toGav(), spdxId, noticeUrl);
      return new LicensedDependency(dependency, spdxId, null, noticeUrl, LICENSE_SOURCE_ARCHIVE);

    } catch (HttpRequestException e) {
      logger.debug("アーカイブのダウンロードに失敗: {} ({}) - {}",
          noticeUrl, dependency.toGav(), e.getMessage());
      return null;
    } catch (IOException e) {
      logger.debug("アーカイブの処理に失敗: {} ({}) - {}",
          noticeUrl, dependency.toGav(), e.getMessage());
      return null;
    } finally {
      if (shouldDeleteTempFile && tempFile != null) {
        try {
          Files.deleteIfExists(tempFile);
        } catch (IOException e) {
          logger.debug("一時ファイルの削除に失敗: {}", tempFile, e);
        }
      }
    }
  }

  /**
   * 依存関係に一致する noticeUrl 付きの OverrideConfig を検索する。
   * spdxId が指定されている場合は対象外（既にオーバーライドで処理済み）。
   */
  private NoticeCollectorConfig.OverrideConfig findOverrideWithNoticeUrl(Dependency dependency) {
    for (NoticeCollectorConfig.OverrideConfig override : overrides) {
      if (dependency.groupId().equals(override.getGroupId())
          && dependency.artifactId().equals(override.getArtifactId())
          && (override.getVersion() == null
              || dependency.version().equals(override.getVersion()))
          && override.getNoticeUrl() != null
          && !override.getNoticeUrl().isBlank()) {
        return override;
      }
    }
    return null;
  }

  /**
   * noticeUrl 内のバージョンプレースホルダーを解決する。
   */
  private String resolveVersionPlaceholders(String url, Dependency dependency) {
    String version = dependency.version();
    if (version == null || version.isBlank()) {
      return url;
    }
    String result = url;
    if (result.contains("${underscored_version}")) {
      result = result.replace("${underscored_version}",
          version.replace('.', '_').replace('-', '_'));
    }
    if (result.contains("${version_major}")) {
      result = result.replace("${version_major}", version.split("\\.")[0]);
    }
    if (result.contains("${version_minor}")) {
      String[] parts = version.split("\\.");
      result = result.replace("${version_minor}",
          parts.length >= 2 ? parts[0] + "." + parts[1] : version);
    }
    if (result.contains("${version}")) {
      result = result.replace("${version}", version);
    }
    return result;
  }

  /**
   * URL からアーカイブの一時ファイルサフィックスを決定する。
   */
  private String determineTempFileSuffix(String url) {
    String lower = url.toLowerCase();
    if (lower.endsWith(".tar.gz")) {
      return ".tar.gz";
    } else if (lower.endsWith(".tgz")) {
      return ".tgz";
    } else if (lower.endsWith(".zip")) {
      return ".zip";
    }
    return ".archive";
  }

  /**
   * pom.xml 文字列を maven-model でパースする。
   *
   * @param pomContent pom.xml の内容
   * @return パース結果の Model
   */
  static Model parsePom(String pomContent) throws IOException, XmlPullParserException {
    MavenXpp3Reader reader = new MavenXpp3Reader();
    return reader.read(new StringReader(pomContent));
  }

  /**
   * JAR ファイルから指定パスのエントリを文字列として抽出する。 case-insensitive でマッチングする。
   *
   * @param jarFile JAR ファイル
   * @param entryPath エントリパス
   * @return エントリの内容。見つからない場合は {@code null}
   */
  private String extractJarEntry(JarFile jarFile, String entryPath) throws IOException {
    // 完全一致を先に試行
    JarEntry entry = jarFile.getJarEntry(entryPath);
    if (entry != null && !entry.isDirectory()) {
      return readEntryContent(jarFile, entry);
    }

    // case-insensitive フォールバック
    String lowerPath = entryPath.toLowerCase();
    var entries = jarFile.entries();
    while (entries.hasMoreElements()) {
      JarEntry candidate = entries.nextElement();
      if (!candidate.isDirectory() && candidate.getName().toLowerCase().equals(lowerPath)) {
        return readEntryContent(jarFile, candidate);
      }
    }

    return null;
  }

  private String readEntryContent(JarFile jarFile, JarEntry entry) throws IOException {
    try (InputStream is = jarFile.getInputStream(entry)) {
      return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  /**
   * LICENSE ファイルの内容からライセンスを推定する。 Apache License 2.0 の特徴的なフレーズを検索する。
   *
   * @param content LICENSE ファイルの内容
   * @return 推定された SPDX 識別子
   */
  String inferLicenseFromContent(String content) {
    if (content == null || content.isBlank()) {
      return LicenseMapping.UNKNOWN_LICENSE;
    }

    String lower = content.toLowerCase();

    // Apache License 2.0 の特徴的なフレーズ
    if (lower.contains("apache license") && lower.contains("version 2.0")) {
      return "Apache-2.0";
    }
    if (lower.contains("apache license, version 2.0")) {
      return "Apache-2.0";
    }

    // MIT License
    if (lower.contains("mit license") || lower.contains("permission is hereby granted, free")) {
      return "MIT";
    }

    // Eclipse Public License 2.0
    if (lower.contains("eclipse public license")
        && (lower.contains("v 2.0") || lower.contains("v2.0")
            || lower.contains("version 2"))) {
      return "EPL-2.0";
    }
    // Eclipse Public License 1.0
    if (lower.contains("eclipse public license")
        && (lower.contains("v 1.0") || lower.contains("v1.0")
            || lower.contains("version 1"))) {
      return "EPL-1.0";
    }

    // LGPL 2.1
    if (lower.contains("gnu lesser general public license")
        && lower.contains("version 2.1")) {
      return "LGPL-2.1-only";
    }

    // MPL 2.0
    if (lower.contains("mozilla public license") && lower.contains("version 2.0")) {
      return "MPL-2.0";
    }

    // CDDL 1.1
    if (lower.contains("common development and distribution license")
        || (lower.contains("cddl") && lower.contains("1.1"))) {
      return "CDDL-1.1";
    }

    // BSD Licenses
    // BSD という文字列がなくても、特徴的なフレーズで判定
    if (lower.contains("redistribution and use in source and binary forms")) {
      if (lower.contains("neither the name")) {
        return "BSD-3-Clause";
      }
      return "BSD-2-Clause";
    }

    return LicenseMapping.UNKNOWN_LICENSE;
  }

  /** pom.xml を取得する関数インターフェース。 */
  @FunctionalInterface
  public interface PomFetcher {
    /**
     * 依存関係の pom.xml 内容を取得する。
     *
     * @param dependency 対象の依存関係
     * @return pom.xml の内容。取得できない場合は {@code null}
     * @throws IOException 取得失敗時
     */
    String fetchPom(Dependency dependency) throws IOException;
  }

  /** ローカル JAR ファイルのパスを解決する関数インターフェース。 */
  @FunctionalInterface
  public interface JarLocator {
    /**
     * 依存関係のローカル JAR パスを返す。
     *
     * @param dependency 対象の依存関係
     * @return JAR ファイルのパス。見つからない場合は {@code null}
     */
    Path locateJar(Dependency dependency);
  }

  /** GitHub API からライセンス情報を取得する関数インターフェース。 */
  @FunctionalInterface
  public interface GitHubLicenseFetcher {
    /**
     * GitHub API から依存関係のライセンス情報を取得する。
     *
     * @param dependency 対象の依存関係
     * @return ライセンス情報。取得できない場合は {@code null}
     * @throws IOException 取得失敗時
     */
    GitHubLicenseInfo fetchLicense(Dependency dependency) throws IOException;
  }

  /**
   * GitHub API から取得したライセンス情報。
   *
   * @param spdxId SPDX 識別子（GitHub が返す値）
   * @param licenseName ライセンス名
   * @param htmlUrl ライセンスページの URL
   */
  public record GitHubLicenseInfo(String spdxId, String licenseName, String htmlUrl) {}
}

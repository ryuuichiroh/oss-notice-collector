package com.github.noticecollector.notice.source;

import com.github.noticecollector.cache.ArchiveCache;
import com.github.noticecollector.config.NoticeCollectorConfig;
import com.github.noticecollector.http.HttpClientWrapper;
import com.github.noticecollector.http.HttpRequestException;
import com.github.noticecollector.license.LicensedDependency;
import com.github.noticecollector.notice.NoticeSearchResult;
import com.github.noticecollector.notice.NoticeSearchResult.SearchOutcome;
import com.github.noticecollector.notice.NoticeSource;
import com.github.noticecollector.notice.util.ArchiveNoticeExtractor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 優先順位 1: ユーザ指定パス/URL から NOTICE ファイルを取得する NoticeSource 実装。
 *
 * <p>設定ファイルの {@code overrides} セクションで、特定の依存関係に対して {@code noticePath} または
 * {@code noticeUrl} が指定されている場合、そのパスまたは URL から NOTICE ファイルを取得する。
 *
 * <p>URL がアーカイブファイル（.zip, .tar.gz, .tgz）の場合、ダウンロード後に展開して NOTICE を抽出する。
 */
public class UserOverrideSource implements NoticeSource {

  private static final Logger LOG = LoggerFactory.getLogger(UserOverrideSource.class);

  private final List<NoticeCollectorConfig.OverrideConfig> overrides;
  private final HttpClientWrapper httpClient;
  private final ArchiveNoticeExtractor archiveExtractor;
  private final ArchiveCache archiveCache;

  /**
   * UserOverrideSource を構築する。
   *
   * @param config 設定オブジェクト
   * @param httpClient HTTP 通信ラッパー
   */
  public UserOverrideSource(NoticeCollectorConfig config, HttpClientWrapper httpClient) {
    this(config, httpClient, null);
  }

  /**
   * UserOverrideSource を構築する（ArchiveCache 対応）。
   *
   * @param config 設定オブジェクト
   * @param httpClient HTTP 通信ラッパー
   * @param archiveCache アーカイブキャッシュ（nullable）
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "ArchiveCache is intentionally shared across components")
  public UserOverrideSource(NoticeCollectorConfig config, HttpClientWrapper httpClient,
      ArchiveCache archiveCache) {
    this.overrides = config.getOverrides();
    this.httpClient = httpClient;
    this.archiveExtractor = new ArchiveNoticeExtractor();
    this.archiveCache = archiveCache;
  }

  @Override
  public NoticeSearchResult search(LicensedDependency dependency,
                                   List<String> noticePatterns,
                                   List<String> licensePatterns) {
    NoticeCollectorConfig.OverrideConfig override = findOverride(dependency);
    if (override == null) {
      return new NoticeSearchResult(SearchOutcome.NOT_FOUND, null, null, null,
          "ユーザオーバーライド未設定");
    }

    // noticePath が指定されている場合、ローカルファイルから読み込む
    if (override.getNoticePath() != null && !override.getNoticePath().isBlank()) {
      return readFromPath(override.getNoticePath(), dependency, noticePatterns, licensePatterns);
    }

    // noticeUrl が指定されている場合、HTTP で取得する
    if (override.getNoticeUrl() != null && !override.getNoticeUrl().isBlank()) {
      return fetchFromUrl(override.getNoticeUrl(), dependency, noticePatterns, licensePatterns);
    }

    return new NoticeSearchResult(SearchOutcome.NOT_FOUND, null, null, null,
        "オーバーライド設定に noticePath/noticeUrl が未指定");
  }

  @Override
  public int getPriority() {
    return 1;
  }

  @Override
  public String getSourceName() {
    return "USER_OVERRIDE";
  }

  /**
   * 依存関係に一致するオーバーライド設定を検索する。groupId と artifactId が一致し、version が未指定
   * または一致する設定を返す。
   */
  private NoticeCollectorConfig.OverrideConfig findOverride(LicensedDependency dependency) {
    if (overrides == null) {
      return null;
    }
    String groupId = dependency.dependency().groupId();
    String artifactId = dependency.dependency().artifactId();
    String version = dependency.dependency().version();

    for (NoticeCollectorConfig.OverrideConfig override : overrides) {
      if (groupId.equals(override.getGroupId())
          && artifactId.equals(override.getArtifactId())
          && (override.getVersion() == null || version.equals(override.getVersion()))) {
        return override;
      }
    }
    return null;
  }

  /** ローカルファイルパスから NOTICE を読み込む。アーカイブファイルの場合は展開して抽出する。 */
  private NoticeSearchResult readFromPath(String noticePath, LicensedDependency dependency,
      List<String> noticePatterns, List<String> licensePatterns) {
    Path path = Path.of(noticePath);
    if (!Files.exists(path)) {
      LOG.warn("ユーザ指定の NOTICE ファイルが見つかりません: {} ({})",
          noticePath, dependency.dependency().toGav());
      return new NoticeSearchResult(SearchOutcome.NOT_FOUND, null, null, noticePath,
          "指定パスにファイルが存在しません: " + noticePath);
    }

    // アーカイブファイルかどうかを判定
    String lowerPath = noticePath.toLowerCase();
    if (lowerPath.endsWith(".zip") || lowerPath.endsWith(".tar.gz") || lowerPath.endsWith(".tgz")) {
      return extractFromArchive(path, noticePath, dependency, noticePatterns, licensePatterns);
    }

    // 通常のテキストファイルとして読み込む
    try {
      String content = Files.readString(path, StandardCharsets.UTF_8);
      LOG.info("ユーザ指定パスから NOTICE を取得: {} ({})",
          noticePath, dependency.dependency().toGav());
      return new NoticeSearchResult(SearchOutcome.FOUND, content, null, noticePath, null);
    } catch (IOException e) {
      LOG.error("ユーザ指定 NOTICE ファイルの読込に失敗: {} ({}) - {}",
          noticePath, dependency.dependency().toGav(), e.getMessage());
      return new NoticeSearchResult(SearchOutcome.ERROR, null, null, noticePath,
          "ファイル読込エラー: " + e.getMessage());
    }
  }

  /** URL から NOTICE を HTTP で取得する。アーカイブファイルの場合は展開して抽出する。 */
  private NoticeSearchResult fetchFromUrl(String noticeUrl, LicensedDependency dependency,
      List<String> noticePatterns, List<String> licensePatterns) {
    // バージョン変数を置換
    String resolvedUrl = resolveVersionPlaceholders(noticeUrl, dependency);
    
    // アーカイブファイルかどうかを判定
    String lowerUrl = resolvedUrl.toLowerCase();
    boolean isArchive = lowerUrl.endsWith(".zip") || lowerUrl.endsWith(".tar.gz")
        || lowerUrl.endsWith(".tgz");

    if (isArchive) {
      return downloadAndExtractArchive(resolvedUrl, dependency, noticePatterns, licensePatterns);
    }

    // 通常のテキストファイルとして取得
    try {
      String content = httpClient.getString(resolvedUrl);
      LOG.info("ユーザ指定 URL から NOTICE を取得: {} ({})",
          resolvedUrl, dependency.dependency().toGav());
      return new NoticeSearchResult(SearchOutcome.FOUND, content, null, resolvedUrl, null);
    } catch (HttpRequestException e) {
      LOG.error("ユーザ指定 URL からの NOTICE 取得に失敗: {} ({}) - {}",
          resolvedUrl, dependency.dependency().toGav(), e.getMessage());
      return new NoticeSearchResult(SearchOutcome.ERROR, null, null, resolvedUrl,
          "HTTP 取得エラー: " + e.getMessage());
    }
  }

  /**
   * URL 内のバージョンプレースホルダーを実際のバージョン値に置換する。
   * 
   * <p>サポートされるプレースホルダー:
   * <ul>
   *   <li>${version} - バージョンをそのまま置換 (例: 1.2.3)</li>
   *   <li>${underscored_version} - バージョンのドットをアンダースコアに置換 (例: 1_2_3)</li>
   *   <li>${version_major} - メジャーバージョンのみ (例: 1)</li>
   *   <li>${version_minor} - メジャー.マイナーバージョン (例: 1.2)</li>
   * </ul>
   *
   * @param url 元の URL
   * @param dependency 依存関係情報
   * @return プレースホルダーが置換された URL
   */
  private String resolveVersionPlaceholders(String url, LicensedDependency dependency) {
    String version = dependency.dependency().version();
    if (version == null || version.isBlank()) {
      return url;
    }

    String result = url;
    
    // ${underscored_version} を置換
    if (result.contains("${underscored_version}")) {
      String underscoredVersion = version.replace('.', '_').replace('-', '_');
      result = result.replace("${underscored_version}", underscoredVersion);
    }
    
    // ${version_major} を置換
    if (result.contains("${version_major}")) {
      String majorVersion = version.split("\\.")[0];
      result = result.replace("${version_major}", majorVersion);
    }
    
    // ${version_minor} を置換
    if (result.contains("${version_minor}")) {
      String[] parts = version.split("\\.");
      String minorVersion = parts.length >= 2 ? parts[0] + "." + parts[1] : version;
      result = result.replace("${version_minor}", minorVersion);
    }
    
    // ${version} を置換（最後に実行して他のプレースホルダーと干渉しないようにする）
    if (result.contains("${version}")) {
      result = result.replace("${version}", version);
    }
    
    return result;
  }

  /** アーカイブファイルをダウンロードして NOTICE/LICENSE を抽出する。 */
  private NoticeSearchResult downloadAndExtractArchive(String archiveUrl,
      LicensedDependency dependency, List<String> noticePatterns, List<String> licensePatterns) {
    String cacheKey = dependency.dependency().toGav() + ":noticeUrl";
    Path tempFile = null;
    boolean shouldDeleteTempFile = true;

    try {
      // キャッシュを確認
      if (archiveCache != null && archiveCache.contains(cacheKey)) {
        tempFile = archiveCache.get(cacheKey);
        shouldDeleteTempFile = false;
        LOG.debug("noticeUrl アーカイブのキャッシュを使用: {} ({})",
            tempFile, dependency.dependency().toGav());
      } else {
        // 一時ファイルにダウンロード
        String suffix = determineTempFileSuffix(archiveUrl);
        tempFile = Files.createTempFile("notice-archive-", suffix);
        httpClient.downloadToFile(archiveUrl, tempFile);

        LOG.debug("アーカイブをダウンロードしました: {} -> {} ({})",
            archiveUrl, tempFile, dependency.dependency().toGav());

        // キャッシュに登録（LicenseIdentifier で既にダウンロード済みの可能性がある）
        if (archiveCache != null) {
          archiveCache.put(cacheKey, tempFile);
          shouldDeleteTempFile = false;
        }
      }

      // アーカイブから NOTICE/LICENSE を抽出
      return extractFromArchive(tempFile, archiveUrl, dependency, noticePatterns, licensePatterns);

    } catch (HttpRequestException e) {
      LOG.error("アーカイブのダウンロードに失敗: {} ({}) - {}",
          archiveUrl, dependency.dependency().toGav(), e.getMessage());
      return new NoticeSearchResult(SearchOutcome.ERROR, null, null, archiveUrl,
          "アーカイブダウンロードエラー: " + e.getMessage());
    } catch (IOException e) {
      LOG.error("一時ファイルの作成に失敗: {} ({}) - {}",
          archiveUrl, dependency.dependency().toGav(), e.getMessage());
      return new NoticeSearchResult(SearchOutcome.ERROR, null, null, archiveUrl,
          "一時ファイル作成エラー: " + e.getMessage());
    } finally {
      if (shouldDeleteTempFile && tempFile != null) {
        deleteTempFile(tempFile);
      }
    }
  }

  /** アーカイブファイルから NOTICE/LICENSE を抽出する。 */
  private NoticeSearchResult extractFromArchive(Path archivePath, String sourceUrl,
      LicensedDependency dependency, List<String> noticePatterns, List<String> licensePatterns) {
    try {
      Optional<String> noticeContent = archiveExtractor.extract(archivePath, noticePatterns);
      Optional<String> licenseContent = archiveExtractor.extract(archivePath, licensePatterns);

      if (noticeContent.isPresent() || licenseContent.isPresent()) {
        LOG.info("ユーザ指定アーカイブから NOTICE/LICENSE を抽出: {} ({})",
            sourceUrl, dependency.dependency().toGav());
        return new NoticeSearchResult(SearchOutcome.FOUND,
            noticeContent.orElse(null),
            licenseContent.orElse(null),
            sourceUrl, null);
      } else {
        LOG.warn("アーカイブ内に NOTICE/LICENSE が見つかりません: {} ({})",
            sourceUrl, dependency.dependency().toGav());
        return new NoticeSearchResult(SearchOutcome.SOURCE_FOUND_NO_NOTICE, null, null, sourceUrl,
            "アーカイブ内に NOTICE/LICENSE ファイルが存在しません");
      }
    } catch (IOException e) {
      LOG.error("アーカイブからの NOTICE/LICENSE 抽出に失敗: {} ({}) - {}",
          sourceUrl, dependency.dependency().toGav(), e.getMessage());
      return new NoticeSearchResult(SearchOutcome.ERROR, null, null, sourceUrl,
          "アーカイブ抽出エラー: " + e.getMessage());
    }
  }

  /** URL から一時ファイルのサフィックスを決定する。 */
  private String determineTempFileSuffix(String url) {
    String lowerUrl = url.toLowerCase();
    if (lowerUrl.endsWith(".tar.gz")) {
      return ".tar.gz";
    } else if (lowerUrl.endsWith(".tgz")) {
      return ".tgz";
    } else if (lowerUrl.endsWith(".zip")) {
      return ".zip";
    }
    return ".tmp";
  }

  /** 一時ファイルを削除する。 */
  private void deleteTempFile(Path tempFile) {
    try {
      Files.deleteIfExists(tempFile);
      LOG.debug("一時ファイルを削除しました: {}", tempFile);
    } catch (IOException e) {
      LOG.warn("一時ファイルの削除に失敗しました: {} - {}", tempFile, e.getMessage());
    }
  }
}

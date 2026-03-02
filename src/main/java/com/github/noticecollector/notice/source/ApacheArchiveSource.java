package com.github.noticecollector.notice.source;

import com.github.noticecollector.config.NoticeCollectorConfig;
import com.github.noticecollector.config.NoticeCollectorConfig.ApacheArchiveConfig;
import com.github.noticecollector.config.NoticeCollectorConfig.ArchiveMappingConfig;
import com.github.noticecollector.http.HttpClientWrapper;
import com.github.noticecollector.http.HttpRequestException;
import com.github.noticecollector.license.LicensedDependency;
import com.github.noticecollector.notice.NoticeSearchResult;
import com.github.noticecollector.notice.NoticeSearchResult.SearchOutcome;
import com.github.noticecollector.notice.NoticeSource;
import com.github.noticecollector.notice.util.ArchiveNoticeExtractor;
import com.github.noticecollector.notice.util.JarNoticeExtractor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 優先順位 5: Apache Archive からアーカイブをダウンロード・展開し、NOTICE ファイルを検索する NoticeSource 実装。
 *
 * <p>設定ファイルの {@code apacheArchive.mappings} に登録された依存関係のみを対象とする。
 * マッピングテーブルに未登録の依存関係は {@link SearchOutcome#NOT_FOUND} を返す。
 * アーカイブファイルサイズが {@code maxDownloadSizeMb} を超過する場合はダウンロードをスキップする。
 */
public class ApacheArchiveSource implements NoticeSource {

  private static final Logger LOG = LoggerFactory.getLogger(ApacheArchiveSource.class);

  /** ディレクトリリスティング HTML から href リンクを抽出する正規表現。 */
  static final Pattern HREF_PATTERN = Pattern.compile("href=\"([^\"]+)\"");

  /** ソースアーカイブとして認識するファイル名パターン。 */
  private static final List<String> SOURCE_ARCHIVE_SUFFIXES = List.of(
      "-src.tar.gz", "-src.zip",
      "-source-release.tar.gz", "-source-release.zip",
      "-sources.jar");

  private final ApacheArchiveConfig config;
  private final HttpClientWrapper httpClient;
  private final ArchiveNoticeExtractor archiveExtractor;
  private final JarNoticeExtractor jarExtractor;

  /**
   * ApacheArchiveSource を構築する。
   *
   * @param config 設定オブジェクト
   * @param httpClient HTTP 通信ラッパー
   */
  public ApacheArchiveSource(NoticeCollectorConfig config, HttpClientWrapper httpClient) {
    this(config.getApacheArchive(), httpClient,
        new ArchiveNoticeExtractor(), new JarNoticeExtractor());
  }

  /**
   * テスト用コンストラクタ。抽出ユーティリティを外部から注入できる。
   *
   * @param archiveConfig Apache Archive 設定
   * @param httpClient HTTP 通信ラッパー
   * @param archiveExtractor tar.gz/zip 内 NOTICE 抽出ユーティリティ
   * @param jarExtractor JAR 内 NOTICE 抽出ユーティリティ
   */
  ApacheArchiveSource(ApacheArchiveConfig archiveConfig, HttpClientWrapper httpClient,
      ArchiveNoticeExtractor archiveExtractor, JarNoticeExtractor jarExtractor) {
    this.config = archiveConfig;
    this.httpClient = httpClient;
    this.archiveExtractor = archiveExtractor;
    this.jarExtractor = jarExtractor;
  }

  @Override
  public NoticeSearchResult search(LicensedDependency dependency,
                                   List<String> noticePatterns,
                                   List<String> licensePatterns) {
    String gav = dependency.dependency().toGav();

    // 無効化チェック
    if (!config.isEnabled()) {
      LOG.debug("Apache Archive ソースは無効化されています: {}", gav);
      return new NoticeSearchResult(SearchOutcome.NOT_FOUND, null, null, null,
          "Apache Archive ソースは無効化されています");
    }

    // マッピングテーブルから archivePath を検索
    Optional<ArchiveMappingConfig> mapping = findMapping(dependency);
    if (mapping.isEmpty()) {
      LOG.debug("Apache Archive マッピングに未登録: {}", gav);
      return new NoticeSearchResult(SearchOutcome.NOT_FOUND, null, null, null,
          "Apache Archive マッピングテーブルに未登録です");
    }

    String archivePath = mapping.get().getArchivePath();
    String version = dependency.dependency().version();
    String directoryUrl = buildDirectoryUrl(archivePath, version);

    return searchInDirectory(directoryUrl, dependency, noticePatterns, licensePatterns);
  }

  @Override
  public int getPriority() {
    return 5;
  }

  @Override
  public String getSourceName() {
    return "APACHE_ARCHIVE";
  }

  /**
   * マッピングテーブルから対象の依存関係に一致するエントリを検索する。
   *
   * @param dependency 対象の依存関係
   * @return 一致するマッピング設定。見つからない場合は空の Optional
   */
  Optional<ArchiveMappingConfig> findMapping(LicensedDependency dependency) {
    if (config.getMappings() == null) {
      return Optional.empty();
    }
    String groupId = dependency.dependency().groupId();
    String artifactId = dependency.dependency().artifactId();
    return config.getMappings().stream()
        .filter(m -> groupId.equals(m.getGroupId()) && artifactId.equals(m.getArtifactId()))
        .findFirst();
  }

  /**
   * Apache Archive のディレクトリ URL を構築する。
   *
   * <p>形式: {@code {baseUrl}{archivePath}/{version}/}
   */
  String buildDirectoryUrl(String archivePath, String version) {
    String base = config.getBaseUrl();
    if (!base.endsWith("/")) {
      base = base + "/";
    }
    return base + archivePath + "/" + version + "/";
  }

  /**
   * ディレクトリリスティングを取得し、ソースアーカイブを検索・ダウンロード・NOTICE 抽出する。
   */
  private NoticeSearchResult searchInDirectory(String directoryUrl,
      LicensedDependency dependency, List<String> noticePatterns,
      List<String> licensePatterns) {
    String gav = dependency.dependency().toGav();

    try {
      String html = httpClient.getString(directoryUrl);
      List<String> archiveLinks = parseSourceArchiveLinks(html);

      if (archiveLinks.isEmpty()) {
        LOG.debug("Apache Archive ディレクトリにソースアーカイブなし: {} ({})",
            directoryUrl, gav);
        return new NoticeSearchResult(SearchOutcome.NOT_FOUND, null, null, directoryUrl,
            "Apache Archive ディレクトリにソースアーカイブが見つかりません");
      }

      // ソースアーカイブを順に試行
      NoticeSearchResult lastSourceFound = null;
      for (String archiveLink : archiveLinks) {
        String archiveUrl = directoryUrl + archiveLink;
        NoticeSearchResult result = tryArchive(archiveUrl, archiveLink,
            noticePatterns, licensePatterns, gav);

        if (result.outcome() == SearchOutcome.FOUND) {
          return result;
        }
        if (result.outcome() == SearchOutcome.SOURCE_FOUND_NO_NOTICE) {
          lastSourceFound = result;
        }
      }

      if (lastSourceFound != null) {
        return lastSourceFound;
      }

      return new NoticeSearchResult(SearchOutcome.NOT_FOUND, null, null, directoryUrl,
          "Apache Archive のソースアーカイブから NOTICE/LICENSE が見つかりません");

    } catch (HttpRequestException e) {
      LOG.debug("Apache Archive ディレクトリ取得失敗: {} ({}) - {}",
          directoryUrl, gav, e.getMessage());
      if (e.getStatusCode() == 404) {
        return new NoticeSearchResult(SearchOutcome.NOT_FOUND, null, null, directoryUrl,
            "Apache Archive にディレクトリが存在しません");
      }
      return new NoticeSearchResult(SearchOutcome.ERROR, null, null, directoryUrl,
          "Apache Archive ディレクトリ取得エラー: " + e.getMessage());
    }
  }

  /**
   * 個別のアーカイブファイルをダウンロードし、NOTICE ファイルを抽出する。
   *
   * <p>ダウンロード前に HTTP HEAD でファイルサイズを確認し、{@code maxDownloadSizeMb} を
   * 超過する場合はスキップする。
   */
  private NoticeSearchResult tryArchive(String archiveUrl, String fileName,
      List<String> noticePatterns, List<String> licensePatterns, String gav) {
    // サイズチェック
    long maxBytes = (long) config.getMaxDownloadSizeMb() * 1024 * 1024;
    long contentLength = httpClient.getContentLength(archiveUrl);
    if (contentLength > maxBytes) {
      LOG.warn("Apache Archive サイズ超過: {} ({} bytes > {} MB) ({})",
          archiveUrl, contentLength, config.getMaxDownloadSizeMb(), gav);
      return new NoticeSearchResult(SearchOutcome.ERROR, null, null, archiveUrl,
          "アーカイブサイズ超過 (" + contentLength + " bytes > "
              + config.getMaxDownloadSizeMb() + " MB)");
    }

    Path tempFile = null;
    try {
      byte[] archiveBytes = httpClient.get(archiveUrl);
      String suffix = determineTempFileSuffix(fileName);
      tempFile = Files.createTempFile("notice-apache-", suffix);
      Files.write(tempFile, archiveBytes);

      Optional<String> noticeContent = extractContent(tempFile, fileName, noticePatterns);
      Optional<String> licenseContent = extractContent(tempFile, fileName, licensePatterns);

      if (noticeContent.isPresent() || licenseContent.isPresent()) {
        LOG.info("Apache Archive から NOTICE/LICENSE を発見: {} ({})", archiveUrl, gav);
        return new NoticeSearchResult(SearchOutcome.FOUND,
            noticeContent.orElse(null),
            licenseContent.orElse(null),
            archiveUrl, null);
      }

      LOG.debug("Apache Archive のアーカイブに NOTICE/LICENSE なし: {} ({})", archiveUrl, gav);
      return new NoticeSearchResult(SearchOutcome.SOURCE_FOUND_NO_NOTICE, null, null, archiveUrl,
          "Apache Archive のアーカイブに NOTICE/LICENSE が含まれていません");

    } catch (HttpRequestException e) {
      LOG.debug("Apache Archive アーカイブ取得失敗: {} ({}) - {}",
          archiveUrl, gav, e.getMessage());
      if (e.getStatusCode() == 404) {
        return new NoticeSearchResult(SearchOutcome.NOT_FOUND, null, null, archiveUrl,
            "Apache Archive にアーカイブが存在しません");
      }
      return new NoticeSearchResult(SearchOutcome.ERROR, null, null, archiveUrl,
          "Apache Archive アーカイブ取得エラー: " + e.getMessage());

    } catch (IOException e) {
      LOG.warn("Apache Archive アーカイブ処理に失敗: {} ({})", archiveUrl, gav, e);
      return new NoticeSearchResult(SearchOutcome.ERROR, null, null, archiveUrl,
          "アーカイブ処理エラー: " + e.getMessage());

    } finally {
      deleteTempFile(tempFile);
    }
  }

  /**
   * アーカイブファイルからコンテンツを抽出する。ファイル名に基づいて適切な抽出方法を選択する。
   *
   * @param tempFile ダウンロードしたアーカイブの一時ファイル
   * @param fileName 元のファイル名（拡張子判定用）
   * @param patterns 検索パターンリスト
   * @return 見つかったコンテンツ
   * @throws IOException 抽出に失敗した場合
   */
  private Optional<String> extractContent(Path tempFile, String fileName, List<String> patterns)
      throws IOException {
    String lowerName = fileName.toLowerCase();
    if (lowerName.endsWith(".jar")) {
      return jarExtractor.extract(tempFile, patterns);
    }
    return archiveExtractor.extract(tempFile, patterns);
  }

  /**
   * HTML ディレクトリリスティングからソースアーカイブのリンクを抽出する。
   *
   * <p>Apache Archive のディレクトリリスティングは {@code <a href="filename">} 形式の
   * リンクを含む。ソースアーカイブとして認識するサフィックスに一致するリンクのみを返す。
   *
   * @param html ディレクトリリスティングの HTML
   * @return ソースアーカイブのファイル名リスト
   */
  List<String> parseSourceArchiveLinks(String html) {
    List<String> links = new ArrayList<>();
    if (html == null || html.isEmpty()) {
      return links;
    }

    Matcher matcher = HREF_PATTERN.matcher(html);
    while (matcher.find()) {
      String href = matcher.group(1);
      // ディレクトリリンクやパス付きリンクを除外
      if (href.contains("/") || href.startsWith("?") || href.startsWith("#")) {
        continue;
      }
      String lowerHref = href.toLowerCase();
      for (String suffix : SOURCE_ARCHIVE_SUFFIXES) {
        if (lowerHref.endsWith(suffix)) {
          links.add(href);
          break;
        }
      }
    }
    return links;
  }

  /**
   * ファイル名から一時ファイルのサフィックスを決定する。
   */
  private String determineTempFileSuffix(String fileName) {
    String lower = fileName.toLowerCase();
    if (lower.endsWith(".tar.gz")) {
      return ".tar.gz";
    } else if (lower.endsWith(".zip")) {
      return ".zip";
    } else if (lower.endsWith(".jar")) {
      return ".jar";
    }
    return ".archive";
  }

  /** 一時ファイルを削除する。 */
  private void deleteTempFile(Path tempFile) {
    if (tempFile != null) {
      try {
        Files.deleteIfExists(tempFile);
      } catch (IOException e) {
        LOG.debug("一時ファイルの削除に失敗: {}", tempFile, e);
      }
    }
  }
}

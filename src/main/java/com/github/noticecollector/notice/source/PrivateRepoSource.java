package com.github.noticecollector.notice.source;

import com.github.noticecollector.config.NoticeCollectorConfig;
import com.github.noticecollector.http.HttpClientWrapper;
import com.github.noticecollector.http.HttpRequestException;
import com.github.noticecollector.license.LicensedDependency;
import com.github.noticecollector.notice.NoticeSearchResult;
import com.github.noticecollector.notice.NoticeSearchResult.SearchOutcome;
import com.github.noticecollector.notice.NoticeSource;
import com.github.noticecollector.notice.util.JarNoticeExtractor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 優先順位 4: 社内プライベートリポジトリからソース JAR を検索し、NOTICE ファイルを抽出する NoticeSource 実装。
 *
 * <p>設定ファイルの {@code repositories} セクションに登録されたリポジトリを順に検索する。
 * リポジトリ URL に Maven リポジトリ形式のパスを付加してソース JAR の取得を試みる。
 */
public class PrivateRepoSource implements NoticeSource {

  private static final Logger LOG = LoggerFactory.getLogger(PrivateRepoSource.class);

  private final List<NoticeCollectorConfig.RepositoryConfig> repositories;
  private final HttpClientWrapper httpClient;
  private final JarNoticeExtractor jarExtractor;

  /**
   * PrivateRepoSource を構築する。
   *
   * @param config 設定オブジェクト
   * @param httpClient HTTP 通信ラッパー
   */
  public PrivateRepoSource(NoticeCollectorConfig config, HttpClientWrapper httpClient) {
    this(config, httpClient, new JarNoticeExtractor());
  }

  /**
   * テスト用コンストラクタ。JarNoticeExtractor を外部から注入できる。
   *
   * @param config 設定オブジェクト
   * @param httpClient HTTP 通信ラッパー
   * @param jarExtractor JAR 内 NOTICE 抽出ユーティリティ
   */
  public PrivateRepoSource(NoticeCollectorConfig config, HttpClientWrapper httpClient,
      JarNoticeExtractor jarExtractor) {
    this.repositories = config.getRepositories();
    this.httpClient = httpClient;
    this.jarExtractor = jarExtractor;
  }

  @Override
  public NoticeSearchResult search(LicensedDependency dependency,
                                   List<String> noticePatterns,
                                   List<String> licensePatterns) {
    if (repositories == null || repositories.isEmpty()) {
      return new NoticeSearchResult(SearchOutcome.NOT_FOUND, null, null, null,
          "社内リポジトリが設定されていません");
    }

    String gav = dependency.dependency().toGav();
    NoticeSearchResult lastSourceFound = null;
    NoticeSearchResult lastError = null;

    for (NoticeCollectorConfig.RepositoryConfig repo : repositories) {
      if (repo.getUrl() == null || repo.getUrl().isBlank()) {
        continue;
      }

      String sourceJarUrl = buildSourceJarUrl(repo.getUrl(), dependency);
      NoticeSearchResult result = tryRepository(sourceJarUrl, dependency,
          noticePatterns, licensePatterns, repo.getName());

      if (result.outcome() == SearchOutcome.FOUND) {
        return result;
      }
      if (result.outcome() == SearchOutcome.SOURCE_FOUND_NO_NOTICE) {
        lastSourceFound = result;
      }
      if (result.outcome() == SearchOutcome.ERROR) {
        lastError = result;
      }
    }

    if (lastSourceFound != null) {
      return lastSourceFound;
    }
    if (lastError != null) {
      return lastError;
    }

    LOG.debug("全社内リポジトリでソース JAR が見つかりません: {}", gav);
    return new NoticeSearchResult(SearchOutcome.NOT_FOUND, null, null, null,
        "全社内リポジトリでソース JAR が見つかりません");
  }

  @Override
  public int getPriority() {
    return 4;
  }

  @Override
  public String getSourceName() {
    return "PRIVATE_REPO";
  }

  /**
   * 社内リポジトリからソース JAR のダウンロードと NOTICE 抽出を試みる。
   */
  private NoticeSearchResult tryRepository(String sourceJarUrl,
      LicensedDependency dependency, List<String> noticePatterns,
      List<String> licensePatterns, String repoName) {
    String gav = dependency.dependency().toGav();
    Path tempJar = null;

    try {
      byte[] jarBytes = httpClient.get(sourceJarUrl);
      tempJar = Files.createTempFile("notice-private-src-", ".jar");
      Files.write(tempJar, jarBytes);

      Optional<String> noticeContent = jarExtractor.extract(tempJar, noticePatterns);
      Optional<String> licenseContent = jarExtractor.extract(tempJar, licensePatterns);

      if (noticeContent.isPresent() || licenseContent.isPresent()) {
        LOG.info("社内リポジトリ [{}] のソース JAR から NOTICE/LICENSE を発見: {} ({})",
            repoName, sourceJarUrl, gav);
        return new NoticeSearchResult(SearchOutcome.FOUND,
            noticeContent.orElse(null),
            licenseContent.orElse(null),
            sourceJarUrl, null);
      }

      LOG.debug("社内リポジトリ [{}] のソース JAR に NOTICE/LICENSE なし: {} ({})",
          repoName, sourceJarUrl, gav);
      return new NoticeSearchResult(SearchOutcome.SOURCE_FOUND_NO_NOTICE, null, null, sourceJarUrl,
          "社内リポジトリ [" + repoName + "] のソース JAR に NOTICE/LICENSE が含まれていません");

    } catch (HttpRequestException e) {
      LOG.debug("社内リポジトリ [{}] からソース JAR 取得失敗: {} ({}) - {}",
          repoName, sourceJarUrl, gav, e.getMessage());
      if (e.getStatusCode() == 404) {
        return new NoticeSearchResult(SearchOutcome.NOT_FOUND, null, null, sourceJarUrl,
            "社内リポジトリ [" + repoName + "] にソース JAR が存在しません");
      }
      return new NoticeSearchResult(SearchOutcome.ERROR, null, null, sourceJarUrl,
          "社内リポジトリ [" + repoName + "] ソース JAR 取得エラー: " + e.getMessage());

    } catch (IOException e) {
      LOG.warn("社内リポジトリ [{}] のソース JAR 処理に失敗: {} ({})",
          repoName, sourceJarUrl, gav, e);
      return new NoticeSearchResult(SearchOutcome.ERROR, null, null, sourceJarUrl,
          "ソース JAR 処理エラー: " + e.getMessage());

    } finally {
      deleteTempFile(tempJar);
    }
  }

  /**
   * 社内リポジトリのソース JAR URL を構築する。
   *
   * <p>形式: {@code {repoUrl}/{groupPath}/{artifactId}/{version}/{artifactId}-{version}-sources.jar}
   */
  String buildSourceJarUrl(String repoBaseUrl, LicensedDependency dependency) {
    String groupId = dependency.dependency().groupId();
    String artifactId = dependency.dependency().artifactId();
    String version = dependency.dependency().version();
    String groupPath = groupId.replace('.', '/');
    String base = repoBaseUrl.endsWith("/") ? repoBaseUrl : repoBaseUrl + "/";
    return String.format("%s%s/%s/%s/%s-%s-sources.jar",
        base, groupPath, artifactId, version, artifactId, version);
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

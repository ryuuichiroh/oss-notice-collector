package com.github.noticecollector.notice.source;

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
 * 優先順位 3: Maven Central からソース JAR をダウンロードし、NOTICE ファイルを抽出する NoticeSource 実装。
 *
 * <p>ソース JAR の URL は {@code groupId} のドット区切りをパス区切りに変換し、
 * {@code https://repo1.maven.org/maven2/{groupPath}/{artifactId}/{version}/{artifactId}-{version}-sources.jar}
 * の形式で構築する。
 */
public class MavenCentralSource implements NoticeSource {

  private static final Logger LOG = LoggerFactory.getLogger(MavenCentralSource.class);
  private static final String MAVEN_CENTRAL_BASE = "https://repo1.maven.org/maven2/";

  private final HttpClientWrapper httpClient;
  private final JarNoticeExtractor jarExtractor;

  /**
   * MavenCentralSource を構築する。
   *
   * @param httpClient HTTP 通信ラッパー
   */
  public MavenCentralSource(HttpClientWrapper httpClient) {
    this(httpClient, new JarNoticeExtractor());
  }

  /**
   * テスト用コンストラクタ。JarNoticeExtractor を外部から注入できる。
   *
   * @param httpClient HTTP 通信ラッパー
   * @param jarExtractor JAR 内 NOTICE 抽出ユーティリティ
   */
  public MavenCentralSource(HttpClientWrapper httpClient, JarNoticeExtractor jarExtractor) {
    this.httpClient = httpClient;
    this.jarExtractor = jarExtractor;
  }

  @Override
  public NoticeSearchResult search(LicensedDependency dependency,
                                   List<String> noticePatterns,
                                   List<String> licensePatterns) {
    String gav = dependency.dependency().toGav();
    String sourceJarUrl = buildSourceJarUrl(dependency);

    // ソース JAR をダウンロードして一時ファイルに保存
    Path tempJar = null;
    try {
      byte[] jarBytes = httpClient.get(sourceJarUrl);
      tempJar = Files.createTempFile("notice-src-", ".jar");
      Files.write(tempJar, jarBytes);

      Optional<String> noticeContent = jarExtractor.extract(tempJar, noticePatterns);
      Optional<String> licenseContent = jarExtractor.extract(tempJar, licensePatterns);

      if (noticeContent.isPresent() || licenseContent.isPresent()) {
        LOG.info("Maven Central ソース JAR から NOTICE/LICENSE を発見: {} ({})", sourceJarUrl, gav);
        return new NoticeSearchResult(SearchOutcome.FOUND,
            noticeContent.orElse(null),
            licenseContent.orElse(null),
            sourceJarUrl, null);
      }

      LOG.debug("Maven Central ソース JAR に NOTICE/LICENSE なし: {} ({})", sourceJarUrl, gav);
      return new NoticeSearchResult(SearchOutcome.SOURCE_FOUND_NO_NOTICE, null, null, sourceJarUrl,
          "Maven Central ソース JAR に NOTICE/LICENSE が含まれていません");

    } catch (HttpRequestException e) {
      LOG.debug("Maven Central ソース JAR の取得に失敗: {} ({}) - {}", sourceJarUrl, gav,
          e.getMessage());
      if (e.getStatusCode() == 404) {
        return new NoticeSearchResult(SearchOutcome.NOT_FOUND, null, null, sourceJarUrl,
            "Maven Central にソース JAR が存在しません");
      }
      return new NoticeSearchResult(SearchOutcome.ERROR, null, null, sourceJarUrl,
          "Maven Central ソース JAR 取得エラー: " + e.getMessage());

    } catch (IOException e) {
      LOG.warn("Maven Central ソース JAR の処理に失敗: {} ({})", sourceJarUrl, gav, e);
      return new NoticeSearchResult(SearchOutcome.ERROR, null, null, sourceJarUrl,
          "ソース JAR 処理エラー: " + e.getMessage());

    } finally {
      deleteTempFile(tempJar);
    }
  }

  @Override
  public int getPriority() {
    return 3;
  }

  @Override
  public String getSourceName() {
    return "MAVEN_CENTRAL_SOURCE_JAR";
  }

  /**
   * Maven Central のソース JAR URL を構築する。
   *
   * <p>形式: {@code https://repo1.maven.org/maven2/{groupPath}/{artifactId}/{version}/{artifactId}-{version}-sources.jar}
   */
  String buildSourceJarUrl(LicensedDependency dependency) {
    String groupId = dependency.dependency().groupId();
    String artifactId = dependency.dependency().artifactId();
    String version = dependency.dependency().version();
    String groupPath = groupId.replace('.', '/');
    return String.format("%s%s/%s/%s/%s-%s-sources.jar",
        MAVEN_CENTRAL_BASE, groupPath, artifactId, version, artifactId, version);
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

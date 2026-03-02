package com.github.noticecollector.notice.source;

import com.github.noticecollector.config.NoticeCollectorConfig;
import com.github.noticecollector.http.HttpClientWrapper;
import com.github.noticecollector.http.HttpRequestException;
import com.github.noticecollector.license.LicensedDependency;
import com.github.noticecollector.notice.NoticeSearchResult;
import com.github.noticecollector.notice.NoticeSearchResult.SearchOutcome;
import com.github.noticecollector.notice.NoticeSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.model.Model;
import org.apache.maven.model.Scm;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 優先順位 6: pom.xml の {@code <scm>} タグから GitHub リポジトリを特定し、GitHub API で NOTICE
 * ファイルを検索する NoticeSource 実装。
 *
 * <p>処理フロー:
 * <ol>
 *   <li>Maven Central から pom.xml を取得し {@code <scm><url>} または {@code <scm><connection>} を解析</li>
 *   <li>GitHub の owner/repo を抽出</li>
 *   <li>GitHub Contents API でバージョンタグの NOTICE ファイルを検索</li>
 * </ol>
 */
public class GitHubSource implements NoticeSource {

  private static final Logger LOG = LoggerFactory.getLogger(GitHubSource.class);

  /** GitHub URL から owner/repo を抽出する正規表現。 */
  static final Pattern GITHUB_URL_PATTERN =
      Pattern.compile("github\\.com[/:]([^/]+)/([^/.]+?)(?:\\.git)?(?:/.*)?$");

  private final HttpClientWrapper httpClient;
  private final String apiBaseUrl;
  private final ObjectMapper objectMapper;

  /**
   * GitHubSource を構築する。
   *
   * @param config 設定オブジェクト
   * @param httpClient HTTP 通信ラッパー
   */
  public GitHubSource(NoticeCollectorConfig config, HttpClientWrapper httpClient) {
    this(config.getGithub().getApiBaseUrl(), httpClient, new ObjectMapper());
  }

  /**
   * テスト用コンストラクタ。
   *
   * @param apiBaseUrl GitHub API ベース URL
   * @param httpClient HTTP 通信ラッパー
   * @param objectMapper JSON パーサー
   */
  GitHubSource(String apiBaseUrl, HttpClientWrapper httpClient, ObjectMapper objectMapper) {
    this.apiBaseUrl = apiBaseUrl != null ? apiBaseUrl : "https://api.github.com";
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
  }

  @Override
  public NoticeSearchResult search(LicensedDependency dependency,
                                   List<String> noticePatterns,
                                   List<String> licensePatterns) {
    String gav = dependency.dependency().toGav();

    // 1. pom.xml から SCM URL を取得
    String scmUrl;
    try {
      scmUrl = fetchScmUrl(dependency);
    } catch (Exception e) {
      LOG.debug("pom.xml の取得/パースに失敗: {} - {}", gav, e.getMessage());
      return new NoticeSearchResult(SearchOutcome.NOT_FOUND, null, null, null,
          "pom.xml から SCM 情報を取得できません: " + e.getMessage());
    }

    if (scmUrl == null) {
      LOG.debug("pom.xml に GitHub SCM URL がありません: {}", gav);
      return new NoticeSearchResult(SearchOutcome.NOT_FOUND, null, null, null,
          "pom.xml に GitHub SCM URL が含まれていません");
    }

    // 2. GitHub owner/repo を抽出
    String[] ownerRepo = extractOwnerRepo(scmUrl);
    if (ownerRepo == null) {
      LOG.debug("GitHub URL のパースに失敗: {} ({})", scmUrl, gav);
      return new NoticeSearchResult(SearchOutcome.NOT_FOUND, null, null, scmUrl,
          "GitHub URL から owner/repo を抽出できません");
    }

    String owner = ownerRepo[0];
    String repo = ownerRepo[1];
    String version = dependency.dependency().version();

    // 3. バージョンタグで NOTICE/LICENSE を検索（タグ候補: v{version}, {version}）
    return searchFilesInRepo(owner, repo, version, noticePatterns, licensePatterns, gav);
  }

  @Override
  public int getPriority() {
    return 6;
  }

  @Override
  public String getSourceName() {
    return "GITHUB";
  }

  /**
   * Maven Central から pom.xml を取得し、{@code <scm>} セクションから GitHub URL を抽出する。
   *
   * @return GitHub URL。見つからない場合は {@code null}
   */
  String fetchScmUrl(LicensedDependency dependency) throws Exception {
    String pomUrl = dependency.dependency().toPomUrl();
    String pomContent = httpClient.getString(pomUrl);

    MavenXpp3Reader reader = new MavenXpp3Reader();
    Model model = reader.read(new StringReader(pomContent));
    Scm scm = model.getScm();
    if (scm == null) {
      return null;
    }

    // <scm><url> を優先、なければ <scm><connection> を使用
    String url = scm.getUrl();
    if (url != null && url.contains("github.com")) {
      return url;
    }
    String connection = scm.getConnection();
    if (connection != null && connection.contains("github.com")) {
      return connection;
    }
    String devConnection = scm.getDeveloperConnection();
    if (devConnection != null && devConnection.contains("github.com")) {
      return devConnection;
    }
    return null;
  }

  /**
   * GitHub URL から owner と repo を抽出する。
   *
   * @param url GitHub URL（HTTPS または SCM 形式）
   * @return {owner, repo} の配列。抽出できない場合は {@code null}
   */
  static String[] extractOwnerRepo(String url) {
    if (url == null) {
      return null;
    }
    // scm:git: プレフィックスを除去
    String cleaned = url;
    if (cleaned.startsWith("scm:git:")) {
      cleaned = cleaned.substring("scm:git:".length());
    }
    if (cleaned.startsWith("scm:git|")) {
      cleaned = cleaned.substring("scm:git|".length());
    }

    Matcher matcher = GITHUB_URL_PATTERN.matcher(cleaned);
    if (matcher.find()) {
      return new String[] {matcher.group(1), matcher.group(2)};
    }
    return null;
  }

  /**
   * GitHub Contents API を使用してリポジトリの NOTICE/LICENSE ファイルを検索する。
   * バージョンタグ候補（v{version}、{version}）を順に試行する。
   */
  private NoticeSearchResult searchFilesInRepo(String owner, String repo, String version,
      List<String> noticePatterns, List<String> licensePatterns, String gav) {
    String[] tagCandidates = {"v" + version, version, repo + "-" + version};

    for (String tag : tagCandidates) {
      NoticeSearchResult result = searchWithTag(owner, repo, tag,
          noticePatterns, licensePatterns, gav);
      if (result.outcome() == SearchOutcome.FOUND) {
        return result;
      }
      if (result.outcome() == SearchOutcome.SOURCE_FOUND_NO_NOTICE) {
        return result;
      }
    }

    // デフォルトブランチ（タグなし）でも試行
    NoticeSearchResult defaultResult = searchWithTag(owner, repo, null,
        noticePatterns, licensePatterns, gav);
    if (defaultResult.outcome() == SearchOutcome.FOUND
        || defaultResult.outcome() == SearchOutcome.SOURCE_FOUND_NO_NOTICE) {
      return defaultResult;
    }

    String repoUrl = String.format("https://github.com/%s/%s", owner, repo);
    return new NoticeSearchResult(SearchOutcome.NOT_FOUND, null, null, repoUrl,
        "GitHub リポジトリから NOTICE/LICENSE が見つかりません");
  }

  /**
   * 特定のタグ（またはデフォルトブランチ）で NOTICE/LICENSE ファイルを検索する。
   */
  private NoticeSearchResult searchWithTag(String owner, String repo, String ref,
      List<String> noticePatterns, List<String> licensePatterns, String gav) {
    String noticeContent = null;
    String noticeSourceUrl = null;
    String licenseContent = null;

    // NOTICE パターンで検索
    for (String pattern : noticePatterns) {
      String result = fetchFileContent(owner, repo, pattern, ref, gav);
      if (result != null) {
        noticeContent = result;
        noticeSourceUrl = String.format("https://github.com/%s/%s/blob/%s/%s",
            owner, repo, ref != null ? ref : "HEAD", pattern);
        break;
      }
    }

    // LICENSE パターンで検索
    for (String pattern : licensePatterns) {
      String result = fetchFileContent(owner, repo, pattern, ref, gav);
      if (result != null) {
        licenseContent = result;
        if (noticeSourceUrl == null) {
          noticeSourceUrl = String.format("https://github.com/%s/%s/blob/%s/%s",
              owner, repo, ref != null ? ref : "HEAD", pattern);
        }
        break;
      }
    }

    if (noticeContent != null || licenseContent != null) {
      LOG.info("GitHub から NOTICE/LICENSE を発見: {} ({})", noticeSourceUrl, gav);
      return new NoticeSearchResult(SearchOutcome.FOUND, noticeContent, licenseContent,
          noticeSourceUrl, null);
    }

    // リポジトリの存在確認
    String repoApiUrl = buildRepoApiUrl(owner, repo);
    try {
      httpClient.getString(repoApiUrl);
      String repoUrl = String.format("https://github.com/%s/%s", owner, repo);
      return new NoticeSearchResult(SearchOutcome.SOURCE_FOUND_NO_NOTICE, null, null, repoUrl,
          "GitHub リポジトリに NOTICE/LICENSE が含まれていません");
    } catch (HttpRequestException e) {
      if (e.getStatusCode() == 404) {
        return new NoticeSearchResult(SearchOutcome.NOT_FOUND, null, null, null,
            "GitHub リポジトリが見つかりません: " + owner + "/" + repo);
      }
      return new NoticeSearchResult(SearchOutcome.ERROR, null, null, null,
          "GitHub API エラー: " + e.getMessage());
    }
  }

  /**
   * GitHub Contents API から単一ファイルの内容を取得する。
   *
   * @return ファイル内容。見つからない場合は {@code null}
   */
  private String fetchFileContent(String owner, String repo, String path, String ref, String gav) {
    String apiUrl = buildContentsApiUrl(owner, repo, path, ref);
    try {
      String json = httpClient.getString(apiUrl);
      JsonNode node = objectMapper.readTree(json);

      JsonNode contentNode = node.get("content");
      if (contentNode != null && !contentNode.isNull()) {
        String encoded = contentNode.asText().replaceAll("\\s", "");
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
      }
    } catch (HttpRequestException e) {
      if (e.getStatusCode() == 404) {
        LOG.debug("GitHub Contents API 404: {} ({})", apiUrl, gav);
      } else if (e.getStatusCode() == 401 || e.getStatusCode() == 403) {
        LOG.debug("GitHub API 認証エラー: {} ({}) - {}", apiUrl, gav, e.getMessage());
      } else {
        LOG.debug("GitHub Contents API エラー: {} ({}) - {}", apiUrl, gav, e.getMessage());
      }
    } catch (Exception e) {
      LOG.debug("GitHub Contents API 処理エラー: {} ({}) - {}", apiUrl, gav, e.getMessage());
    }
    return null;
  }

  /**
   * GitHub Contents API の URL を構築する。
   *
   * <p>形式: {@code {apiBaseUrl}/repos/{owner}/{repo}/contents/{path}?ref={ref}}
   */
  String buildContentsApiUrl(String owner, String repo, String path, String ref) {
    String url = String.format("%s/repos/%s/%s/contents/%s", apiBaseUrl, owner, repo, path);
    if (ref != null) {
      url += "?ref=" + ref;
    }
    return url;
  }

  /**
   * GitHub Repos API の URL を構築する。
   */
  String buildRepoApiUrl(String owner, String repo) {
    return String.format("%s/repos/%s/%s", apiBaseUrl, owner, repo);
  }
}

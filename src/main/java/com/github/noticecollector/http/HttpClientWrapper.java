package com.github.noticecollector.http;

import com.github.noticecollector.config.NoticeCollectorConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Apache HttpClient 5 による HTTP 通信ラッパー。指数バックオフリトライ、GitHub API トークン自動付与、社内リポジトリ認証、HTTPS
 * 強制を提供する。
 */
public class HttpClientWrapper implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(HttpClientWrapper.class);

  private static final int MAX_RETRIES = 3;
  private static final long BASE_DELAY_MS = 500;

  private final CloseableHttpClient httpClient;
  private final String githubToken;
  private final String githubApiBaseUrl;
  private final List<NoticeCollectorConfig.RepositoryConfig> repositories;

  /**
   * 設定から HttpClientWrapper を構築する。
   *
   * @param config 設定オブジェクト
   */
  public HttpClientWrapper(NoticeCollectorConfig config) {
    this(config, HttpClients.createDefault());
  }

  /**
   * テスト用コンストラクタ。HttpClient を外部から注入できる。
   *
   * @param config 設定オブジェクト
   * @param httpClient 使用する HttpClient インスタンス
   */
  public HttpClientWrapper(NoticeCollectorConfig config, CloseableHttpClient httpClient) {
    this.httpClient = httpClient;
    this.repositories = config.getRepositories();
    this.githubApiBaseUrl = config.getGithub().getApiBaseUrl();

    String tokenEnv = config.getGithub().getTokenEnv();
    this.githubToken = (tokenEnv != null) ? System.getenv(tokenEnv) : null;
  }

  /**
   * GET リクエストを送信し、レスポンスボディをバイト配列で返す。
   *
   * @param url リクエスト URL（HTTPS 必須）
   * @return レスポンスボディ
   * @throws HttpRequestException リトライ上限超過時
   */
  public byte[] get(String url) throws HttpRequestException {
    validateHttps(url);
    return executeWithRetry(
        url,
        () -> {
          HttpGet request = new HttpGet(url);
          applyAuth(request, url);
          return httpClient.execute(
              request,
              response -> {
                validateResponse(response, url);
                HttpEntity entity = response.getEntity();
                if (entity == null) {
                  return new byte[0];
                }
                return EntityUtils.toByteArray(entity);
              });
        });
  }

  /**
   * GET リクエストを送信し、レスポンスボディを文字列で返す。
   *
   * @param url リクエスト URL（HTTPS 必須）
   * @return レスポンスボディ
   * @throws HttpRequestException リトライ上限超過時
   */
  public String getString(String url) throws HttpRequestException {
    validateHttps(url);
    return executeWithRetry(
        url,
        () -> {
          HttpGet request = new HttpGet(url);
          applyAuth(request, url);
          return httpClient.execute(
              request,
              response -> {
                validateResponse(response, url);
                HttpEntity entity = response.getEntity();
                if (entity == null) {
                  return "";
                }
                try {
                  return EntityUtils.toString(entity, StandardCharsets.UTF_8);
                } catch (ParseException e) {
                  throw new IOException("Failed to parse response body", e);
                }
              });
        });
  }

  /**
   * GET リクエストを送信し、レスポンスボディをファイルに保存する。
   *
   * @param url リクエスト URL（HTTPS 必須）
   * @param destination 保存先ファイルパス
   * @throws HttpRequestException リトライ上限超過時
   */
  public void downloadToFile(String url, java.nio.file.Path destination)
      throws HttpRequestException {
    byte[] data = get(url);
    try {
      java.nio.file.Files.write(destination, data);
    } catch (IOException e) {
      throw new HttpRequestException("Failed to write file: " + destination, e);
    }
  }

  /**
   * HEAD リクエストでファイルサイズを取得する。
   *
   * @param url リクエスト URL（HTTPS 必須）
   * @return Content-Length（バイト）。不明の場合は -1
   */
  public long getContentLength(String url) {
    try {
      validateHttps(url);
    } catch (HttpRequestException e) {
      LOG.warn("HTTPS validation failed for HEAD request: {}", url);
      return -1;
    }
    try {
      HttpHead request = new HttpHead(url);
      applyAuth(request, url);
      return httpClient.execute(
          request,
          response -> {
            if (response.getCode() >= 400) {
              EntityUtils.consume(response.getEntity());
              return -1L;
            }
            long contentLength =
                response.getFirstHeader("Content-Length") != null
                    ? Long.parseLong(response.getFirstHeader("Content-Length").getValue())
                    : -1L;
            EntityUtils.consume(response.getEntity());
            return contentLength;
          });
    } catch (Exception e) {
      LOG.warn("HEAD request failed for {}: {}", url, e.getMessage());
      return -1;
    }
  }

  @Override
  public void close() throws Exception {
    httpClient.close();
  }

  /**
   * 指数バックオフリトライで処理を実行する。
   *
   * @param url ログ用 URL
   * @param action 実行する処理
   * @param <T> 戻り値の型
   * @return 処理結果
   * @throws HttpRequestException 全リトライ失敗時
   */
  private <T> T executeWithRetry(String url, RetryableAction<T> action)
      throws HttpRequestException {
    Exception lastException = null;
    for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
      try {
        return action.execute();
      } catch (HttpRequestException e) {
        // 4xx クライアントエラーはリトライしない
        if (e.getStatusCode() >= 400 && e.getStatusCode() < 500) {
          throw e;
        }
        lastException = e;
      } catch (HttpResponseException e) {
        // レスポンスハンドラ内からの HTTP エラー
        if (e.getStatusCode() >= 400 && e.getStatusCode() < 500) {
          throw new HttpRequestException(e.getMessage(), e.getStatusCode(), e);
        }
        lastException = e;
      } catch (IOException e) {
        lastException = e;
      }
      if (attempt < MAX_RETRIES) {
        long delay = BASE_DELAY_MS * (1L << (attempt - 1));
        LOG.info("Retry {}/{} for {} after {}ms", attempt, MAX_RETRIES, url, delay);
        try {
          Thread.sleep(delay);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new HttpRequestException("Interrupted during retry backoff", lastException);
        }
      }
    }
    throw new HttpRequestException(
        "All " + MAX_RETRIES + " retries failed for " + url, lastException);
  }

  /** HTTPS プロトコルを強制する。テスト時にオーバーライド可能。 */
  void validateHttps(String url) throws HttpRequestException {
    if (url == null || !url.toLowerCase().startsWith("https://")) {
      throw new HttpRequestException("HTTPS is required, but got: " + url);
    }
  }

  /**
   * レスポンスのステータスコードを検証する。レスポンスハンドラ内で使用するため、HttpRequestException を
   * HttpResponseException でラップしてスローする。
   */
  private void validateResponse(ClassicHttpResponse response, String url) throws IOException {
    int code = response.getCode();
    if (code >= 400) {
      EntityUtils.consume(response.getEntity());
      throw new HttpResponseException(code, url);
    }
  }

  /** URL に応じて認証ヘッダを付与する。 */
  private void applyAuth(org.apache.hc.core5.http.HttpRequest request, String url) {
    // GitHub API トークン
    if (isGitHubUrl(url) && githubToken != null && !githubToken.isEmpty()) {
      request.setHeader("Authorization", "Bearer " + githubToken);
      LOG.debug("Applied GitHub token for {}", url);
      return;
    }

    // 社内リポジトリ認証
    if (repositories == null) {
      return;
    }
    for (NoticeCollectorConfig.RepositoryConfig repo : repositories) {
      if (repo.getUrl() != null && url.startsWith(repo.getUrl()) && repo.getAuth() != null) {
        applyRepoAuth(request, repo.getAuth());
        LOG.debug("Applied repository auth for {} ({})", url, repo.getName());
        return;
      }
    }
  }

  /** GitHub API の URL かどうかを判定する。 */
  private boolean isGitHubUrl(String url) {
    return url.startsWith(githubApiBaseUrl) || url.startsWith("https://api.github.com");
  }

  /** 社内リポジトリの認証ヘッダを付与する。 */
  private void applyRepoAuth(
      org.apache.hc.core5.http.HttpRequest request, NoticeCollectorConfig.AuthConfig auth) {
    String authType = auth.getType();
    if ("bearer".equalsIgnoreCase(authType)) {
      String token = resolveEnv(auth.getTokenEnv());
      if (token != null && !token.isEmpty()) {
        request.setHeader("Authorization", "Bearer " + token);
      }
    } else {
      // Basic 認証（デフォルト）
      String username = resolveEnv(auth.getUsernameEnv());
      String password = resolveEnv(auth.getPasswordEnv());
      if (username != null && password != null) {
        String credentials = username + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        request.setHeader("Authorization", "Basic " + encoded);
      }
    }
  }

  /** 環境変数名から値を取得する。 */
  private String resolveEnv(String envName) {
    if (envName == null || envName.isEmpty()) {
      return null;
    }
    return System.getenv(envName);
  }

  /** リトライ可能な処理を表す関数型インターフェース。 */
  @FunctionalInterface
  interface RetryableAction<T> {
    T execute() throws IOException, HttpRequestException;
  }

  /**
   * レスポンスハンドラ内から HttpRequestException 相当の情報を伝搬するための IOException サブクラス。
   * executeWithRetry で HttpRequestException に変換される。
   */
  static class HttpResponseException extends IOException {
    private final int statusCode;

    HttpResponseException(int statusCode, String url) {
      super("HTTP " + statusCode + " for " + url);
      this.statusCode = statusCode;
    }

    int getStatusCode() {
      return statusCode;
    }
  }
}

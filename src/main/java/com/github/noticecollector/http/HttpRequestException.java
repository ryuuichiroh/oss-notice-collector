package com.github.noticecollector.http;

/** HTTP リクエストがリトライ上限を超過した場合にスローされる例外。 */
public class HttpRequestException extends Exception {

  private final int statusCode;

  public HttpRequestException(String message) {
    super(message);
    this.statusCode = -1;
  }

  public HttpRequestException(String message, Throwable cause) {
    super(message, cause);
    this.statusCode = -1;
  }

  public HttpRequestException(String message, int statusCode) {
    super(message);
    this.statusCode = statusCode;
  }

  public HttpRequestException(String message, int statusCode, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  /** HTTP ステータスコードを返す。不明の場合は -1。 */
  public int getStatusCode() {
    return statusCode;
  }
}

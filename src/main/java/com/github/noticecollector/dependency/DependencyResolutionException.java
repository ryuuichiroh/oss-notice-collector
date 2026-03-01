package com.github.noticecollector.dependency;

/** 依存関係の解決に失敗した場合にスローされる例外。 */
public class DependencyResolutionException extends Exception {

  public DependencyResolutionException(String message) {
    super(message);
  }

  public DependencyResolutionException(String message, Throwable cause) {
    super(message, cause);
  }
}

package com.github.noticecollector.dependency;

import com.github.noticecollector.config.NoticeCollectorConfig;
import java.nio.file.Path;
import java.util.List;

/**
 * プロジェクトの依存関係を解決するインターフェース。
 *
 * <p>ビルドツール（Maven / Gradle）やテキストファイルなど、各種ソースから依存関係リストを取得する。
 */
public interface DependencyResolver {

  /**
   * プロジェクトの依存関係を解決する。
   *
   * @param projectPath プロジェクトのルートディレクトリ
   * @param config 設定（スコープ等）
   * @return 依存関係リスト（BOM はフィルタリング済み）
   * @throws DependencyResolutionException 解決失敗時
   */
  List<Dependency> resolve(Path projectPath, NoticeCollectorConfig config)
      throws DependencyResolutionException;
}

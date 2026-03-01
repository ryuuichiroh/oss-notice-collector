package com.github.noticecollector.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * アーカイブファイルの一時ダウンロードキャッシュを管理するクラス。
 *
 * <p>ライセンス特定時と NOTICE 収集時に同じアーカイブを重複ダウンロードすることを防ぐため、
 * ダウンロードした一時ファイルのパスを GAV（groupId:artifactId:version）をキーとして保持する。
 *
 * <p>使用例:
 * <pre>{@code
 * ArchiveCache cache = new ArchiveCache();
 * // ライセンス特定時にダウンロード
 * Path tempFile = downloadArchive(url);
 * cache.put("com.example:lib:1.0", tempFile);
 *
 * // NOTICE 収集時にキャッシュを確認
 * Optional<Path> cached = cache.get("com.example:lib:1.0");
 * if (cached.isPresent()) {
 *   // キャッシュを使用
 * }
 *
 * // 処理完了後に一括削除
 * cache.clear();
 * }</pre>
 */
public class ArchiveCache {

  private static final Logger LOG = LoggerFactory.getLogger(ArchiveCache.class);

  private final Map<String, Path> cache = new ConcurrentHashMap<>();

  /**
   * アーカイブファイルをキャッシュに登録する。
   *
   * @param key キャッシュキー（通常は GAV: groupId:artifactId:version）
   * @param archivePath ダウンロードした一時ファイルのパス
   */
  public void put(String key, Path archivePath) {
    if (key == null || archivePath == null) {
      return;
    }
    cache.put(key, archivePath);
    LOG.debug("アーカイブをキャッシュに登録: {} -> {}", key, archivePath);
  }

  /**
   * キャッシュからアーカイブファイルを取得する。
   *
   * @param key キャッシュキー（通常は GAV: groupId:artifactId:version）
   * @return キャッシュされたファイルのパス。存在しない場合は null
   */
  public Path get(String key) {
    if (key == null) {
      return null;
    }
    Path cached = cache.get(key);
    if (cached != null) {
      LOG.debug("キャッシュヒット: {} -> {}", key, cached);
    }
    return cached;
  }

  /**
   * 指定されたキーのキャッシュエントリが存在するかを確認する。
   *
   * @param key キャッシュキー
   * @return キャッシュが存在する場合は true
   */
  public boolean contains(String key) {
    return key != null && cache.containsKey(key);
  }

  /**
   * キャッシュされた全ての一時ファイルを削除し、キャッシュをクリアする。
   *
   * <p>NOTICE 収集完了後に呼び出すことで、一時ファイルのクリーンアップを行う。
   */
  public void clear() {
    LOG.debug("アーカイブキャッシュをクリアします（エントリ数: {}）", cache.size());
    for (Map.Entry<String, Path> entry : cache.entrySet()) {
      deleteTempFile(entry.getValue(), entry.getKey());
    }
    cache.clear();
  }

  /**
   * キャッシュされているエントリ数を返す。
   *
   * @return キャッシュエントリ数
   */
  public int size() {
    return cache.size();
  }

  /** 一時ファイルを削除する。 */
  private void deleteTempFile(Path tempFile, String key) {
    if (tempFile != null) {
      try {
        Files.deleteIfExists(tempFile);
        LOG.debug("一時ファイルを削除: {} ({})", tempFile, key);
      } catch (IOException e) {
        LOG.debug("一時ファイルの削除に失敗: {} ({})", tempFile, key, e);
      }
    }
  }
}

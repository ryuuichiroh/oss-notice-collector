package com.github.noticecollector.notice.source;

import com.github.noticecollector.config.NoticeCollectorConfig;
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
 * 優先順位 2: ローカルの Maven リポジトリまたは Gradle キャッシュにあるバイナリ JAR 内から
 * NOTICE ファイルを抽出する NoticeSource 実装。
 */
public class LocalCacheSource implements NoticeSource {

  private static final Logger LOG = LoggerFactory.getLogger(LocalCacheSource.class);

  private final Path mavenLocalRepository;
  private final Path gradleCacheDir;
  private final JarNoticeExtractor jarExtractor;

  /**
   * LocalCacheSource を構築する。
   *
   * @param config 設定オブジェクト
   */
  public LocalCacheSource(NoticeCollectorConfig config) {
    this(config, new JarNoticeExtractor());
  }

  /**
   * テスト用コンストラクタ。JarNoticeExtractor を外部から注入できる。
   *
   * @param config 設定オブジェクト
   * @param jarExtractor JAR 内 NOTICE 抽出ユーティリティ
   */
  public LocalCacheSource(NoticeCollectorConfig config, JarNoticeExtractor jarExtractor) {
    this.jarExtractor = jarExtractor;

    String localRepo = config.getProject().getLocalRepository();
    if (localRepo != null) {
      this.mavenLocalRepository = Path.of(localRepo);
    } else {
      this.mavenLocalRepository = Path.of(System.getProperty("user.home"),
          ".m2", "repository");
    }

    String gradleCache = config.getProject().getGradleCache();
    if (gradleCache != null) {
      this.gradleCacheDir = Path.of(gradleCache);
    } else {
      this.gradleCacheDir = Path.of(System.getProperty("user.home"),
          ".gradle", "caches", "modules-2", "files-2.1");
    }
  }

  @Override
  public NoticeSearchResult search(LicensedDependency dependency, List<String> patterns) {
    // Maven ローカルリポジトリから検索
    NoticeSearchResult mavenResult = searchMavenLocal(dependency, patterns);
    if (mavenResult.outcome() == SearchOutcome.FOUND
        || mavenResult.outcome() == SearchOutcome.SOURCE_FOUND_NO_NOTICE) {
      return mavenResult;
    }

    // Gradle キャッシュから検索
    return searchGradleCache(dependency, patterns);
  }

  @Override
  public int getPriority() {
    return 2;
  }

  @Override
  public String getSourceName() {
    return "LOCAL_CACHE";
  }

  /** Maven ローカルリポジトリの JAR から NOTICE を検索する。 */
  private NoticeSearchResult searchMavenLocal(
      LicensedDependency dependency, List<String> patterns) {
    Path jarPath = dependency.dependency().toLocalJarPath(mavenLocalRepository);
    return extractFromJar(jarPath, patterns, dependency, "Maven ローカルリポジトリ");
  }

  /**
   * Gradle キャッシュから JAR を検索する。Gradle キャッシュは
   * {@code ~/.gradle/caches/modules-2/files-2.1/{group}/{artifact}/{version}/{hash}/{artifact}-{version}.jar}
   * の構造を持つ。ハッシュディレクトリが複数存在する可能性があるため、最初に見つかった JAR を使用する。
   */
  private NoticeSearchResult searchGradleCache(
      LicensedDependency dependency, List<String> patterns) {
    String groupId = dependency.dependency().groupId();
    String artifactId = dependency.dependency().artifactId();
    String version = dependency.dependency().version();
    String jarName = artifactId + "-" + version + ".jar";

    Path artifactDir = gradleCacheDir.resolve(groupId).resolve(artifactId).resolve(version);
    if (!Files.isDirectory(artifactDir)) {
      return new NoticeSearchResult(SearchOutcome.NOT_FOUND, null, null,
          "Gradle キャッシュにディレクトリが見つかりません");
    }

    // ハッシュディレクトリを走査して JAR を探す
    try (var hashDirs = Files.list(artifactDir)) {
      Optional<Path> jarPath = hashDirs
          .filter(Files::isDirectory)
          .map(dir -> dir.resolve(jarName))
          .filter(Files::isRegularFile)
          .findFirst();

      if (jarPath.isEmpty()) {
        return new NoticeSearchResult(SearchOutcome.NOT_FOUND, null, null,
            "Gradle キャッシュに JAR が見つかりません");
      }

      return extractFromJar(jarPath.get(), patterns, dependency, "Gradle キャッシュ");
    } catch (IOException e) {
      LOG.warn("Gradle キャッシュの走査に失敗: {} ({})",
          artifactDir, dependency.dependency().toGav(), e);
      return new NoticeSearchResult(SearchOutcome.ERROR, null, null,
          "Gradle キャッシュ走査エラー: " + e.getMessage());
    }
  }

  /** JAR ファイルから NOTICE を抽出する共通処理。 */
  private NoticeSearchResult extractFromJar(
      Path jarPath, List<String> patterns,
      LicensedDependency dependency, String sourceName) {
    if (!Files.isRegularFile(jarPath)) {
      return new NoticeSearchResult(SearchOutcome.NOT_FOUND, null, null,
          sourceName + "に JAR が見つかりません: " + jarPath);
    }

    try {
      Optional<String> content = jarExtractor.extract(jarPath, patterns);
      if (content.isPresent()) {
        LOG.info("{}の JAR から NOTICE を発見: {} ({})",
            sourceName, jarPath, dependency.dependency().toGav());
        return new NoticeSearchResult(SearchOutcome.FOUND, content.get(),
            jarPath.toString(), null);
      }
      LOG.debug("{}の JAR に NOTICE なし: {} ({})",
          sourceName, jarPath, dependency.dependency().toGav());
      return new NoticeSearchResult(SearchOutcome.SOURCE_FOUND_NO_NOTICE, null,
          jarPath.toString(), sourceName + "の JAR に NOTICE が含まれていません");
    } catch (IOException e) {
      LOG.warn("JAR からの NOTICE 抽出に失敗: {} ({})",
          jarPath, dependency.dependency().toGav(), e);
      return new NoticeSearchResult(SearchOutcome.ERROR, null, jarPath.toString(),
          "JAR 読込エラー: " + e.getMessage());
    }
  }
}

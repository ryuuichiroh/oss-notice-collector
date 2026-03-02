package com.github.noticecollector.notice.source;

import com.github.noticecollector.config.NoticeCollectorConfig;
import com.github.noticecollector.config.NoticeCollectorConfig.SourceRepoConfig;
import com.github.noticecollector.license.LicensedDependency;
import com.github.noticecollector.notice.NoticeSearchResult;
import com.github.noticecollector.notice.NoticeSearchResult.SearchOutcome;
import com.github.noticecollector.notice.NoticeSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 優先順位 7: JGit によるユーザ指定ソースコードリポジトリからの NOTICE 取得を行う NoticeSource 実装。
 *
 * <p>設定ファイルの {@code sourceRepositories} セクションで指定されたリポジトリを shallow clone し、
 * バージョンタグに対応するコミットから NOTICE ファイルを検索する。
 */
public class CustomRepoSource implements NoticeSource {

  private static final Logger LOG = LoggerFactory.getLogger(CustomRepoSource.class);

  private final List<SourceRepoConfig> sourceRepositories;

  /**
   * CustomRepoSource を構築する。
   *
   * @param config 設定オブジェクト
   */
  public CustomRepoSource(NoticeCollectorConfig config) {
    this.sourceRepositories = config.getSourceRepositories();
  }

  /**
   * テスト用コンストラクタ。
   *
   * @param sourceRepositories ソースリポジトリ設定リスト
   */
  CustomRepoSource(List<SourceRepoConfig> sourceRepositories) {
    this.sourceRepositories = sourceRepositories;
  }

  @Override
  public NoticeSearchResult search(LicensedDependency dependency,
                                   List<String> noticePatterns,
                                   List<String> licensePatterns) {
    String gav = dependency.dependency().toGav();

    SourceRepoConfig repoConfig = findRepoConfig(dependency);
    if (repoConfig == null) {
      return new NoticeSearchResult(SearchOutcome.NOT_FOUND, null, null, null,
          "ソースリポジトリ設定が見つかりません");
    }

    String repoUrl = repoConfig.getRepoUrl();
    String version = dependency.dependency().version();

    Path tempDir = null;
    try {
      tempDir = Files.createTempDirectory("notice-repo-");
      return cloneAndSearch(repoUrl, version, noticePatterns, licensePatterns, tempDir, gav);
    } catch (IOException e) {
      LOG.warn("一時ディレクトリの作成に失敗: {}", gav, e);
      return new NoticeSearchResult(SearchOutcome.ERROR, null, null, repoUrl,
          "一時ディレクトリ作成エラー: " + e.getMessage());
    } finally {
      deleteTempDir(tempDir);
    }
  }

  @Override
  public int getPriority() {
    return 7;
  }

  @Override
  public String getSourceName() {
    return "CUSTOM_REPO";
  }

  /**
   * 依存関係に一致するソースリポジトリ設定を検索する。
   */
  private SourceRepoConfig findRepoConfig(LicensedDependency dependency) {
    if (sourceRepositories == null) {
      return null;
    }
    String groupId = dependency.dependency().groupId();
    String artifactId = dependency.dependency().artifactId();

    return sourceRepositories.stream()
        .filter(r -> groupId.equals(r.getGroupId()) && artifactId.equals(r.getArtifactId()))
        .findFirst()
        .orElse(null);
  }

  /**
   * リポジトリを clone し、バージョンタグから NOTICE ファイルを検索する。
   */
  private NoticeSearchResult cloneAndSearch(String repoUrl, String version,
      List<String> noticePatterns, List<String> licensePatterns,
      Path tempDir, String gav) {
    try (Git git = Git.cloneRepository()
        .setURI(repoUrl)
        .setDirectory(tempDir.toFile())
        .setNoCheckout(true)
        .setDepth(1)
        .call()) {

      Repository repository = git.getRepository();

      // バージョンタグ候補を順に試行
      String[] tagCandidates = {"v" + version, version};
      for (String tag : tagCandidates) {
        Optional<String> noticeContent = searchInTag(repository, tag, noticePatterns);
        Optional<String> licenseContent = searchInTag(repository, tag, licensePatterns);
        if (noticeContent.isPresent() || licenseContent.isPresent()) {
          String sourceUrl = repoUrl + " (tag: " + tag + ")";
          LOG.info("カスタムリポジトリから NOTICE/LICENSE を発見: {} ({})", sourceUrl, gav);
          return new NoticeSearchResult(SearchOutcome.FOUND,
              noticeContent.orElse(null),
              licenseContent.orElse(null),
              sourceUrl, null);
        }
      }

      // タグが見つからない場合は HEAD で検索
      Optional<String> headNotice = searchInHead(repository, noticePatterns);
      Optional<String> headLicense = searchInHead(repository, licensePatterns);
      if (headNotice.isPresent() || headLicense.isPresent()) {
        LOG.info("カスタムリポジトリ HEAD から NOTICE/LICENSE を発見: {} ({})", repoUrl, gav);
        return new NoticeSearchResult(SearchOutcome.FOUND,
            headNotice.orElse(null),
            headLicense.orElse(null),
            repoUrl + " (HEAD)", null);
      }

      LOG.debug("カスタムリポジトリに NOTICE/LICENSE なし: {} ({})", repoUrl, gav);
      return new NoticeSearchResult(SearchOutcome.SOURCE_FOUND_NO_NOTICE, null, null, repoUrl,
          "カスタムリポジトリに NOTICE/LICENSE が含まれていません");

    } catch (GitAPIException e) {
      LOG.debug("リポジトリの clone に失敗: {} ({}) - {}", repoUrl, gav, e.getMessage());
      return new NoticeSearchResult(SearchOutcome.ERROR, null, null, repoUrl,
          "リポジトリ clone エラー: " + e.getMessage());
    }
  }

  /**
   * 指定タグのツリーから NOTICE ファイルを検索する。
   */
  private Optional<String> searchInTag(Repository repository, String tagName,
      List<String> patterns) {
    try {
      Ref tagRef = repository.findRef("refs/tags/" + tagName);
      if (tagRef == null) {
        return Optional.empty();
      }
      ObjectId commitId = tagRef.getPeeledObjectId();
      if (commitId == null) {
        commitId = tagRef.getObjectId();
      }
      return searchInCommit(repository, commitId, patterns);
    } catch (IOException e) {
      LOG.debug("タグ {} の検索に失敗: {}", tagName, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * HEAD コミットのツリーから NOTICE ファイルを検索する。
   */
  private Optional<String> searchInHead(Repository repository, List<String> patterns) {
    try {
      ObjectId headId = repository.resolve("HEAD");
      if (headId == null) {
        return Optional.empty();
      }
      return searchInCommit(repository, headId, patterns);
    } catch (IOException e) {
      LOG.debug("HEAD の検索に失敗: {}", e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * 指定コミットのツリーから NOTICE ファイルを検索する。
   */
  private Optional<String> searchInCommit(Repository repository, ObjectId commitId,
      List<String> patterns) throws IOException {
    try (RevWalk revWalk = new RevWalk(repository)) {
      RevCommit commit = revWalk.parseCommit(commitId);
      RevTree tree = commit.getTree();

      for (String pattern : patterns) {
        Optional<String> content = findFileInTree(repository, tree, pattern);
        if (content.isPresent()) {
          return content;
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Git ツリーから指定パスのファイルを検索し、内容を返す。case-insensitive マッチング。
   */
  private Optional<String> findFileInTree(Repository repository, RevTree tree, String pattern)
      throws IOException {
    // まず完全一致で検索
    try (TreeWalk treeWalk = new TreeWalk(repository)) {
      treeWalk.addTree(tree);
      treeWalk.setRecursive(true);
      treeWalk.setFilter(PathFilter.create(pattern));

      if (treeWalk.next()) {
        ObjectId objectId = treeWalk.getObjectId(0);
        ObjectLoader loader = repository.open(objectId);
        String content = new String(loader.getBytes(), StandardCharsets.UTF_8);
        return Optional.of(content);
      }
    }

    // case-insensitive フォールバック
    try (TreeWalk treeWalk = new TreeWalk(repository)) {
      treeWalk.addTree(tree);
      treeWalk.setRecursive(true);

      while (treeWalk.next()) {
        if (treeWalk.getPathString().equalsIgnoreCase(pattern)) {
          ObjectId objectId = treeWalk.getObjectId(0);
          ObjectLoader loader = repository.open(objectId);
          String content = new String(loader.getBytes(), StandardCharsets.UTF_8);
          return Optional.of(content);
        }
      }
    }

    return Optional.empty();
  }

  /** 一時ディレクトリを再帰的に削除する。 */
  private void deleteTempDir(Path dir) {
    if (dir == null) {
      return;
    }
    try {
      Files.walk(dir)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(path -> {
            try {
              Files.deleteIfExists(path);
            } catch (IOException e) {
              LOG.debug("一時ファイルの削除に失敗: {}", path, e);
            }
          });
    } catch (IOException e) {
      LOG.debug("一時ディレクトリの削除に失敗: {}", dir, e);
    }
  }
}

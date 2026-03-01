package com.github.noticecollector.dependency;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;

/**
 * {@code groupId:artifactId:version} で表される依存関係モデル。
 *
 * @param groupId Maven groupId
 * @param artifactId Maven artifactId
 * @param version バージョン文字列
 * @param scope 依存スコープ（compile, runtime, provided 等）
 * @param packaging パッケージング種別（jar, pom 等）
 */
public record Dependency(
    String groupId, String artifactId, String version, String scope, String packaging) {

  /**
   * groupId・artifactId・version が非 null・非空であることを検証するコンパクトコンストラクタ。
   */
  public Dependency {
    Objects.requireNonNull(groupId, "groupId must not be null");
    Objects.requireNonNull(artifactId, "artifactId must not be null");
    Objects.requireNonNull(version, "version must not be null");
    if (groupId.isBlank()) {
      throw new IllegalArgumentException("groupId must not be blank");
    }
    if (artifactId.isBlank()) {
      throw new IllegalArgumentException("artifactId must not be blank");
    }
    if (version.isBlank()) {
      throw new IllegalArgumentException("version must not be blank");
    }
  }

  /** GAV 座標文字列を返す（例: {@code "org.apache:commons-lang3:3.14.0"}）。 */
  public String toGav() {
    return groupId + ":" + artifactId + ":" + version;
  }

  /**
   * {@code groupId:artifactId:version} 形式の GAV 文字列をパースして {@link Dependency} を生成する。
   *
   * @param gav GAV 座標文字列
   * @return パース結果の Dependency（scope と packaging は null）
   * @throws IllegalArgumentException GAV 形式が不正な場合
   */
  public static Dependency fromGav(String gav) {
    Objects.requireNonNull(gav, "gav must not be null");
    String[] parts = gav.split(":");
    if (parts.length != 3) {
      throw new IllegalArgumentException(
          "Invalid GAV format (expected groupId:artifactId:version): " + gav);
    }
    return new Dependency(parts[0], parts[1], parts[2], null, null);
  }

  /** Maven Central の pom.xml URL を生成する。 */
  public String toPomUrl() {
    String groupPath = groupId.replace('.', '/');
    return String.format(
        "https://repo1.maven.org/maven2/%s/%s/%s/%s-%s.pom",
        groupPath, artifactId, version, artifactId, version);
  }

  /**
   * Maven ローカルリポジトリ内の JAR パスを生成する。
   *
   * @param localRepository ローカルリポジトリのルートパス
   * @return JAR ファイルのパス
   */
  public Path toLocalJarPath(Path localRepository) {
    Objects.requireNonNull(localRepository, "localRepository must not be null");
    String groupPath = groupId.replace('.', File.separatorChar);
    return localRepository
        .resolve(groupPath)
        .resolve(artifactId)
        .resolve(version)
        .resolve(artifactId + "-" + version + ".jar");
  }

  /** BOM（pom-only）かどうかを判定する。 */
  public boolean isBom() {
    return "pom".equals(packaging);
  }
}

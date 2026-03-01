package com.github.noticecollector.dependency;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link MavenDependencyResolver} のテスト。
 */
class MavenDependencyResolverTest {

  @TempDir
  Path tempDir;

  private MavenDependencyResolver resolver;
  private String originalOsName;

  @BeforeEach
  void setUp() {
    resolver = new MavenDependencyResolver();
    originalOsName = System.getProperty("os.name");
  }

  @AfterEach
  void tearDown() {
    // OS名を元に戻す
    System.setProperty("os.name", originalOsName);
  }

  @Test
  void resolveMavenCommand_WithMvnwCmd_OnWindows() throws IOException {
    // Windows環境をシミュレート
    System.setProperty("os.name", "Windows 11");
    
    // mvnw.cmdを作成
    Path mvnwCmd = tempDir.resolve("mvnw.cmd");
    Files.createFile(mvnwCmd);

    String result = resolver.resolveMavenCommand(tempDir);

    assertEquals(mvnwCmd.toAbsolutePath().toString(), result);
  }

  @Test
  void resolveMavenCommand_WithMvnw_OnLinux() throws IOException {
    // Linux環境をシミュレート
    System.setProperty("os.name", "Linux");
    
    // mvnwを作成
    Path mvnw = tempDir.resolve("mvnw");
    Files.createFile(mvnw);

    String result = resolver.resolveMavenCommand(tempDir);

    assertEquals(mvnw.toAbsolutePath().toString(), result);
  }

  @Test
  void resolveMavenCommand_WithMvnw_OnMacOS() throws IOException {
    // macOS環境をシミュレート
    System.setProperty("os.name", "Mac OS X");
    
    // mvnwを作成
    Path mvnw = tempDir.resolve("mvnw");
    Files.createFile(mvnw);

    String result = resolver.resolveMavenCommand(tempDir);

    assertEquals(mvnw.toAbsolutePath().toString(), result);
  }

  @Test
  void resolveMavenCommand_WithoutWrapper_OnWindows() {
    // Windows環境をシミュレート
    System.setProperty("os.name", "Windows 11");

    String result = resolver.resolveMavenCommand(tempDir);

    assertEquals("mvn.cmd", result);
  }

  @Test
  void resolveMavenCommand_WithoutWrapper_OnLinux() {
    // Linux環境をシミュレート
    System.setProperty("os.name", "Linux");

    String result = resolver.resolveMavenCommand(tempDir);

    assertEquals("mvn", result);
  }

  @Test
  void resolveMavenCommand_WithoutWrapper_OnMacOS() {
    // macOS環境をシミュレート
    System.setProperty("os.name", "Mac OS X");

    String result = resolver.resolveMavenCommand(tempDir);

    assertEquals("mvn", result);
  }

  @Test
  void resolveMavenCommand_PrefersWrapperOverSystemCommand_OnWindows() throws IOException {
    // Windows環境をシミュレート
    System.setProperty("os.name", "Windows 11");
    
    // mvnw.cmdを作成
    Path mvnwCmd = tempDir.resolve("mvnw.cmd");
    Files.createFile(mvnwCmd);

    String result = resolver.resolveMavenCommand(tempDir);

    // システムのmvn.cmdではなく、mvnw.cmdが選択される
    assertEquals(mvnwCmd.toAbsolutePath().toString(), result);
    assertNotEquals("mvn.cmd", result);
  }

  @Test
  void resolveMavenCommand_PrefersWrapperOverSystemCommand_OnLinux() throws IOException {
    // Linux環境をシミュレート
    System.setProperty("os.name", "Linux");
    
    // mvnwを作成
    Path mvnw = tempDir.resolve("mvnw");
    Files.createFile(mvnw);

    String result = resolver.resolveMavenCommand(tempDir);

    // システムのmvnではなく、mvnwが選択される
    assertEquals(mvnw.toAbsolutePath().toString(), result);
    assertNotEquals("mvn", result);
  }

  @Test
  void resolveMavenCommand_IgnoresMvnwOnWindows() throws IOException {
    // Windows環境をシミュレート
    System.setProperty("os.name", "Windows 11");
    
    // Unix用のmvnwのみ存在（mvnw.cmdは存在しない）
    Path mvnw = tempDir.resolve("mvnw");
    Files.createFile(mvnw);

    String result = resolver.resolveMavenCommand(tempDir);

    // Windowsではmvnw.cmdを探すので、mvnwは無視してmvn.cmdにフォールバック
    assertEquals("mvn.cmd", result);
  }
}

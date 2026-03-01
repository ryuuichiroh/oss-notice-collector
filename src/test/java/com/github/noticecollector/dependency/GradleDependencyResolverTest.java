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
 * {@link GradleDependencyResolver} のテスト。
 */
class GradleDependencyResolverTest {

  @TempDir
  Path tempDir;

  private GradleDependencyResolver resolver;
  private String originalOsName;

  @BeforeEach
  void setUp() {
    resolver = new GradleDependencyResolver();
    originalOsName = System.getProperty("os.name");
  }

  @AfterEach
  void tearDown() {
    // OS名を元に戻す
    System.setProperty("os.name", originalOsName);
  }

  @Test
  void resolveGradleCommand_WithGradlewBat_OnWindows() throws IOException {
    // Windows環境をシミュレート
    System.setProperty("os.name", "Windows 11");
    
    // gradlew.batを作成
    Path gradlewBat = tempDir.resolve("gradlew.bat");
    Files.createFile(gradlewBat);

    String result = resolver.resolveGradleCommand(tempDir);

    assertEquals(gradlewBat.toAbsolutePath().toString(), result);
  }

  @Test
  void resolveGradleCommand_WithGradlew_OnLinux() throws IOException {
    // Linux環境をシミュレート
    System.setProperty("os.name", "Linux");
    
    // gradlewを作成
    Path gradlew = tempDir.resolve("gradlew");
    Files.createFile(gradlew);

    String result = resolver.resolveGradleCommand(tempDir);

    assertEquals(gradlew.toAbsolutePath().toString(), result);
  }

  @Test
  void resolveGradleCommand_WithGradlew_OnMacOS() throws IOException {
    // macOS環境をシミュレート
    System.setProperty("os.name", "Mac OS X");
    
    // gradlewを作成
    Path gradlew = tempDir.resolve("gradlew");
    Files.createFile(gradlew);

    String result = resolver.resolveGradleCommand(tempDir);

    assertEquals(gradlew.toAbsolutePath().toString(), result);
  }

  @Test
  void resolveGradleCommand_WithoutWrapper_OnWindows() {
    // Windows環境をシミュレート
    System.setProperty("os.name", "Windows 11");

    String result = resolver.resolveGradleCommand(tempDir);

    assertEquals("gradle", result);
  }

  @Test
  void resolveGradleCommand_WithoutWrapper_OnLinux() {
    // Linux環境をシミュレート
    System.setProperty("os.name", "Linux");

    String result = resolver.resolveGradleCommand(tempDir);

    assertEquals("gradle", result);
  }

  @Test
  void resolveGradleCommand_WithoutWrapper_OnMacOS() {
    // macOS環境をシミュレート
    System.setProperty("os.name", "Mac OS X");

    String result = resolver.resolveGradleCommand(tempDir);

    assertEquals("gradle", result);
  }

  @Test
  void resolveGradleCommand_PrefersWrapperOverSystemCommand_OnWindows() throws IOException {
    // Windows環境をシミュレート
    System.setProperty("os.name", "Windows 11");
    
    // gradlew.batを作成
    Path gradlewBat = tempDir.resolve("gradlew.bat");
    Files.createFile(gradlewBat);

    String result = resolver.resolveGradleCommand(tempDir);

    // システムのgradleではなく、gradlew.batが選択される
    assertEquals(gradlewBat.toAbsolutePath().toString(), result);
    assertNotEquals("gradle", result);
  }

  @Test
  void resolveGradleCommand_PrefersWrapperOverSystemCommand_OnLinux() throws IOException {
    // Linux環境をシミュレート
    System.setProperty("os.name", "Linux");
    
    // gradlewを作成
    Path gradlew = tempDir.resolve("gradlew");
    Files.createFile(gradlew);

    String result = resolver.resolveGradleCommand(tempDir);

    // システムのgradleではなく、gradlewが選択される
    assertEquals(gradlew.toAbsolutePath().toString(), result);
    assertNotEquals("gradle", result);
  }

  @Test
  void resolveGradleCommand_IgnoresGradlewOnWindows() throws IOException {
    // Windows環境をシミュレート
    System.setProperty("os.name", "Windows 11");
    
    // Unix用のgradlewのみ存在（gradlew.batは存在しない）
    Path gradlew = tempDir.resolve("gradlew");
    Files.createFile(gradlew);

    String result = resolver.resolveGradleCommand(tempDir);

    // Windowsではgradlew.batを探すので、gradlewは無視してgradleにフォールバック
    assertEquals("gradle", result);
  }
}

package com.github.noticecollector.http;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.noticecollector.config.NoticeCollectorConfig;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.Test;

/**
 * HttpClientWrapper のユニットテスト。WireMock を使用して HTTP レスポンスをモックする。テスト用サブクラスで HTTPS
 * 検証をバイパスする。
 */
@WireMockTest
class HttpClientWrapperTest {

  /** テスト用: HTTPS 検証をスキップするサブクラス。 */
  static class TestableWrapper extends HttpClientWrapper {
    TestableWrapper(NoticeCollectorConfig config) {
      super(config, HttpClients.createDefault());
    }

    @Override
    void validateHttps(String url) {
      // テスト時は HTTP を許可
    }
  }

  private NoticeCollectorConfig createConfig() {
    NoticeCollectorConfig config = new NoticeCollectorConfig();
    // GitHub トークン環境変数を未設定の名前にしてテスト時に null になるようにする
    config.getGithub().setTokenEnv("TEST_NONEXISTENT_TOKEN");
    return config;
  }

  @Test
  void getReturnsBodyAsBytes(WireMockRuntimeInfo wmInfo) throws Exception {
    stubFor(get("/test").willReturn(ok().withBody("hello")));

    try (TestableWrapper wrapper = new TestableWrapper(createConfig())) {
      byte[] result = wrapper.get(wmInfo.getHttpBaseUrl() + "/test");
      assertArrayEquals("hello".getBytes(), result);
    }
  }

  @Test
  void getStringReturnsBodyAsString(WireMockRuntimeInfo wmInfo) throws Exception {
    stubFor(get("/text").willReturn(ok().withBody("response text")));

    try (TestableWrapper wrapper = new TestableWrapper(createConfig())) {
      String result = wrapper.getString(wmInfo.getHttpBaseUrl() + "/text");
      assertEquals("response text", result);
    }
  }

  @Test
  void getContentLengthReturnsSize(WireMockRuntimeInfo wmInfo) throws Exception {
    stubFor(head(urlEqualTo("/file")).willReturn(ok().withHeader("Content-Length", "12345")));

    try (TestableWrapper wrapper = new TestableWrapper(createConfig())) {
      long size = wrapper.getContentLength(wmInfo.getHttpBaseUrl() + "/file");
      assertEquals(12345L, size);
    }
  }

  @Test
  void getContentLengthReturnsMinusOneWhenMissing(WireMockRuntimeInfo wmInfo) throws Exception {
    stubFor(head(urlEqualTo("/nosize")).willReturn(ok()));

    try (TestableWrapper wrapper = new TestableWrapper(createConfig())) {
      long size = wrapper.getContentLength(wmInfo.getHttpBaseUrl() + "/nosize");
      assertEquals(-1L, size);
    }
  }

  @Test
  void getContentLengthReturnsMinusOneOn404(WireMockRuntimeInfo wmInfo) throws Exception {
    stubFor(head(urlEqualTo("/missing")).willReturn(notFound()));

    try (TestableWrapper wrapper = new TestableWrapper(createConfig())) {
      long size = wrapper.getContentLength(wmInfo.getHttpBaseUrl() + "/missing");
      assertEquals(-1L, size);
    }
  }

  @Test
  void retriesOnServerError(WireMockRuntimeInfo wmInfo) throws Exception {
    stubFor(
        get("/retry")
            .inScenario("retry")
            .whenScenarioStateIs("Started")
            .willReturn(serverError())
            .willSetStateTo("second"));
    stubFor(
        get("/retry")
            .inScenario("retry")
            .whenScenarioStateIs("second")
            .willReturn(serverError())
            .willSetStateTo("third"));
    stubFor(
        get("/retry")
            .inScenario("retry")
            .whenScenarioStateIs("third")
            .willReturn(ok().withBody("success")));

    try (TestableWrapper wrapper = new TestableWrapper(createConfig())) {
      String result = wrapper.getString(wmInfo.getHttpBaseUrl() + "/retry");
      assertEquals("success", result);
    }
    verify(3, getRequestedFor(urlEqualTo("/retry")));
  }

  @Test
  void throwsAfterAllRetriesFail(WireMockRuntimeInfo wmInfo) {
    stubFor(get("/fail").willReturn(serverError()));

    try (TestableWrapper wrapper = new TestableWrapper(createConfig())) {
      HttpRequestException ex =
          assertThrows(
              HttpRequestException.class,
              () -> wrapper.getString(wmInfo.getHttpBaseUrl() + "/fail"));
      assertTrue(ex.getMessage().contains("All 3 retries failed"));
    } catch (Exception e) {
      fail("Unexpected exception: " + e);
    }
    verify(3, getRequestedFor(urlEqualTo("/fail")));
  }

  @Test
  void doesNotRetryOn4xxClientError(WireMockRuntimeInfo wmInfo) {
    stubFor(get("/notfound").willReturn(notFound()));

    try (TestableWrapper wrapper = new TestableWrapper(createConfig())) {
      HttpRequestException ex =
          assertThrows(
              HttpRequestException.class,
              () -> wrapper.getString(wmInfo.getHttpBaseUrl() + "/notfound"));
      assertEquals(404, ex.getStatusCode());
    } catch (Exception e) {
      fail("Unexpected exception: " + e);
    }
    verify(1, getRequestedFor(urlEqualTo("/notfound")));
  }

  @Test
  void httpsValidationRejectsHttpUrl() {
    HttpClientWrapper wrapper = new HttpClientWrapper(createConfig());
    HttpRequestException ex =
        assertThrows(
            HttpRequestException.class, () -> wrapper.get("http://example.com/test"));
    assertTrue(ex.getMessage().contains("HTTPS is required"));
  }

  @Test
  void httpsValidationRejectsNullUrl() {
    HttpClientWrapper wrapper = new HttpClientWrapper(createConfig());
    HttpRequestException ex =
        assertThrows(HttpRequestException.class, () -> wrapper.get(null));
    assertTrue(ex.getMessage().contains("HTTPS is required"));
  }
}

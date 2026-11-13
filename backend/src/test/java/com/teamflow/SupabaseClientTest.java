package com.teamflow;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.teamflow.auth.SupabaseClient;
import com.teamflow.common.ApiException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class SupabaseClientTest {
  @Test
  void rejectsPrivateKeysAndUnsafeOriginsWithoutLeakingInputs() {
    for (String key :
        new String[] {"", "sb_secret_private-value", "wrong-key", legacy("service_role")})
      assertThatThrownBy(() -> new SupabaseClient("https://project.supabase.co", key))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining("private-value");
    for (String url :
        new String[] {
          "http://remote.example",
          "https://user:private-value@project.supabase.co",
          "https://project.supabase.co?private-value=1",
          "https://project.supabase.co/auth/v1",
          "https://project.supabase.co/#private-value"
        })
      assertThatThrownBy(() -> new SupabaseClient(url, "sb_publishable_test"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining("private-value");
    assertThat(new SupabaseClient("https://project.supabase.co/", legacy("anon")).issuer())
        .isEqualTo("https://project.supabase.co/auth/v1");
  }

  @Test
  void providerErrorsAreSanitizedAndUnavailableFailsClosed() throws Exception {
    var provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    provider.createContext(
        "/auth/v1/",
        exchange -> {
          int code =
              Integer.parseInt(exchange.getRequestURI().getPath().substring("/auth/v1/".length()));
          byte[] bytes = "{\"msg\":\"private-provider-detail\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(code, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    provider.start();
    var client =
        new SupabaseClient(
            "http://127.0.0.1:" + provider.getAddress().getPort(), "sb_publishable_test");
    try {
      for (int code : new int[] {401, 429, 500}) {
        int expected = code == 500 ? 503 : code;
        assertThatThrownBy(() -> client.request(HttpMethod.GET, "/" + code, null, "test-token"))
            .isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.status().value()).isEqualTo(expected))
            .hasMessageNotContaining("private-provider-detail")
            .hasMessageNotContaining("test-token");
      }
    } finally {
      provider.stop(0);
    }
    assertThatThrownBy(() -> client.request(HttpMethod.GET, "/user", null, "test-token"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status().value()).isEqualTo(503));
  }

  private String legacy(String role) {
    return "eyJhbGciOiJIUzI1NiJ9."
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(("{\"role\":\"" + role + "\"}").getBytes(StandardCharsets.UTF_8))
        + ".test";
  }
}

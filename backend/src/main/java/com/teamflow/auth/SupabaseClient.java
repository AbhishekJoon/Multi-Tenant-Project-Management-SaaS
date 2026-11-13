package com.teamflow.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.teamflow.common.ApiException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

/** Uses the public Auth API only. No admin key or access to managed password hashes. */
@Component
@ConditionalOnProperty(name = "teamflow.auth.provider", havingValue = "supabase")
public class SupabaseClient {
  private final RestClient client;
  private final String issuer;

  public SupabaseClient(
      @Value("${teamflow.auth.supabase-url}") String url,
      @Value("${teamflow.auth.supabase-key}") String key) {
    URI uri;
    try {
      uri = URI.create(url);
    } catch (RuntimeException e) {
      throw new IllegalStateException("Invalid SUPABASE_URL");
    }
    boolean loopback = "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost());
    if ((!"https".equals(uri.getScheme()) && !loopback)
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
      throw new IllegalStateException("SUPABASE_URL must be the HTTPS project origin");
    if (key == null || key.isBlank() || key.startsWith("sb_secret_"))
      throw new IllegalStateException(
          "Set SUPABASE_PUBLISHABLE_KEY to the public publishable or anon key");
    if (!key.startsWith("sb_publishable_") && !key.startsWith("eyJ"))
      throw new IllegalStateException(
          "SUPABASE_PUBLISHABLE_KEY must be a publishable or legacy anon key");
    if (key.startsWith("eyJ")) {
      try {
        var claims =
            new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(java.util.Base64.getUrlDecoder().decode(key.split("\\.")[1]));
        if (!"anon".equals(claims.path("role").asText())) throw new IllegalArgumentException();
      } catch (Exception e) {
        throw new IllegalStateException(
            "Use a public publishable or anon key, never a service_role key");
      }
    }
    issuer = url.replaceAll("/$", "") + "/auth/v1";
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    client =
        RestClient.builder()
            .baseUrl(issuer)
            .requestFactory(factory)
            .defaultHeader("apikey", key)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            .build();
  }

  public String issuer() {
    return issuer;
  }

  public JsonNode request(HttpMethod method, String path, Map<String, ?> body, String token) {
    try {
      var request = client.method(method).uri(path);
      if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
      if (body != null) request.body(body);
      return request.retrieve().body(JsonNode.class);
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      if (status == 429)
        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Please wait before trying again");
      if (status >= 500 || status == 404) throw unavailable();
      // Provider responses can contain account details. Never forward or log the raw response.
      if (path.startsWith("/token") || method == HttpMethod.GET || path.startsWith("/logout"))
        throw ApiException.unauthenticated();
      throw ApiException.invalid(
          "Supabase could not complete this request. Check the details or request a new email"
              + " link.");
    } catch (RestClientException e) {
      throw unavailable();
    }
  }

  private ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Sign-in service is temporarily unavailable. Please try again.");
  }
}

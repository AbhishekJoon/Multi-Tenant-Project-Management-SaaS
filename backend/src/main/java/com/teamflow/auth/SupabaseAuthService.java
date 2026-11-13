package com.teamflow.auth;

import com.fasterxml.jackson.databind.*;
import com.teamflow.audit.AuditService;
import com.teamflow.common.ApiException;
import com.teamflow.security.Actor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "teamflow.auth.provider", havingValue = "supabase")
public class SupabaseAuthService {
  private final SupabaseClient client;
  private final SupabaseIdentities identities;
  private final ObjectMapper json;
  private final AuditService audit;

  public SupabaseAuthService(
      SupabaseClient client, SupabaseIdentities identities, ObjectMapper json, AuditService audit) {
    this.client = client;
    this.identities = identities;
    this.json = json;
    this.audit = audit;
  }

  public Actor authenticate(String token) {
    var profile = profile(token);
    return new Actor(profile.id(), profile.email(), profile.name());
  }

  public AuthService.Profile profile(String token) {
    if (token == null || token.length() > 12000) throw ApiException.unauthenticated();
    // Supabase's /user endpoint verifies the signature with the project's current keys.
    // This also supports projects still using HS256 without sharing their signing secret.
    JsonNode user = client.request(HttpMethod.GET, "/user", null, token);
    try {
      String[] parts = token.split("\\.", -1);
      if (parts.length != 3) throw ApiException.unauthenticated();
      JsonNode claims =
          json.readTree(
              new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
      boolean audience = "authenticated".equals(claims.path("aud").asText());
      if (claims.path("aud").isArray())
        for (JsonNode value : claims.path("aud"))
          audience |= "authenticated".equals(value.asText());
      if (!client.issuer().equals(claims.path("iss").asText())
          || !audience
          || !"authenticated".equals(claims.path("role").asText())
          || claims.path("exp").asLong() <= Instant.now().getEpochSecond()
          || claims.path("nbf").asLong() > Instant.now().getEpochSecond()
          || !user.path("id").asText().equals(claims.path("sub").asText()))
        throw ApiException.unauthenticated();
      UUID subject = UUID.fromString(claims.path("sub").asText());
      UUID session = UUID.fromString(claims.path("session_id").asText());
      if (!identities.sessionActive(session, subject)) throw ApiException.unauthenticated();
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw ApiException.unauthenticated();
    }
    // Only provider-confirmed email can adopt a legacy application identity.
    return identities.synchronize(user);
  }

  public AuthService.Session session(JsonNode response) {
    String access = response.path("access_token").asText();
    String refresh = response.path("refresh_token").asText();
    if (access.isBlank() || refresh.isBlank() || refresh.length() > 2000)
      throw ApiException.unauthenticated();
    return new AuthService.Session(access, refresh, profile(access));
  }

  public AuthService.Session login(String email, String password) {
    return session(
        client.request(
            HttpMethod.POST,
            "/token?grant_type=password",
            Map.of("email", email.trim(), "password", password),
            null));
  }

  public AuthService.Session refresh(String refresh) {
    if (refresh == null || refresh.isBlank() || refresh.length() > 2000)
      throw ApiException.unauthenticated();
    return session(
        client.request(
            HttpMethod.POST,
            "/token?grant_type=refresh_token",
            Map.of("refresh_token", refresh),
            null));
  }

  public JsonNode register(
      String email, String name, String password, String challenge, String redirect) {
    return client.request(
        HttpMethod.POST,
        "/signup?redirect_to=" + encode(redirect),
        Map.of(
            "email",
            email.trim(),
            "password",
            password,
            "data",
            Map.of("name", name.trim()),
            "code_challenge",
            challenge,
            "code_challenge_method",
            "s256"),
        null);
  }

  public void email(String email, String challenge, String redirect, boolean recovery) {
    var body = new HashMap<String, Object>();
    body.put("email", email.trim());
    body.put("code_challenge", challenge);
    body.put("code_challenge_method", "s256");
    // A PKCE email sign-in link also confirms an existing unconfirmed account; never creates one.
    if (!recovery) body.put("create_user", false);
    try {
      client.request(
          HttpMethod.POST,
          (recovery ? "/recover" : "/otp") + "?redirect_to=" + encode(redirect),
          body,
          null);
    } catch (ApiException e) {
      // In particular, OTP with create_user=false can reject an unknown account.
      // Keep the public response identical; throttling and outages still propagate.
      if (e.status().value() != 400) throw e;
    }
  }

  public AuthService.Session exchange(String code, String verifier) {
    if (verifier == null || !verifier.matches("[A-Za-z0-9_-]{43}"))
      throw ApiException.invalid(
          "Start a new email request in this browser before opening the link");
    return session(
        client.request(
            HttpMethod.POST,
            "/token?grant_type=pkce",
            Map.of("auth_code", code, "code_verifier", verifier),
            null));
  }

  public void logout(String access, String refresh, boolean global) {
    if (access == null && refresh != null) {
      try {
        access = refresh(refresh).accessToken();
      } catch (ApiException e) {
        if (e.status().value() != 401) throw e;
        return;
      }
    }
    if (access == null) return;
    try {
      client.request(
          HttpMethod.POST, "/logout?scope=" + (global ? "global" : "local"), null, access);
    } catch (ApiException e) {
      if (e.status().value() != 401) throw e;
    }
  }

  public AuthService.Profile rename(String access, UUID id, String name) {
    client.request(HttpMethod.PUT, "/user", Map.of("data", Map.of("name", name.trim())), access);
    return identities.rename(id, name);
  }

  public void changePassword(Actor actor, String currentPassword, String password) {
    var check = login(actor.email(), currentPassword);
    try {
      if (!check.user().id().equals(actor.id())) throw ApiException.unauthenticated();
      setPassword(check.accessToken(), password, "PASSWORD_CHANGED");
    } finally {
      logout(check.accessToken(), null, false);
    }
  }

  public void setPassword(String access, String password) {
    setPassword(access, password, "PASSWORD_RESET");
  }

  private void setPassword(String access, String password, String action) {
    var profile = profile(access);
    client.request(HttpMethod.PUT, "/user", Map.of("password", password), access);
    logout(access, null, true);
    audit.record(profile.id(), null, action, "USER", profile.id());
  }

  private String encode(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}

package com.teamflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import jakarta.servlet.http.Cookie;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real HTTP calls to a controlled Auth API fixture, plus the real PostgreSQL tenant schema. */
@SpringBootTest(properties = "teamflow.auth.provider=supabase")
class SupabaseAuthIntegrationTest extends ApiTestSupport {
  static final ObjectMapper JSON = new ObjectMapper();
  static final HttpServer PROVIDER;
  static final Map<String, ObjectNode> PEOPLE = new ConcurrentHashMap<>();
  static final Map<String, ObjectNode> ACCESS = new ConcurrentHashMap<>();
  static final Map<String, String> REFRESH = new ConcurrentHashMap<>();
  static volatile JdbcClient database;
  static volatile JsonNode lastEmail;
  static volatile String lastEmailPath;
  static volatile JsonNode lastExchange;
  static volatile boolean rejectUser;
  static final String PASSWORD = "test-password-123";

  static {
    try {
      PROVIDER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      PROVIDER.createContext("/auth/v1/", SupabaseAuthIntegrationTest::respond);
      PROVIDER.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static String origin() {
    return "http://127.0.0.1:" + PROVIDER.getAddress().getPort();
  }

  @DynamicPropertySource
  static void authProperties(DynamicPropertyRegistry r) {
    r.add("teamflow.auth.supabase-url", SupabaseAuthIntegrationTest::origin);
    r.add("teamflow.auth.supabase-key", () -> "sb_publishable_test-only");
  }

  @Autowired JdbcClient db;

  @BeforeEach
  void fixture() {
    database = db;
    rejectUser = false;
    // Disposable test DB only: stand-ins for Supabase's managed, read-only session metadata.
    db.sql("CREATE SCHEMA IF NOT EXISTS auth").update();
    db.sql(
            "CREATE TABLE IF NOT EXISTS auth.users(id uuid PRIMARY KEY,deleted_at"
                + " timestamptz,banned_until timestamptz)")
        .update();
    db.sql(
            "CREATE TABLE IF NOT EXISTS auth.sessions(id uuid PRIMARY KEY,user_id uuid NOT"
                + " NULL,not_after timestamptz)")
        .update();
  }

  @AfterAll
  static void stopProvider() {
    PROVIDER.stop(0);
  }

  static ObjectNode person(String email, boolean confirmed) {
    ObjectNode user = JSON.createObjectNode();
    user.put("id", UUID.randomUUID().toString()).put("email", email).put("is_anonymous", false);
    if (confirmed) user.put("email_confirmed_at", Instant.now().toString());
    else user.putNull("email_confirmed_at");
    user.putObject("user_metadata").put("name", "Supabase person").put("role", "OWNER");
    PEOPLE.put(email, user);
    return user;
  }

  static ObjectNode issue(ObjectNode user, Map<String, Object> overrides) {
    UUID session = UUID.randomUUID(), subject = UUID.fromString(user.path("id").asText());
    database
        .sql("INSERT INTO auth.users(id) VALUES(:id) ON CONFLICT DO NOTHING")
        .param("id", subject)
        .update();
    database
        .sql("INSERT INTO auth.sessions(id,user_id) VALUES(:id,:user)")
        .param("id", session)
        .param("user", subject)
        .update();
    var claims =
        new HashMap<String, Object>(
            Map.of(
                "iss",
                origin() + "/auth/v1",
                "sub",
                subject.toString(),
                "session_id",
                session.toString(),
                "aud",
                "authenticated",
                "role",
                "authenticated",
                "exp",
                Instant.now().plusSeconds(3600).getEpochSecond()));
    claims.putAll(overrides);
    try {
      String token =
          "eyJhbGciOiJFUzI1NiJ9."
              + Base64.getUrlEncoder()
                  .withoutPadding()
                  .encodeToString(JSON.writeValueAsBytes(claims))
              + ".provider-verified-test-signature";
      ACCESS.put(token, user);
      String refresh = UUID.randomUUID().toString();
      REFRESH.put(refresh, token);
      return JSON.createObjectNode().put("access_token", token).put("refresh_token", refresh);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static void respond(HttpExchange exchange) throws java.io.IOException {
    int status = 200;
    JsonNode output = JSON.createObjectNode();
    String path = exchange.getRequestURI().getPath();
    JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
    String header = exchange.getRequestHeaders().getFirst("Authorization");
    String access = header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
    try {
      if (path.endsWith("/user")) {
        if (rejectUser || !ACCESS.containsKey(access)) status = 401;
        else output = ACCESS.get(access);
      } else if (path.endsWith("/signup")) {
        lastEmail = body;
        lastEmailPath = exchange.getRequestURI().toString();
        output = JSON.createObjectNode().put("id", UUID.randomUUID().toString());
      } else if (path.endsWith("/recover") || path.endsWith("/otp")) {
        lastEmail = body;
        lastEmailPath = exchange.getRequestURI().toString();
        if (!PEOPLE.containsKey(body.path("email").asText())) status = 400;
      } else if (path.endsWith("/token")) {
        String query = exchange.getRequestURI().getQuery();
        if (query.endsWith("password")) {
          ObjectNode user = PEOPLE.get(body.path("email").asText());
          if (user == null || !PASSWORD.equals(body.path("password").asText())) status = 400;
          else output = issue(user, Map.of());
        } else if (query.endsWith("refresh_token")) {
          String previous = REFRESH.remove(body.path("refresh_token").asText());
          if (previous == null) status = 400;
          else {
            var previousClaims =
                JSON.readTree(Base64.getUrlDecoder().decode(previous.split("\\.")[1]));
            output =
                issue(
                    ACCESS.get(previous),
                    Map.of("session_id", previousClaims.path("session_id").asText()));
          }
        } else {
          lastExchange = body;
          String email = body.path("auth_code").asText();
          if (!PEOPLE.containsKey(email)) status = 400;
          else output = issue(PEOPLE.get(email), Map.of());
        }
      } else if (path.endsWith("/logout")) {
        if (!ACCESS.containsKey(access)) status = 401;
        else {
          JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(access.split("\\.")[1]));
          if (exchange.getRequestURI().getQuery().contains("global"))
            database
                .sql("DELETE FROM auth.sessions WHERE user_id=:id")
                .param("id", UUID.fromString(claims.path("sub").asText()))
                .update();
          else
            database
                .sql("DELETE FROM auth.sessions WHERE id=:id")
                .param("id", UUID.fromString(claims.path("session_id").asText()))
                .update();
          status = 204;
        }
      } else status = 404;
    } catch (Exception e) {
      status = 500;
    }
    byte[] bytes = JSON.writeValueAsBytes(output);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
    if (status != 204) exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  Account providerAccount() throws Exception {
    String email = UUID.randomUUID() + "@example.test";
    person(email, true);
    var result =
        perform(
                post("/api/v1/auth/login")
                    .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = body(result);
    return new Account(
        body.path("accessToken").asText(),
        result.getResponse().getCookie("tf_refresh").getValue(),
        body.path("user").path("id").asText(),
        email);
  }

  @Test
  void supabaseLoginRefreshAndTenantIsolation() throws Exception {
    Account owner = providerAccount(), stranger = providerAccount();
    String organization = org(owner);
    perform(as(get("/api/v1/organizations/" + organization), stranger))
        .andExpect(status().isForbidden());
    perform(as(get("/api/v1/auth/me"), owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.emailVerified").value(true));
    var refreshed =
        perform(post("/api/v1/auth/refresh").cookie(new Cookie("tf_refresh", owner.refresh())))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(refreshed.getResponse().getCookie("tf_refresh").isHttpOnly()).isTrue();
    assertThat(refreshed.getResponse().getContentAsString())
        .doesNotContain("refresh_token", "password");
    perform(
            post("/api/v1/auth/login")
                .content(
                    json.writeValueAsString(Map.of("email", owner.email(), "password", "wrong"))))
        .andExpect(status().isUnauthorized());
    perform(get("/api/v1/organizations").header("Authorization", "Bearer old-local-opaque-token"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void verifiedEmailAdoptsLegacyIdAndRevokesOldCredentials() throws Exception {
    String email = UUID.randomUUID() + "@example.test";
    UUID legacy = UUID.randomUUID();
    db.sql(
            "INSERT INTO users(id,email,name,password_hash) VALUES(:id,:email,'Legacy"
                + " owner','legacy-hash')")
        .param("id", legacy)
        .param("email", email)
        .update();
    db.sql(
            "INSERT INTO"
                + " refresh_tokens(user_id,family_id,token_hash,access_hash,access_expires_at,expires_at)"
                + " VALUES(:id,:family,:hash,:hash,now()+interval '1 hour',now()+interval '1 day')")
        .param("id", legacy)
        .param("family", UUID.randomUUID())
        .param("hash", UUID.randomUUID().toString())
        .update();
    ObjectNode user = person(email, false);
    String token = issue(user, Map.of()).path("access_token").asText();
    perform(get("/api/v1/organizations").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
    assertThat(
            db.sql("SELECT password_hash FROM users WHERE id=:id")
                .param("id", legacy)
                .query(String.class)
                .single())
        .isEqualTo("legacy-hash");
    user.put("email_confirmed_at", Instant.now().toString());
    var result =
        perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(body(result).path("id").asText()).isEqualTo(legacy.toString());
    assertThat(
            db.sql("SELECT password_hash IS NULL FROM users WHERE id=:id")
                .param("id", legacy)
                .query(Boolean.class)
                .single())
        .isTrue();
    assertThat(
            db.sql("SELECT count(*) FROM refresh_tokens WHERE user_id=:id")
                .param("id", legacy)
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  void claimsProviderRejectionDisabledAndRevokedSessionsFailClosed() throws Exception {
    Account account = providerAccount();
    ObjectNode user = PEOPLE.get(account.email());
    for (var override :
        List.<Map<String, Object>>of(
            Map.of("iss", "https://wrong.example/auth/v1"),
            Map.of("aud", "anon"),
            Map.of("role", "service_role"),
            Map.of("exp", 1),
            Map.of("nbf", Instant.now().plusSeconds(600).getEpochSecond()),
            Map.of("sub", UUID.randomUUID().toString()),
            Map.of("session_id", UUID.randomUUID().toString()))) {
      String invalid = issue(user, override).path("access_token").asText();
      perform(get("/api/v1/organizations").header("Authorization", "Bearer " + invalid))
          .andExpect(status().isUnauthorized());
    }
    rejectUser = true;
    perform(as(get("/api/v1/organizations"), account)).andExpect(status().isUnauthorized());
    rejectUser = false;
    db.sql("UPDATE users SET enabled=false WHERE id=:id")
        .param("id", UUID.fromString(account.id()))
        .update();
    perform(as(get("/api/v1/organizations"), account)).andExpect(status().isUnauthorized());
    db.sql("UPDATE users SET enabled=true WHERE id=:id")
        .param("id", UUID.fromString(account.id()))
        .update();
    db.sql("UPDATE auth.users SET banned_until=now()+interval '1 day' WHERE id=:id")
        .param("id", UUID.fromString(user.path("id").asText()))
        .update();
    perform(as(get("/api/v1/organizations"), account)).andExpect(status().isUnauthorized());
    db.sql("UPDATE auth.users SET banned_until=NULL WHERE id=:id")
        .param("id", UUID.fromString(user.path("id").asText()))
        .update();
    perform(post("/api/v1/auth/logout").cookie(new Cookie("tf_refresh", account.refresh())))
        .andExpect(status().isNoContent());
    perform(as(get("/api/v1/organizations"), account)).andExpect(status().isUnauthorized());
  }

  @Test
  void pkceConfirmationAndRecoveryUseHttpOnlyCookies() throws Exception {
    for (String endpoint : List.of("forgot-password", "resend-confirmation")) {
      perform(
              post("/api/v1/auth/" + endpoint)
                  .content(
                      json.writeValueAsString(
                          Map.of("email", UUID.randomUUID() + "@example.test"))))
          .andExpect(status().isAccepted());
    }
    String email = UUID.randomUUID() + "@example.test";
    person(email, true);
    var signup =
        perform(
                post("/api/v1/auth/register")
                    .content(
                        json.writeValueAsString(
                            Map.of("email", email, "name", "New person", "password", PASSWORD))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.confirmationRequired").value(true))
            .andReturn();
    Cookie verifier = signup.getResponse().getCookie("tf_signup_verifier");
    assertThat(verifier.isHttpOnly()).isTrue();
    assertThat(lastEmail.path("code_challenge_method").asText()).isEqualTo("s256");
    assertThat(lastEmailPath).contains("redirect_to=");
    perform(
            post("/api/v1/auth/callback")
                .content(json.writeValueAsString(Map.of("code", email, "flow", "signup"))))
        .andExpect(status().isBadRequest());
    perform(
            post("/api/v1/auth/callback")
                .cookie(verifier)
                .content(json.writeValueAsString(Map.of("code", email, "flow", "signup"))))
        .andExpect(status().isOk());
    assertThat(lastExchange.path("code_verifier").asText()).isEqualTo(verifier.getValue());
    var forgot =
        perform(
                post("/api/v1/auth/forgot-password")
                    .content(json.writeValueAsString(Map.of("email", email))))
            .andExpect(status().isAccepted())
            .andReturn();
    var callback =
        perform(
                post("/api/v1/auth/callback")
                    .cookie(forgot.getResponse().getCookie("tf_recovery_verifier"))
                    .content(json.writeValueAsString(Map.of("code", email, "flow", "recovery"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recovery").value(true))
            .andReturn();
    assertThat(callback.getResponse().getContentAsString())
        .doesNotContain("accessToken", "refresh");
    perform(post("/api/v1/auth/reset-password").content("{\"password\":\"new-password-123\"}"))
        .andExpect(status().isBadRequest());
    perform(
            post("/api/v1/auth/reset-password")
                .cookie(callback.getResponse().getCookie("tf_recovery"))
                .content("{\"password\":\"new-password-123\"}"))
        .andExpect(status().isNoContent());
  }
}

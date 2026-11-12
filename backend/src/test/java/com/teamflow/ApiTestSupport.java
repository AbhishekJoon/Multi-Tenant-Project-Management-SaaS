package com.teamflow;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.teamflow.auth.MailService;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
public abstract class ApiTestSupport {
  static final String EXTERNAL_URL = System.getenv("TEAMFLOW_TEST_DATABASE_URL");
  static final PostgreSQLContainer<?> POSTGRES;

  static {
    if (EXTERNAL_URL == null || EXTERNAL_URL.isBlank()) {
      POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
      if ("true".equals(System.getenv("TEAMFLOW_TEST_PRIVATE_SCHEMA"))) {
        POSTGRES.withInitScript("supabase-test-init.sql");
      }
      POSTGRES.start();
    } else {
      POSTGRES = null;
      if (!EXTERNAL_URL.matches(
          "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/teamflow_test_[A-Za-z0-9_]+")) {
        throw new IllegalStateException(
            "External tests require an explicitly named local teamflow_test_* database, never a"
                + " hosted or development database.");
      }
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> POSTGRES == null ? EXTERNAL_URL : POSTGRES.getJdbcUrl());
    r.add(
        "spring.datasource.username",
        () ->
            POSTGRES == null
                ? System.getenv("TEAMFLOW_TEST_DATABASE_USER")
                : POSTGRES.getUsername());
    r.add(
        "spring.datasource.password",
        () ->
            POSTGRES == null
                ? System.getenv("TEAMFLOW_TEST_DATABASE_PASSWORD")
                : POSTGRES.getPassword());
    if ("true".equals(System.getenv("TEAMFLOW_TEST_PRIVATE_SCHEMA"))) {
      r.add(
          "spring.datasource.hikari.data-source-properties.currentSchema",
          () -> "teamflow,extensions");
      r.add("spring.jpa.properties.hibernate.default_schema", () -> "teamflow");
      r.add("spring.flyway.default-schema", () -> "teamflow");
      r.add("spring.flyway.schemas", () -> "teamflow");
      r.add("spring.flyway.create-schemas", () -> false);
      r.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/supabase");
    }
    r.add("teamflow.bcrypt-cost", () -> 4);
    r.add("teamflow.secure-cookies", () -> false);
    r.add("debug", () -> false);
  }

  @Autowired protected MockMvc mvc;
  @Autowired protected ObjectMapper json;
  @MockitoBean protected MailService mail;
  static final AtomicInteger ADDRESS = new AtomicInteger();

  protected record Account(String token, String refresh, String id, String email) {}

  protected Account account() throws Exception {
    String email = UUID.randomUUID() + "@example.test";
    var result =
        perform(
                post("/api/v1/auth/register")
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "email",
                                email,
                                "name",
                                "Test user",
                                "password",
                                "test-password-123"))))
            .andExpect(status().isCreated())
            .andReturn();
    var body = body(result);
    return new Account(
        body.path("accessToken").asText(),
        result.getResponse().getCookie("tf_refresh").getValue(),
        body.path("user").path("id").asText(),
        email);
  }

  protected ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(
        request
            .contentType("application/json")
            .header("Origin", "http://localhost:5173")
            .header("X-TeamFlow-Request", "TeamFlow")
            .with(
                r -> {
                  r.setRemoteAddr("10.0.0." + ADDRESS.incrementAndGet());
                  return r;
                }));
  }

  protected MockHttpServletRequestBuilder as(
      MockHttpServletRequestBuilder request, Account account) {
    return request.header("Authorization", "Bearer " + account.token());
  }

  protected JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }

  protected String org(Account account) throws Exception {
    return body(perform(
                as(post("/api/v1/organizations"), account)
                    .content("{\"name\":\"Workspace\",\"description\":\"\"}"))
            .andExpect(status().isCreated())
            .andReturn())
        .path("id")
        .asText();
  }

  protected String invite(Account owner, String org, Account member, String role) throws Exception {
    clearInvocations(mail);
    perform(
            as(post("/api/v1/organizations/" + org + "/invitations"), owner)
                .content(json.writeValueAsString(Map.of("email", member.email(), "role", role))))
        .andExpect(status().isCreated());
    var captor = ArgumentCaptor.forClass(String.class);
    verify(mail)
        .sendLink(eq(member.email()), anyString(), eq("/accept-invitation"), captor.capture());
    return captor.getValue();
  }

  protected void join(Account owner, String org, Account member, String role) throws Exception {
    String token = invite(owner, org, member, role);
    perform(
            as(post("/api/v1/invitations/respond"), member)
                .content(json.writeValueAsString(Map.of("token", token, "accept", true))))
        .andExpect(status().isOk());
  }
}

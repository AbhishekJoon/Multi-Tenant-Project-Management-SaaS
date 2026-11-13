package com.teamflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class IdentityTenantIntegrationTest extends ApiTestSupport {
  @Autowired JdbcClient db;

  @Test
  void anonymousAndCsrfAreRejected() throws Exception {
    perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/auth/refresh")
                .header("Origin", "https://evil.example")
                .header("X-TeamFlow-Request", "TeamFlow"))
        .andExpect(status().isForbidden());
    perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void registrationLoginProfileAndDisabledAccounts() throws Exception {
    var a = account();
    perform(as(get("/api/v1/auth/me"), a))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(a.email()))
        .andExpect(jsonPath("$.passwordHash").doesNotExist());
    perform(
            post("/api/v1/auth/login")
                .content(json.writeValueAsString(Map.of("email", a.email(), "password", "wrong"))))
        .andExpect(status().isUnauthorized());
    perform(
            post("/api/v1/auth/login")
                .content(
                    json.writeValueAsString(
                        Map.of("email", a.email(), "password", "test-password-123"))))
        .andExpect(status().isOk());
    db.sql("UPDATE users SET enabled=false WHERE id=cast(:id as uuid)")
        .param("id", a.id())
        .update();
    perform(as(get("/api/v1/auth/me"), a)).andExpect(status().isUnauthorized());
  }

  @Test
  void refreshRotatesAndReplayRevokesDescendants() throws Exception {
    var a = account();
    var rotated =
        perform(post("/api/v1/auth/refresh").cookie(new Cookie("tf_refresh", a.refresh())))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(body(rotated).path("accessToken").asText()).isNotEqualTo(a.token());
    var fresh = rotated.getResponse().getCookie("tf_refresh");
    assertThat(fresh.isHttpOnly()).isTrue();
    assertThat(fresh.getValue()).isNotEqualTo(a.refresh());
    perform(post("/api/v1/auth/refresh").cookie(new Cookie("tf_refresh", a.refresh())))
        .andExpect(status().isUnauthorized());
    perform(post("/api/v1/auth/refresh").cookie(fresh)).andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/v1/auth/me")
                .header("Authorization", "Bearer " + body(rotated).path("accessToken").asText()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void logoutRevokesAccessAndRefresh() throws Exception {
    var a = account();
    perform(post("/api/v1/auth/logout").cookie(new Cookie("tf_refresh", a.refresh())))
        .andExpect(status().isNoContent());
    perform(as(get("/api/v1/auth/me"), a)).andExpect(status().isUnauthorized());
    perform(post("/api/v1/auth/refresh").cookie(new Cookie("tf_refresh", a.refresh())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void organizationsRejectNonmembersAndRespectDifferentRoles() throws Exception {
    var a = account();
    var b = account();
    String orgA = org(a), orgB = org(b);
    perform(as(get("/api/v1/organizations/" + orgB), a)).andExpect(status().isForbidden());
    perform(
            as(patch("/api/v1/organizations/" + orgB), a)
                .content("{\"name\":\"Stolen\",\"description\":\"\"}"))
        .andExpect(status().isForbidden());
    join(b, orgB, a, "VIEWER");
    perform(as(get("/api/v1/organizations/" + orgB), a))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("VIEWER"));
    perform(as(get("/api/v1/organizations/" + orgA), a))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("OWNER"));
    perform(
            as(patch("/api/v1/organizations/" + orgB), a)
                .content("{\"name\":\"Stolen\",\"description\":\"\"}"))
        .andExpect(status().isForbidden());
    perform(as(get("/api/v1/organizations"), a)).andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void invitationsAreEmailBoundSingleUseAndCannotGrantOwnership() throws Exception {
    var a = account();
    var b = account();
    var stranger = account();
    String org = org(a);
    String token = invite(a, org, b, "MEMBER");
    String payload = json.writeValueAsString(Map.of("token", token, "accept", true));
    perform(as(post("/api/v1/invitations/respond"), stranger).content(payload))
        .andExpect(status().isForbidden());
    perform(as(post("/api/v1/invitations/respond"), b).content(payload)).andExpect(status().isOk());
    perform(as(post("/api/v1/invitations/respond"), b).content(payload))
        .andExpect(status().isBadRequest());
    perform(
            as(post("/api/v1/organizations/" + org + "/invitations"), a)
                .content(
                    json.writeValueAsString(Map.of("email", stranger.email(), "role", "OWNER"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void finalOwnerAndAdminEscalationAreProtected() throws Exception {
    var a = account();
    var admin = account();
    String org = org(a);
    perform(as(delete("/api/v1/organizations/" + org + "/members/" + a.id()), a))
        .andExpect(status().isConflict());
    join(a, org, admin, "ADMIN");
    perform(
            as(patch("/api/v1/organizations/" + org + "/members/" + a.id()), admin)
                .content("{\"role\":\"MEMBER\"}"))
        .andExpect(status().isForbidden());
    perform(
            as(patch("/api/v1/organizations/" + org + "/members/" + admin.id()), admin)
                .content("{\"role\":\"OWNER\"}"))
        .andExpect(status().isBadRequest());
    perform(
            as(post("/api/v1/organizations/" + org + "/transfer-ownership"), a)
                .content(json.writeValueAsString(Map.of("userId", admin.id()))))
        .andExpect(status().isNoContent());
    perform(as(get("/api/v1/organizations/" + org), admin))
        .andExpect(jsonPath("$.role").value("OWNER"));
    perform(as(get("/api/v1/organizations/" + org), a))
        .andExpect(jsonPath("$.role").value("ADMIN"));
  }
}

package com.teamflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class AccountRecoveryIntegrationTest extends ApiTestSupport {
  @Autowired JdbcClient db;

  @Test
  void resetTokensExpireAreSingleUseAndRevokeSessions() throws Exception {
    var a = account();
    clearInvocations(mail);
    perform(
            post("/api/v1/auth/forgot-password")
                .content(json.writeValueAsString(Map.of("email", a.email()))))
        .andExpect(status().isAccepted());
    var captor = ArgumentCaptor.forClass(String.class);
    verify(mail).sendLink(eq(a.email()), anyString(), eq("/reset-password"), captor.capture());
    String payload =
        json.writeValueAsString(Map.of("token", captor.getValue(), "password", "new-password-456"));
    perform(post("/api/v1/auth/reset-password").content(payload)).andExpect(status().isNoContent());
    perform(post("/api/v1/auth/reset-password").content(payload))
        .andExpect(status().isBadRequest());
    perform(as(get("/api/v1/auth/me"), a)).andExpect(status().isUnauthorized());
    perform(
            post("/api/v1/auth/login")
                .content(
                    json.writeValueAsString(
                        Map.of("email", a.email(), "password", "new-password-456"))))
        .andExpect(status().isOk());
    clearInvocations(mail);
    perform(
            post("/api/v1/auth/forgot-password")
                .content(json.writeValueAsString(Map.of("email", a.email()))))
        .andExpect(status().isAccepted());
    verify(mail).sendLink(eq(a.email()), anyString(), eq("/reset-password"), captor.capture());
    db.sql(
            "UPDATE password_reset_tokens SET expires_at=now()-interval '1 minute' WHERE"
                + " user_id=cast(:id as uuid)")
        .param("id", a.id())
        .update();
    perform(
            post("/api/v1/auth/reset-password")
                .content(
                    json.writeValueAsString(
                        Map.of("token", captor.getValue(), "password", "expired-password-123"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void emailVerificationIsSingleUseAndMailFailureRollsBackRegistration() throws Exception {
    clearInvocations(mail);
    var a = account();
    var captor = ArgumentCaptor.forClass(String.class);
    verify(mail).sendLink(eq(a.email()), anyString(), eq("/verify-email"), captor.capture());
    String payload = json.writeValueAsString(Map.of("token", captor.getValue()));
    perform(post("/api/v1/auth/verify-email").content(payload)).andExpect(status().isNoContent());
    perform(post("/api/v1/auth/verify-email").content(payload)).andExpect(status().isBadRequest());
    perform(as(get("/api/v1/auth/me"), a)).andExpect(jsonPath("$.emailVerified").value(true));
    doThrow(new org.springframework.mail.MailSendException("Unavailable"))
        .when(mail)
        .sendLink(eq("mail-failure@example.test"), anyString(), anyString(), anyString());
    perform(
            post("/api/v1/auth/register")
                .content(
                    "{\"name\":\"Failure\",\"email\":\"mail-failure@example.test\",\"password\":\"test-password-123\"}"))
        .andExpect(status().isServiceUnavailable());
    assertThat(
            db.sql("SELECT count(*) FROM users WHERE email='mail-failure@example.test'")
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void authenticationIsRateLimitedPerRemoteAddress() throws Exception {
    for (int i = 0; i < 40; i++)
      mvc.perform(
              post("/api/v1/auth/login")
                  .contentType("application/json")
                  .header("Origin", "http://localhost:5173")
                  .header("X-TeamFlow-Request", "TeamFlow")
                  .content("{\"email\":\"missing@example.test\",\"password\":\"wrong\"}")
                  .with(
                      r -> {
                        r.setRemoteAddr("192.0.2.42");
                        return r;
                      }))
          .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/auth/login")
                .header("Origin", "http://localhost:5173")
                .header("X-TeamFlow-Request", "TeamFlow")
                .with(
                    r -> {
                      r.setRemoteAddr("192.0.2.42");
                      return r;
                    }))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
  }
}

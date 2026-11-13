package com.teamflow.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.teamflow.audit.AuditService;
import com.teamflow.common.ApiException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "teamflow.auth.provider", havingValue = "supabase")
public class SupabaseIdentities {
  private final JdbcClient db;
  private final AuditService audit;

  public SupabaseIdentities(JdbcClient db, AuditService audit) {
    this.db = db;
    this.audit = audit;
  }

  @Transactional
  public AuthService.Profile synchronize(JsonNode user) {
    UUID subject = UUID.fromString(user.path("id").asText());
    String email = user.path("email").asText().trim().toLowerCase(Locale.ROOT);
    if (email.isBlank()
        || email.length() > 254
        || user.path("email_confirmed_at").isNull()
        || user.path("email_confirmed_at").asText().isBlank()
        || user.path("is_anonymous").asBoolean()) throw ApiException.unauthenticated();
    // Serialize both first-login inserts and verified-email adoption of existing accounts.
    db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:email, 0))")
        .param("email", email)
        .query()
        .singleRow();
    var bound =
        db
            .sql(
                "SELECT id,email,name,enabled FROM users WHERE supabase_subject=:subject FOR"
                    + " UPDATE")
            .param("subject", subject)
            .query()
            .listOfRows()
            .stream()
            .findFirst();
    if (bound.isPresent()) {
      var row = bound.get();
      if (!Boolean.TRUE.equals(row.get("enabled"))) throw ApiException.unauthenticated();
      // Email changes require a separate account migration to avoid silently merging identities.
      if (!email.equals(row.get("email")))
        throw ApiException.conflict("Contact support to update the account email");
      return new AuthService.Profile((UUID) row.get("id"), email, (String) row.get("name"), true);
    }
    var existing =
        db
            .sql("SELECT id,enabled,supabase_subject FROM users WHERE email=:email FOR UPDATE")
            .param("email", email)
            .query()
            .listOfRows()
            .stream()
            .findFirst();
    UUID id;
    if (existing.isPresent()) {
      var row = existing.get();
      if (!Boolean.TRUE.equals(row.get("enabled")) || row.get("supabase_subject") != null)
        throw ApiException.unauthenticated();
      id = (UUID) row.get("id");
      db.sql(
              "UPDATE users SET supabase_subject=:subject,password_hash=NULL,email_verified=true"
                  + " WHERE id=:id")
          .param("subject", subject)
          .param("id", id)
          .update();
      // Old application credentials and recovery links must stop working after adoption.
      for (String table :
          List.of("refresh_tokens", "password_reset_tokens", "email_verification_tokens"))
        db.sql("DELETE FROM " + table + " WHERE user_id=:id").param("id", id).update();
      audit.record(id, null, "ACCOUNT_LINKED_TO_SUPABASE", "USER", id);
    } else {
      id = UUID.randomUUID();
      String name = user.path("user_metadata").path("name").asText("").trim();
      if (name.isBlank())
        name = email.substring(0, email.indexOf('@') > 0 ? email.indexOf('@') : email.length());
      if (name.length() > 100) name = name.substring(0, 100);
      db.sql(
              "INSERT INTO users(id,email,name,supabase_subject,email_verified)"
                  + " VALUES(:id,:email,:name,:subject,true)")
          .param("id", id)
          .param("email", email)
          .param("name", name)
          .param("subject", subject)
          .update();
      audit.record(id, null, "ACCOUNT_REGISTERED", "USER", id);
    }
    return db.sql("SELECT id,email,name,email_verified FROM users WHERE id=:id")
        .param("id", id)
        .query(
            (rs, n) ->
                new AuthService.Profile(
                    rs.getObject("id", UUID.class),
                    rs.getString("email"),
                    rs.getString("name"),
                    rs.getBoolean("email_verified")))
        .single();
  }

  public boolean sessionActive(UUID session, UUID subject) {
    // Read managed auth metadata only; Supabase owns all writes to its auth schema.
    return db.sql(
            "SELECT EXISTS(SELECT 1 FROM auth.sessions s JOIN auth.users u ON u.id=s.user_id WHERE"
                + " s.id=:session AND s.user_id=:subject AND (s.not_after IS NULL OR"
                + " s.not_after>now()) AND u.deleted_at IS NULL AND (u.banned_until IS NULL OR"
                + " u.banned_until<now()))")
        .param("session", session)
        .param("subject", subject)
        .query(Boolean.class)
        .single();
  }

  @Transactional
  public AuthService.Profile rename(UUID id, String name) {
    db.sql("UPDATE users SET name=:name WHERE id=:id AND enabled")
        .param("name", name.trim())
        .param("id", id)
        .update();
    return db.sql("SELECT id,email,name FROM users WHERE id=:id AND enabled")
        .param("id", id)
        .query(
            (rs, n) ->
                new AuthService.Profile(
                    rs.getObject("id", UUID.class),
                    rs.getString("email"),
                    rs.getString("name"),
                    true))
        .optional()
        .orElseThrow(ApiException::unauthenticated);
  }
}

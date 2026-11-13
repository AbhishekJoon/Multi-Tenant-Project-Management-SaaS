package com.teamflow.auth;

import com.teamflow.audit.AuditService;
import com.teamflow.common.ApiException;
import com.teamflow.security.*;
import com.teamflow.user.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private final UserRepository users;
  private final JdbcClient db;
  private final PasswordEncoder passwords;
  private final MailService mail;
  private final AuditService audit;
  private final String dummyHash;

  public record Profile(UUID id, String email, String name, boolean emailVerified) {}

  public record Session(String accessToken, String refreshToken, Profile user) {
    @Override
    public String toString() {
      return "Session[REDACTED]";
    }
  }

  record StoredToken(
      UUID userId, UUID familyId, Instant expiresAt, boolean used, boolean revoked) {}

  public AuthService(
      UserRepository users,
      JdbcClient db,
      PasswordEncoder passwords,
      MailService mail,
      AuditService audit) {
    this.users = users;
    this.db = db;
    this.passwords = passwords;
    this.mail = mail;
    this.audit = audit;
    this.dummyHash = passwords.encode(Tokens.generate());
  }

  public Profile profile(UUID id) {
    return profile(users.findById(id).orElseThrow(ApiException::unauthenticated));
  }

  private Profile profile(UserAccount u) {
    return new Profile(u.getId(), u.getEmail(), u.getName(), u.isEmailVerified());
  }

  @Transactional
  public Session register(String email, String name, String password) {
    email = email.trim().toLowerCase(Locale.ROOT);
    if (users.findByEmail(email).isPresent())
      throw ApiException.conflict("An account with these details cannot be registered");
    var user = users.saveAndFlush(new UserAccount(email, name.trim(), hashPassword(password)));
    verification(user);
    audit.record(user.getId(), null, "ACCOUNT_REGISTERED", "USER", user.getId());
    return issue(user, UUID.randomUUID());
  }

  @Transactional
  public Session login(String email, String password) {
    if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw ApiException.unauthenticated();
    db.sql("SELECT id FROM users WHERE email=:email FOR UPDATE")
        .param("email", email.trim().toLowerCase(Locale.ROOT))
        .query(UUID.class)
        .optional();
    var found = users.findByEmail(email.trim().toLowerCase(Locale.ROOT));
    boolean matches =
        passwords.matches(password, found.map(UserAccount::getPasswordHash).orElse(dummyHash));
    if (found.isEmpty() || !matches || !found.get().isEnabled())
      throw ApiException.unauthenticated();
    return issue(found.get(), UUID.randomUUID());
  }

  private Session issue(UserAccount user, UUID family) {
    String access = Tokens.generate(), refresh = Tokens.generate();
    db.sql(
            "INSERT INTO"
                + " refresh_tokens(user_id,family_id,token_hash,access_hash,access_expires_at,expires_at)"
                + " VALUES(:user,:family,:refresh,:access,now()+interval '15"
                + " minutes',now()+interval '7 days')")
        .param("user", user.getId())
        .param("family", family)
        .param("refresh", Tokens.hash(refresh))
        .param("access", Tokens.hash(access))
        .update();
    return new Session(access, refresh, profile(user));
  }

  // Commit family revocation even when rejecting replay; rolling this back would restore stolen
  // sessions.
  @Transactional(noRollbackFor = ApiException.class)
  public Session refresh(String token) {
    if (token == null || token.length() > 100) throw ApiException.unauthenticated();
    var stored =
        db.sql(
                "SELECT user_id,family_id,expires_at,used,revoked FROM refresh_tokens WHERE"
                    + " token_hash=:hash")
            .param("hash", Tokens.hash(token))
            .query(
                (r, n) ->
                    new StoredToken(
                        r.getObject(1, UUID.class),
                        r.getObject(2, UUID.class),
                        r.getTimestamp(3).toInstant(),
                        r.getBoolean(4),
                        r.getBoolean(5)))
            .optional()
            .orElseThrow(ApiException::unauthenticated);
    // Serialize every rotation in a family, including use of different generations.
    db.sql("SELECT id FROM users WHERE id=:id FOR UPDATE")
        .param("id", stored.userId())
        .query(UUID.class)
        .single();
    stored =
        db.sql(
                "SELECT user_id,family_id,expires_at,used,revoked FROM refresh_tokens WHERE"
                    + " token_hash=:hash FOR UPDATE")
            .param("hash", Tokens.hash(token))
            .query(
                (r, n) ->
                    new StoredToken(
                        r.getObject(1, UUID.class),
                        r.getObject(2, UUID.class),
                        r.getTimestamp(3).toInstant(),
                        r.getBoolean(4),
                        r.getBoolean(5)))
            .single();
    if (stored.used() || stored.revoked() || stored.expiresAt().isBefore(Instant.now())) {
      revokeFamily(stored.familyId());
      throw ApiException.unauthenticated();
    }
    var user =
        users
            .findById(stored.userId())
            .filter(UserAccount::isEnabled)
            .orElseThrow(ApiException::unauthenticated);
    db.sql("UPDATE refresh_tokens SET used=true WHERE token_hash=:hash")
        .param("hash", Tokens.hash(token))
        .update();
    return issue(user, stored.familyId());
  }

  @Transactional
  public void logout(String token) {
    if (token == null) return;
    db.sql("SELECT user_id FROM refresh_tokens WHERE token_hash=:hash")
        .param("hash", Tokens.hash(token))
        .query(UUID.class)
        .optional()
        .ifPresent(this::lockUser);
    db.sql(
            "UPDATE refresh_tokens SET revoked=true WHERE family_id IN (SELECT family_id FROM"
                + " refresh_tokens WHERE token_hash=:hash)")
        .param("hash", Tokens.hash(token))
        .update();
  }

  private void revokeFamily(UUID family) {
    db.sql("UPDATE refresh_tokens SET revoked=true WHERE family_id=:id")
        .param("id", family)
        .update();
  }

  @Transactional
  public Profile updateProfile(UUID id, String name) {
    var user = users.findById(id).orElseThrow(ApiException::unauthenticated);
    user.setName(name.trim());
    return profile(user);
  }

  @Transactional
  public void changePassword(UUID id, String current, String replacement) {
    lockUser(id);
    var user = users.findById(id).orElseThrow(ApiException::unauthenticated);
    if (!passwords.matches(current, user.getPasswordHash()))
      throw ApiException.invalid("Current password is incorrect");
    user.setPasswordHash(hashPassword(replacement));
    revokeUser(id);
    db.sql("UPDATE password_reset_tokens SET used=true WHERE user_id=:id").param("id", id).update();
    audit.record(id, null, "PASSWORD_CHANGED", "USER", id);
  }

  @Transactional
  public void forgot(String email) {
    users
        .findByEmail(email.trim().toLowerCase(Locale.ROOT))
        .filter(UserAccount::isEnabled)
        .filter(user -> user.getPasswordHash() != null)
        .ifPresent(
            user -> {
              lockUser(user.getId());
              db.sql("UPDATE password_reset_tokens SET used=true WHERE user_id=:id")
                  .param("id", user.getId())
                  .update();
              String token = Tokens.generate();
              db.sql(
                      "INSERT INTO password_reset_tokens(user_id,token_hash,expires_at)"
                          + " VALUES(:id,:hash,now()+interval '30 minutes')")
                  .param("id", user.getId())
                  .param("hash", Tokens.hash(token))
                  .update();
              mail.sendLink(
                  user.getEmail(), "Reset your TeamFlow password", "/reset-password", token);
            });
  }

  @Transactional
  public void reset(String token, String password) {
    UUID id = consume("password_reset_tokens", token);
    lockUser(id);
    var user =
        users
            .findById(id)
            .filter(UserAccount::isEnabled)
            .orElseThrow(ApiException::unauthenticated);
    user.setPasswordHash(hashPassword(password));
    revokeUser(id);
    audit.record(id, null, "PASSWORD_RESET", "USER", id);
  }

  @Transactional
  public void verify(String token) {
    users
        .findById(consume("email_verification_tokens", token))
        .orElseThrow(ApiException::missing)
        .verifyEmail();
  }

  @Transactional
  public void resendVerification(UUID id) {
    lockUser(id);
    var u = users.findById(id).orElseThrow(ApiException::unauthenticated);
    if (!u.isEmailVerified()) verification(u);
  }

  @Transactional
  public void resendByEmail(String email) {
    users
        .findByEmail(email.trim().toLowerCase(Locale.ROOT))
        .filter(UserAccount::isEnabled)
        .filter(u -> !u.isEmailVerified())
        .ifPresent(
            user -> {
              lockUser(user.getId());
              verification(user);
            });
  }

  private void verification(UserAccount user) {
    db.sql("UPDATE email_verification_tokens SET used=true WHERE user_id=:id")
        .param("id", user.getId())
        .update();
    String token = Tokens.generate();
    db.sql(
            "INSERT INTO email_verification_tokens(user_id,token_hash,expires_at)"
                + " VALUES(:id,:hash,now()+interval '24 hours')")
        .param("id", user.getId())
        .param("hash", Tokens.hash(token))
        .update();
    mail.sendLink(user.getEmail(), "Verify your TeamFlow email", "/verify-email", token);
  }

  private UUID consume(String table, String token) {
    // Table is an internal constant, never request input. Atomic token consumption rejects replay.
    return db.sql(
            "UPDATE "
                + table
                + " SET used=true WHERE token_hash=:hash AND NOT used AND expires_at>now()"
                + " RETURNING user_id")
        .param("hash", Tokens.hash(token))
        .query(UUID.class)
        .optional()
        .orElseThrow(() -> ApiException.invalid("This link is invalid or expired"));
  }

  private void lockUser(UUID id) {
    db.sql("SELECT id FROM users WHERE id=:id FOR UPDATE")
        .param("id", id)
        .query(UUID.class)
        .single();
  }

  private String hashPassword(String password) {
    if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw ApiException.invalid("Password must be at most 72 UTF-8 bytes");
    return passwords.encode(password);
  }

  private void revokeUser(UUID id) {
    db.sql("UPDATE refresh_tokens SET revoked=true WHERE user_id=:id").param("id", id).update();
  }
}

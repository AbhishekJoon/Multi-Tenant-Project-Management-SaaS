package com.teamflow.invitation;

import com.teamflow.audit.AuditService;
import com.teamflow.auth.MailService;
import com.teamflow.common.ApiException;
import com.teamflow.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationService {
  public record Invitation(UUID id, String email, OrgRole role, String status, Instant expiresAt) {}

  record Acceptance(UUID id, UUID organizationId, String email, OrgRole role) {}

  private final JdbcClient db;
  private final OrganizationAccess access;
  private final MailService mail;
  private final AuditService audit;

  public InvitationService(
      JdbcClient db, OrganizationAccess access, MailService mail, AuditService audit) {
    this.db = db;
    this.access = access;
    this.mail = mail;
    this.audit = audit;
  }

  public List<Invitation> list(UUID user, UUID org) {
    access.require(user, org, Permission.MANAGE_ORGANIZATION);
    return db.sql(
            "SELECT id,email,role,status,expires_at FROM organization_invitations WHERE"
                + " organization_id=:org ORDER BY created_at DESC,id LIMIT 100")
        .param("org", org)
        .query(Invitation.class)
        .list();
  }

  @Transactional
  public Invitation invite(UUID user, UUID org, String email, OrgRole role) {
    access.lock(org);
    access.require(user, org, Permission.MANAGE_ORGANIZATION);
    if (role == OrgRole.OWNER)
      throw ApiException.invalid("Owners must be appointed through ownership transfer");
    email = email.trim().toLowerCase(Locale.ROOT);
    if (db.sql(
            "SELECT EXISTS(SELECT 1 FROM organization_members m JOIN users u ON u.id=m.user_id"
                + " WHERE m.organization_id=:org AND u.email=:email)")
        .param("org", org)
        .param("email", email)
        .query(Boolean.class)
        .single()) throw ApiException.conflict("This person is already a member");
    db.sql(
            "UPDATE organization_invitations SET status='REVOKED' WHERE organization_id=:org AND"
                + " email=:email AND status='PENDING' AND expires_at<=now()")
        .param("org", org)
        .param("email", email)
        .update();
    String token = Tokens.generate();
    var invitation =
        db.sql(
                "INSERT INTO"
                    + " organization_invitations(organization_id,email,role,token_hash,invited_by,expires_at)"
                    + " VALUES(:org,:email,:role,:hash,:user,now()+interval '7 days') RETURNING"
                    + " id,email,role,status,expires_at")
            .param("org", org)
            .param("email", email)
            .param("role", role.name())
            .param("hash", Tokens.hash(token))
            .param("user", user)
            .query(Invitation.class)
            .single();
    mail.sendLink(email, "Join your team on TeamFlow", "/accept-invitation", token);
    audit.record(user, org, "MEMBER_INVITED", "INVITATION", invitation.id());
    return invitation;
  }

  @Transactional
  public UUID respond(Actor actor, String token, boolean accept) {
    var found =
        db.sql(
                "SELECT id,organization_id,email,role FROM organization_invitations WHERE"
                    + " token_hash=:hash AND status='PENDING' AND expires_at>now()")
            .param("hash", Tokens.hash(token))
            .query(Acceptance.class)
            .optional()
            .orElseThrow(() -> ApiException.invalid("Invitation is invalid or expired"));
    access.lock(found.organizationId());
    if (!actor.email().equals(found.email())) throw ApiException.forbidden();
    if (db.sql("SELECT archived_at IS NOT NULL FROM organizations WHERE id=:org")
        .param("org", found.organizationId())
        .query(Boolean.class)
        .single()) throw ApiException.conflict("Organization is archived");
    int changed =
        db.sql(
                "UPDATE organization_invitations SET status=:status WHERE id=:id AND"
                    + " organization_id=:org AND status='PENDING' AND expires_at>now()")
            .param("status", accept ? "ACCEPTED" : "REJECTED")
            .param("id", found.id())
            .param("org", found.organizationId())
            .update();
    if (changed != 1) throw ApiException.conflict("Invitation has already been processed");
    if (accept)
      db.sql(
              "INSERT INTO organization_members(organization_id,user_id,role)"
                  + " VALUES(:org,:user,:role) ON CONFLICT DO NOTHING")
          .param("org", found.organizationId())
          .param("user", actor.id())
          .param("role", found.role().name())
          .update();
    audit.record(
        actor.id(),
        found.organizationId(),
        accept ? "MEMBER_JOINED" : "INVITATION_REJECTED",
        "INVITATION",
        found.id());
    return found.organizationId();
  }

  @Transactional
  public void revoke(UUID user, UUID org, UUID id) {
    access.lock(org);
    access.require(user, org, Permission.MANAGE_ORGANIZATION);
    if (db.sql(
                "UPDATE organization_invitations SET status='REVOKED' WHERE organization_id=:org"
                    + " AND id=:id AND status='PENDING'")
            .param("org", org)
            .param("id", id)
            .update()
        != 1) throw ApiException.missing();
    audit.record(user, org, "INVITATION_REVOKED", "INVITATION", id);
  }
}

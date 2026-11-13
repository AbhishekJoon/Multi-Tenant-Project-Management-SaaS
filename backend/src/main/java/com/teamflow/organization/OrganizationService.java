package com.teamflow.organization;

import com.teamflow.audit.AuditService;
import com.teamflow.common.ApiException;
import com.teamflow.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {
  public record Organization(
      UUID id, String name, String description, String logoUrl, OrgRole role, Instant archivedAt) {}

  public record Member(UUID userId, String name, String email, OrgRole role, Instant joinedAt) {}

  private final JdbcClient db;
  private final OrganizationAccess access;
  private final AuditService audit;

  public OrganizationService(JdbcClient db, OrganizationAccess access, AuditService audit) {
    this.db = db;
    this.access = access;
    this.audit = audit;
  }

  public List<Organization> list(UUID user) {
    return db.sql(
            "SELECT o.id,o.name,o.description,o.logo_url,m.role,o.archived_at FROM organizations o"
                + " JOIN organization_members m ON m.organization_id=o.id WHERE m.user_id=:user"
                + " ORDER BY o.created_at,o.id")
        .param("user", user)
        .query(Organization.class)
        .list();
  }

  public Organization get(UUID user, UUID org) {
    access.require(user, org, Permission.READ);
    return db.sql(
            "SELECT o.id,o.name,o.description,o.logo_url,m.role,o.archived_at FROM organizations o"
                + " JOIN organization_members m ON m.organization_id=o.id WHERE o.id=:org AND"
                + " m.user_id=:user")
        .param("org", org)
        .param("user", user)
        .query(Organization.class)
        .single();
  }

  @Transactional
  public Organization create(UUID user, String name, String description) {
    UUID id =
        db.sql(
                "INSERT INTO organizations(name,description,created_by)"
                    + " VALUES(:name,:description,:user) RETURNING id")
            .param("name", name.trim())
            .param("description", description)
            .param("user", user)
            .query(UUID.class)
            .single();
    db.sql(
            "INSERT INTO organization_members(organization_id,user_id,role)"
                + " VALUES(:org,:user,'OWNER')")
        .param("org", id)
        .param("user", user)
        .update();
    audit.record(user, id, "ORGANIZATION_CREATED", "ORGANIZATION", id);
    return get(user, id);
  }

  @Transactional
  public Organization update(UUID user, UUID org, String name, String description, String logoUrl) {
    access.lock(org);
    access.require(user, org, Permission.MANAGE_ORGANIZATION);
    db.sql(
            "UPDATE organizations SET"
                + " name=:name,description=:description,logo_url=:logo,updated_at=now() WHERE"
                + " id=:org")
        .param("name", name.trim())
        .param("description", description)
        .param("logo", logoUrl)
        .param("org", org)
        .update();
    audit.record(user, org, "ORGANIZATION_UPDATED", "ORGANIZATION", org);
    return get(user, org);
  }

  public List<Member> members(UUID user, UUID org) {
    access.require(user, org, Permission.READ);
    return db.sql(
            "SELECT m.user_id,u.name,u.email,m.role,m.joined_at FROM organization_members m JOIN"
                + " users u ON u.id=m.user_id WHERE m.organization_id=:org ORDER BY"
                + " m.joined_at,m.user_id")
        .param("org", org)
        .query(Member.class)
        .list();
  }

  @Transactional
  public void role(UUID actor, UUID org, UUID target, OrgRole role) {
    access.lock(org);
    OrgRole acting = access.require(actor, org, Permission.MANAGE_ORGANIZATION);
    OrgRole existing = memberRole(org, target);
    if (role == OrgRole.OWNER)
      throw ApiException.invalid("Use ownership transfer to grant ownership");
    if (acting == OrgRole.ADMIN && (existing == OrgRole.OWNER || actor.equals(target)))
      throw ApiException.forbidden();
    finalOwner(org, existing);
    db.sql(
            "UPDATE organization_members SET role=:role WHERE organization_id=:org AND"
                + " user_id=:user")
        .param("role", role.name())
        .param("org", org)
        .param("user", target)
        .update();
    audit.record(actor, org, "MEMBER_ROLE_CHANGED", "USER", target);
  }

  @Transactional
  public void remove(UUID actor, UUID org, UUID target) {
    access.lock(org);
    OrgRole acting =
        access.require(
            actor, org, actor.equals(target) ? Permission.READ : Permission.MANAGE_ORGANIZATION);
    OrgRole existing = memberRole(org, target);
    if (!actor.equals(target) && acting == OrgRole.ADMIN && existing == OrgRole.OWNER)
      throw ApiException.forbidden();
    finalOwner(org, existing);
    db.sql(
            "UPDATE tasks SET assignee_id=NULL,version=version+1,updated_at=now() WHERE"
                + " organization_id=:org AND assignee_id=:user AND deleted_at IS NULL")
        .param("org", org)
        .param("user", target)
        .update();
    db.sql("DELETE FROM organization_members WHERE organization_id=:org AND user_id=:user")
        .param("org", org)
        .param("user", target)
        .update();
    audit.record(actor, org, "MEMBER_REMOVED", "USER", target);
  }

  @Transactional
  public void transfer(UUID actor, UUID org, UUID target) {
    access.lock(org);
    access.require(actor, org, Permission.TRANSFER_OWNERSHIP);
    memberRole(org, target);
    if (actor.equals(target)) throw ApiException.invalid("Choose another member");
    db.sql(
            "UPDATE organization_members SET role='OWNER' WHERE organization_id=:org AND"
                + " user_id=:user")
        .param("org", org)
        .param("user", target)
        .update();
    db.sql(
            "UPDATE organization_members SET role='ADMIN' WHERE organization_id=:org AND"
                + " user_id=:user")
        .param("org", org)
        .param("user", actor)
        .update();
    audit.record(actor, org, "OWNERSHIP_TRANSFERRED", "USER", target);
  }

  @Transactional
  public void archive(UUID actor, UUID org) {
    access.lock(org);
    access.require(actor, org, Permission.TRANSFER_OWNERSHIP);
    db.sql("UPDATE organizations SET archived_at=now(),updated_at=now() WHERE id=:org")
        .param("org", org)
        .update();
    audit.record(actor, org, "ORGANIZATION_ARCHIVED", "ORGANIZATION", org);
  }

  private OrgRole memberRole(UUID org, UUID user) {
    return db.sql(
            "SELECT role FROM organization_members WHERE organization_id=:org AND user_id=:user")
        .param("org", org)
        .param("user", user)
        .query(String.class)
        .optional()
        .map(OrgRole::valueOf)
        .orElseThrow(ApiException::missing);
  }

  private void finalOwner(UUID org, OrgRole existing) {
    if (existing == OrgRole.OWNER
        && db.sql(
                    "SELECT count(*) FROM organization_members WHERE organization_id=:org AND"
                        + " role='OWNER'")
                .param("org", org)
                .query(Long.class)
                .single()
            <= 1) throw ApiException.conflict("The final owner cannot leave or be demoted");
  }
}

package com.teamflow.security;

import com.teamflow.common.ApiException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class OrganizationAccess {
  private final JdbcClient db;

  public OrganizationAccess(JdbcClient db) {
    this.db = db;
  }

  public OrgRole require(UUID user, UUID org, Permission permission) {
    OrgRole role =
        db.sql(
                "SELECT m.role FROM organization_members m JOIN organizations o ON"
                    + " o.id=m.organization_id WHERE m.user_id=:user AND m.organization_id=:org")
            .param("user", user)
            .param("org", org)
            .query(String.class)
            .optional()
            .map(OrgRole::valueOf)
            .orElseThrow(ApiException::forbidden);
    boolean allowed =
        switch (permission) {
          case READ -> true;
          case MANAGE_ORGANIZATION -> role.managesOrganization();
          case CREATE_PROJECT -> role.createsProjects();
          case TRANSFER_OWNERSHIP -> role == OrgRole.OWNER;
          default -> false;
        };
    if (!allowed) throw ApiException.forbidden();
    if (permission != Permission.READ
        && db.sql("SELECT archived_at IS NOT NULL FROM organizations WHERE id=:org")
            .param("org", org)
            .query(Boolean.class)
            .single()) throw ApiException.conflict("Organization is archived");
    return role;
  }

  public void lock(UUID org) {
    db.sql("SELECT id FROM organizations WHERE id=:org FOR UPDATE")
        .param("org", org)
        .query(UUID.class)
        .optional()
        .orElseThrow(ApiException::missing);
  }
}

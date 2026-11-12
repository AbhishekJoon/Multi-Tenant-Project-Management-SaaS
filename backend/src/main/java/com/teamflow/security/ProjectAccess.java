package com.teamflow.security;

import com.teamflow.common.ApiException;
import com.teamflow.project.ProjectTypes.*;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class ProjectAccess {
  // Alias p must always be a project, scoped by organization_id in the outer query.
  public static final String VISIBLE =
      "(p.visibility='ORGANIZATION' OR :admin OR EXISTS(SELECT 1 FROM project_members pm WHERE"
          + " pm.organization_id=p.organization_id AND pm.project_id=p.id AND pm.user_id=:user))";
  private final JdbcClient db;
  private final OrganizationAccess organizations;

  public ProjectAccess(JdbcClient db, OrganizationAccess organizations) {
    this.db = db;
    this.organizations = organizations;
  }

  public OrgRole require(UUID user, UUID org, UUID project, Permission permission) {
    OrgRole role = organizations.require(user, org, Permission.READ);
    var scope =
        db.sql(
                "SELECT p.visibility,p.status FROM projects p WHERE p.organization_id=:org AND"
                    + " p.id=:project AND "
                    + VISIBLE)
            .param("org", org)
            .param("project", project)
            .param("admin", role.managesOrganization())
            .param("user", user)
            .query((r, n) -> r.getString("status"))
            .optional()
            .orElseThrow(ApiException::missing);
    if (permission == Permission.READ) return role;
    if (db.sql("SELECT archived_at IS NOT NULL FROM organizations WHERE id=:org")
            .param("org", org)
            .query(Boolean.class)
            .single()
        || scope.equals("ARCHIVED"))
      throw ApiException.conflict("This workspace or project is archived");
    Role projectRole =
        db.sql(
                "SELECT role FROM project_members WHERE organization_id=:org AND"
                    + " project_id=:project AND user_id=:user")
            .param("org", org)
            .param("project", project)
            .param("user", user)
            .query(String.class)
            .optional()
            .map(Role::valueOf)
            .orElse(null);
    if (!allows(role, projectRole, permission)) throw ApiException.forbidden();
    return role;
  }

  public static boolean allows(OrgRole orgRole, Role projectRole, Permission permission) {
    if (permission == Permission.READ) return true;
    if (permission != Permission.MANAGE_PROJECT && permission != Permission.MANAGE_TASK)
      return false;
    if (orgRole == OrgRole.VIEWER) return false;
    if (orgRole.managesOrganization()) return true;
    return projectRole == Role.PROJECT_MANAGER
        || (permission == Permission.MANAGE_TASK && projectRole == Role.CONTRIBUTOR);
  }
}

package com.teamflow.project;

import com.teamflow.activity.ActivityService;
import com.teamflow.audit.AuditService;
import com.teamflow.common.*;
import com.teamflow.project.ProjectTypes.*;
import com.teamflow.security.*;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
  public record Input(
      @NotBlank @Size(max = 150) String name,
      @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9]{1,9}") String projectKey,
      @NotNull @Size(max = 4000) String description,
      @NotNull Status status,
      @NotNull Visibility visibility,
      @NotNull Priority priority,
      LocalDate startDate,
      LocalDate dueDate) {}

  public record Project(
      UUID id,
      UUID organizationId,
      String name,
      String projectKey,
      String description,
      Status status,
      Visibility visibility,
      Priority priority,
      LocalDate startDate,
      LocalDate dueDate,
      Instant createdAt,
      String projectRole) {}

  public record Member(UUID userId, String name, String email, Role role) {}

  private static final String SELECT =
      "SELECT"
          + " p.id,p.organization_id,p.name,p.project_key,p.description,p.status,p.visibility,p.priority,p.start_date,p.due_date,p.created_at,(SELECT"
          + " pm.role FROM project_members pm WHERE pm.organization_id=p.organization_id AND"
          + " pm.project_id=p.id AND pm.user_id=:user) AS project_role FROM projects p ";
  private final JdbcClient db;
  private final OrganizationAccess orgs;
  private final ProjectAccess access;
  private final AuditService audit;
  private final ActivityService activity;

  public ProjectService(
      JdbcClient db,
      OrganizationAccess orgs,
      ProjectAccess access,
      AuditService audit,
      ActivityService activity) {
    this.db = db;
    this.orgs = orgs;
    this.access = access;
    this.audit = audit;
    this.activity = activity;
  }

  public PageResult<Project> list(
      UUID actor,
      UUID org,
      String search,
      Status status,
      Visibility visibility,
      String sort,
      int page,
      int size) {
    OrgRole role = orgs.require(actor, org, Permission.READ);
    size = PageResult.size(size);
    page = PageResult.page(page);
    String where =
        " WHERE p.organization_id=:org AND "
            + ProjectAccess.VISIBLE
            + " AND (:search='' OR lower(p.name) LIKE :pattern OR lower(p.project_key) LIKE"
            + " :pattern)";
    Map<String, Object> params =
        new HashMap<>(
            Map.of(
                "org",
                org,
                "admin",
                role.managesOrganization(),
                "user",
                actor,
                "search",
                search,
                "pattern",
                "%" + search.toLowerCase(Locale.ROOT) + "%"));
    if (status != null) {
      where += " AND p.status=:status";
      params.put("status", status.name());
    }
    if (visibility != null) {
      where += " AND p.visibility=:visibility";
      params.put("visibility", visibility.name());
    }
    String order =
        switch (sort) {
          case "name" -> "p.name ASC,p.id";
          case "dueDate" -> "p.due_date ASC NULLS LAST,p.id";
          default -> "p.created_at DESC,p.id";
        };
    long total =
        db.sql("SELECT count(*) FROM projects p" + where).params(params).query(Long.class).single();
    var items =
        db.sql(SELECT + where + " ORDER BY " + order + " LIMIT :limit OFFSET :offset")
            .params(params)
            .param("limit", size)
            .param("offset", page * size)
            .query(Project.class)
            .list();
    return new PageResult<>(items, total, page, size);
  }

  public Project get(UUID actor, UUID org, UUID id) {
    access.require(actor, org, id, Permission.READ);
    return db.sql(SELECT + " WHERE p.organization_id=:org AND p.id=:id")
        .param("org", org)
        .param("id", id)
        .param("user", actor)
        .query(Project.class)
        .single();
  }

  @Transactional
  public Project create(UUID actor, UUID org, Input input) {
    orgs.lock(org);
    orgs.require(actor, org, Permission.CREATE_PROJECT);
    dates(input);
    if (input.status() == Status.ARCHIVED)
      throw ApiException.invalid("Create an active or planned project before archiving");
    UUID id =
        db.sql(
                "INSERT INTO"
                    + " projects(organization_id,name,project_key,description,status,visibility,priority,start_date,due_date,created_by)"
                    + " VALUES(:org,:name,:key,:description,:status,:visibility,:priority,:start,:due,:actor)"
                    + " RETURNING id")
            .param("org", org)
            .param("name", input.name().trim())
            .param("key", input.projectKey())
            .param("description", input.description())
            .param("status", input.status().name())
            .param("visibility", input.visibility().name())
            .param("priority", input.priority().name())
            .param("start", input.startDate())
            .param("due", input.dueDate())
            .param("actor", actor)
            .query(UUID.class)
            .single();
    db.sql(
            "INSERT INTO project_members(organization_id,project_id,user_id,role)"
                + " VALUES(:org,:project,:user,'PROJECT_MANAGER')")
        .param("org", org)
        .param("project", id)
        .param("user", actor)
        .update();
    activity.record(actor, org, id, null, "PROJECT_CREATED", input.name());
    audit.record(actor, org, "PROJECT_CREATED", "PROJECT", id);
    return get(actor, org, id);
  }

  @Transactional
  public Project update(UUID actor, UUID org, UUID id, Input input) {
    orgs.lock(org);
    access.require(actor, org, id, Permission.MANAGE_PROJECT);
    dates(input);
    Project old = get(actor, org, id);
    if (!old.projectKey().equals(input.projectKey()))
      throw ApiException.invalid("Project keys are immutable to preserve task identifiers");
    db.sql(
            "UPDATE projects SET"
                + " name=:name,description=:description,status=:status,visibility=:visibility,priority=:priority,start_date=:start,due_date=:due,updated_at=now(),archived_at=CASE"
                + " WHEN :status='ARCHIVED' THEN now() ELSE NULL END WHERE organization_id=:org AND"
                + " id=:id")
        .param("name", input.name().trim())
        .param("description", input.description())
        .param("status", input.status().name())
        .param("visibility", input.visibility().name())
        .param("priority", input.priority().name())
        .param("start", input.startDate())
        .param("due", input.dueDate())
        .param("org", org)
        .param("id", id)
        .update();
    activity.record(actor, org, id, null, "PROJECT_UPDATED", input.name());
    audit.record(
        actor,
        org,
        old.visibility() != input.visibility() ? "PROJECT_VISIBILITY_CHANGED" : "PROJECT_UPDATED",
        "PROJECT",
        id);
    return get(actor, org, id);
  }

  @Transactional
  public void archive(UUID actor, UUID org, UUID id) {
    Project old = get(actor, org, id);
    update(
        actor,
        org,
        id,
        new Input(
            old.name(),
            old.projectKey(),
            old.description(),
            Status.ARCHIVED,
            old.visibility(),
            old.priority(),
            old.startDate(),
            old.dueDate()));
  }

  public List<Member> members(UUID actor, UUID org, UUID id) {
    access.require(actor, org, id, Permission.READ);
    return db.sql(
            "SELECT pm.user_id,u.name,u.email,pm.role FROM project_members pm JOIN users u ON"
                + " u.id=pm.user_id WHERE pm.organization_id=:org AND pm.project_id=:id ORDER BY"
                + " u.name,pm.user_id")
        .param("org", org)
        .param("id", id)
        .query(Member.class)
        .list();
  }

  @Transactional
  public void member(UUID actor, UUID org, UUID id, UUID user, Role role) {
    orgs.lock(org);
    access.require(actor, org, id, Permission.MANAGE_PROJECT);
    if (!db.sql(
            "SELECT EXISTS(SELECT 1 FROM organization_members WHERE organization_id=:org AND"
                + " user_id=:user)")
        .param("org", org)
        .param("user", user)
        .query(Boolean.class)
        .single()) throw ApiException.invalid("Choose a member of this organization");
    db.sql(
            "INSERT INTO project_members(organization_id,project_id,user_id,role)"
                + " VALUES(:org,:id,:user,:role) ON CONFLICT(organization_id,project_id,user_id) DO"
                + " UPDATE SET role=EXCLUDED.role")
        .param("org", org)
        .param("id", id)
        .param("user", user)
        .param("role", role.name())
        .update();
    audit.record(actor, org, "PROJECT_MEMBER_CHANGED", "PROJECT", id);
  }

  @Transactional
  public void removeMember(UUID actor, UUID org, UUID id, UUID user) {
    orgs.lock(org);
    access.require(actor, org, id, Permission.MANAGE_PROJECT);
    db.sql(
            "UPDATE tasks SET assignee_id=NULL,version=version+1,updated_at=now() WHERE"
                + " organization_id=:org AND project_id=:id AND assignee_id=:user AND deleted_at IS"
                + " NULL")
        .param("org", org)
        .param("id", id)
        .param("user", user)
        .update();
    if (db.sql(
                "DELETE FROM project_members WHERE organization_id=:org AND project_id=:id AND"
                    + " user_id=:user")
            .param("org", org)
            .param("id", id)
            .param("user", user)
            .update()
        != 1) throw ApiException.missing();
    audit.record(actor, org, "PROJECT_MEMBER_REMOVED", "PROJECT", id);
  }

  private void dates(Input i) {
    if (i.startDate() != null && i.dueDate() != null && i.dueDate().isBefore(i.startDate()))
      throw ApiException.invalid("Due date must be on or after the start date");
  }
}

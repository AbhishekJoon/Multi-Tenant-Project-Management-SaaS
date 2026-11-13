package com.teamflow.task;

import com.teamflow.activity.ActivityService;
import com.teamflow.common.*;
import com.teamflow.notification.NotificationService;
import com.teamflow.project.ProjectTypes.Priority;
import com.teamflow.security.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {
  public enum Status {
    TODO,
    IN_PROGRESS,
    IN_REVIEW,
    BLOCKED,
    DONE
  }

  public record Input(
      @NotBlank @Size(max = 200) String title,
      @NotNull @Size(max = 10000) String description,
      @NotNull Status status,
      @NotNull Priority priority,
      UUID assigneeId,
      LocalDate dueDate,
      @DecimalMin("0") @DecimalMax("999999.99") BigDecimal estimatedEffort,
      @Min(0) Long version) {}

  public record Task(
      UUID id,
      UUID organizationId,
      UUID projectId,
      String identifier,
      long taskNumber,
      String title,
      String description,
      Status status,
      Priority priority,
      UUID reporterId,
      UUID assigneeId,
      String assigneeName,
      LocalDate dueDate,
      BigDecimal estimatedEffort,
      int position,
      long version,
      Instant createdAt,
      Instant updatedAt,
      Instant completedAt) {}

  public record Move(@NotNull Status status, UUID beforeId, @NotNull @Min(0) Long version) {}

  private static final String SELECT =
      "SELECT t.id,t.organization_id,t.project_id,concat(p.project_key,'-',t.task_number) AS"
          + " identifier,t.task_number,t.title,t.description,t.status,t.priority,t.reporter_id,t.assignee_id,u.name"
          + " AS assignee_name,t.due_date,t.estimated_effort,t.position,t.version,t.created_at,t.updated_at,t.completed_at"
          + " FROM tasks t JOIN projects p ON p.id=t.project_id AND"
          + " p.organization_id=t.organization_id LEFT JOIN users u ON u.id=t.assignee_id ";
  private final JdbcClient db;
  private final OrganizationAccess orgs;
  private final ProjectAccess projects;
  private final ActivityService activity;
  private final NotificationService notifications;

  public TaskService(
      JdbcClient db,
      OrganizationAccess orgs,
      ProjectAccess projects,
      ActivityService activity,
      NotificationService notifications) {
    this.db = db;
    this.orgs = orgs;
    this.projects = projects;
    this.activity = activity;
    this.notifications = notifications;
  }

  public PageResult<Task> list(
      UUID actor,
      UUID org,
      UUID project,
      String search,
      Status status,
      Priority priority,
      UUID assignee,
      boolean overdue,
      String sort,
      int page,
      int size) {
    OrgRole role = orgs.require(actor, org, Permission.READ);
    if (project != null) projects.require(actor, org, project, Permission.READ);
    page = PageResult.page(page);
    size = PageResult.size(size);
    String where =
        " WHERE t.organization_id=:org AND t.deleted_at IS NULL AND "
            + ProjectAccess.VISIBLE
            + " AND (:search='' OR lower(t.title) LIKE :pattern OR"
            + " lower(concat(p.project_key,'-',t.task_number)) LIKE :pattern)";
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
    if (project != null) {
      where += " AND t.project_id=:project";
      params.put("project", project);
    }
    if (status != null) {
      where += " AND t.status=:status";
      params.put("status", status.name());
    }
    if (priority != null) {
      where += " AND t.priority=:priority";
      params.put("priority", priority.name());
    }
    if (assignee != null) {
      where += " AND t.assignee_id=:assignee";
      params.put("assignee", assignee);
    }
    if (overdue) where += " AND t.due_date<CURRENT_DATE AND t.status<>'DONE'";
    String order =
        switch (sort) {
          case "dueDate" -> "t.due_date ASC NULLS LAST,t.id";
          case "updatedAt" -> "t.updated_at DESC,t.id";
          case "title" -> "t.title ASC,t.id";
          default -> "t.position ASC,t.id";
        };
    long total =
        db.sql(
                "SELECT count(*) FROM tasks t JOIN projects p ON"
                    + " p.organization_id=t.organization_id AND p.id=t.project_id"
                    + where)
            .params(params)
            .query(Long.class)
            .single();
    var items =
        db.sql(SELECT + where + " ORDER BY " + order + " LIMIT :limit OFFSET :offset")
            .params(params)
            .param("limit", size)
            .param("offset", page * size)
            .query(Task.class)
            .list();
    return new PageResult<>(items, total, page, size);
  }

  public Task get(UUID actor, UUID org, UUID project, UUID id) {
    projects.require(actor, org, project, Permission.READ);
    return db.sql(
            SELECT
                + " WHERE t.organization_id=:org AND t.project_id=:project AND t.id=:id AND"
                + " t.deleted_at IS NULL")
        .param("org", org)
        .param("project", project)
        .param("id", id)
        .query(Task.class)
        .optional()
        .orElseThrow(ApiException::missing);
  }

  @Transactional
  public Task create(UUID actor, UUID org, UUID project, Input input) {
    orgs.lock(org);
    projects.require(actor, org, project, Permission.MANAGE_TASK);
    assignee(org, project, input.assigneeId());
    long number =
        db.sql(
                "UPDATE projects SET next_task_number=next_task_number+1 WHERE organization_id=:org"
                    + " AND id=:project RETURNING next_task_number-1")
            .param("org", org)
            .param("project", project)
            .query(Long.class)
            .single();
    int position = end(org, project, input.status());
    UUID id =
        db.sql(
                "INSERT INTO"
                    + " tasks(organization_id,project_id,task_number,title,description,status,priority,reporter_id,assignee_id,due_date,estimated_effort,position,completed_at)"
                    + " VALUES(:org,:project,:number,:title,:description,:status,:priority,:actor,:assignee,:due,:effort,:position,CASE"
                    + " WHEN :status='DONE' THEN now() ELSE NULL END) RETURNING id")
            .param("org", org)
            .param("project", project)
            .param("number", number)
            .param("title", input.title().trim())
            .param("description", input.description())
            .param("status", input.status().name())
            .param("priority", input.priority().name())
            .param("actor", actor)
            .param("assignee", input.assigneeId())
            .param("due", input.dueDate())
            .param("effort", input.estimatedEffort())
            .param("position", position)
            .query(UUID.class)
            .single();
    activity.record(actor, org, project, id, "TASK_CREATED", input.title());
    if (!actor.equals(input.assigneeId()))
      notifications.send(org, project, input.assigneeId(), "A task has been assigned to you");
    return get(actor, org, project, id);
  }

  @Transactional
  public Task update(UUID actor, UUID org, UUID project, UUID id, Input input) {
    orgs.lock(org);
    projects.require(actor, org, project, Permission.MANAGE_TASK);
    Task old = get(actor, org, project, id);
    assignee(org, project, input.assigneeId());
    if (input.version() == null) throw ApiException.invalid("A task version is required");
    int changed =
        db.sql(
                "UPDATE tasks SET"
                    + " title=:title,description=:description,status=:status,priority=:priority,assignee_id=:assignee,due_date=:due,estimated_effort=:effort,position=:position,version=version+1,updated_at=now(),completed_at=CASE"
                    + " WHEN :status='DONE' THEN coalesce(completed_at,now()) ELSE NULL END WHERE"
                    + " organization_id=:org AND project_id=:project AND id=:id AND"
                    + " version=:version AND deleted_at IS NULL")
            .param("title", input.title().trim())
            .param("description", input.description())
            .param("status", input.status().name())
            .param("priority", input.priority().name())
            .param("assignee", input.assigneeId())
            .param("due", input.dueDate())
            .param("effort", input.estimatedEffort())
            .param(
                "position",
                old.status() == input.status() ? old.position() : end(org, project, input.status()))
            .param("org", org)
            .param("project", project)
            .param("id", id)
            .param("version", input.version())
            .update();
    if (changed != 1)
      throw ApiException.conflict(
          "This task changed while you were editing. Reload it and try again");
    activity.record(actor, org, project, id, "TASK_UPDATED", input.title());
    if (old.status() != input.status()) {
      activity.record(
          actor, org, project, id, "TASK_STATUS_CHANGED", old.status() + " → " + input.status());
      if (!actor.equals(old.assigneeId()))
        notifications.send(org, project, old.assigneeId(), "An assigned task changed status");
    }
    if (old.priority() != input.priority())
      activity.record(
          actor,
          org,
          project,
          id,
          "TASK_PRIORITY_CHANGED",
          old.priority() + " → " + input.priority());
    if (!Objects.equals(old.assigneeId(), input.assigneeId())) {
      activity.record(actor, org, project, id, "TASK_ASSIGNED", "Assignee updated");
      if (!actor.equals(input.assigneeId()))
        notifications.send(org, project, input.assigneeId(), "A task has been assigned to you");
    }
    return get(actor, org, project, id);
  }

  @Transactional
  public void delete(UUID actor, UUID org, UUID project, UUID id, long version) {
    orgs.lock(org);
    projects.require(actor, org, project, Permission.MANAGE_TASK);
    get(actor, org, project, id);
    if (db.sql(
                "UPDATE tasks SET deleted_at=now(),version=version+1,updated_at=now() WHERE"
                    + " organization_id=:org AND project_id=:project AND id=:id AND"
                    + " version=:version AND deleted_at IS NULL")
            .param("org", org)
            .param("project", project)
            .param("id", id)
            .param("version", version)
            .update()
        != 1) throw ApiException.conflict("Task changed. Reload before deleting");
    activity.record(actor, org, project, id, "TASK_DELETED", "Task moved to retained storage");
  }

  @Transactional
  public Task move(UUID actor, UUID org, UUID project, UUID id, Move move) {
    orgs.lock(org);
    projects.require(actor, org, project, Permission.MANAGE_TASK);
    Task task = get(actor, org, project, id);
    if (task.version() != move.version())
      throw ApiException.conflict("The board changed. Refresh and try again");
    var ids =
        new ArrayList<>(
            db.sql(
                    "SELECT id FROM tasks WHERE organization_id=:org AND project_id=:project AND"
                        + " status=:status AND deleted_at IS NULL AND id<>:id ORDER BY position,id")
                .param("org", org)
                .param("project", project)
                .param("status", move.status().name())
                .param("id", id)
                .query(UUID.class)
                .list());
    int index = move.beforeId() == null ? ids.size() : ids.indexOf(move.beforeId());
    if (index < 0) throw ApiException.invalid("The target card is not in this project and column");
    ids.add(index, id);
    // Organization lock serializes board writers. Versions of shifted cards also change.
    for (int i = 0; i < ids.size(); i++)
      db.sql(
              "UPDATE tasks SET position=:position,version=version+1,updated_at=now() WHERE"
                  + " organization_id=:org AND project_id=:project AND id=:id AND id<>:moved AND"
                  + " position<>:position")
          .param("position", i)
          .param("org", org)
          .param("project", project)
          .param("id", ids.get(i))
          .param("moved", id)
          .update();
    db.sql(
            "UPDATE tasks SET"
                + " status=:status,position=:position,version=version+1,updated_at=now(),completed_at=CASE"
                + " WHEN :status='DONE' THEN coalesce(completed_at,now()) ELSE NULL END WHERE"
                + " organization_id=:org AND project_id=:project AND id=:id AND version=:version")
        .param("status", move.status().name())
        .param("position", index)
        .param("org", org)
        .param("project", project)
        .param("id", id)
        .param("version", move.version())
        .update();
    activity.record(actor, org, project, id, "TASK_MOVED", task.status() + " → " + move.status());
    if (task.status() != move.status() && !actor.equals(task.assigneeId()))
      notifications.send(org, project, task.assigneeId(), "An assigned task changed status");
    return get(actor, org, project, id);
  }

  private int end(UUID org, UUID project, Status status) {
    return db.sql(
            "SELECT coalesce(max(position),-1)+1 FROM tasks WHERE organization_id=:org AND"
                + " project_id=:project AND status=:status AND deleted_at IS NULL")
        .param("org", org)
        .param("project", project)
        .param("status", status.name())
        .query(Integer.class)
        .single();
  }

  private void assignee(UUID org, UUID project, UUID user) {
    if (user != null
        && !db.sql(
                "SELECT EXISTS(SELECT 1 FROM project_members pm JOIN organization_members om ON"
                    + " om.organization_id=pm.organization_id AND om.user_id=pm.user_id WHERE"
                    + " pm.organization_id=:org AND pm.project_id=:project AND pm.user_id=:user AND"
                    + " pm.role<>'VIEWER' AND om.role<>'VIEWER')")
            .param("org", org)
            .param("project", project)
            .param("user", user)
            .query(Boolean.class)
            .single())
      throw ApiException.invalid("Assignee must be a contributing member of this project");
  }
}

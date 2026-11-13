package com.teamflow.dashboard;

import com.teamflow.common.PageResult;
import com.teamflow.security.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DashboardService {
  public record Stats(
      long totalProjects,
      long totalTasks,
      long completedTasks,
      long inProgressTasks,
      long blockedTasks,
      long overdueTasks,
      long members,
      long myTasks) {}

  public record Distribution(String name, long value) {}

  public record Progress(UUID id, String name, long total, long completed) {}

  public record Activity(
      UUID id,
      UUID projectId,
      UUID taskId,
      String actorName,
      String action,
      String detail,
      Instant createdAt) {}

  public record Notification(
      UUID id, String message, UUID projectId, Instant readAt, Instant createdAt) {}

  public record Audit(
      UUID id,
      UUID actorId,
      String action,
      String resourceType,
      UUID resourceId,
      Instant createdAt) {}

  public record Dashboard(
      Stats stats,
      List<Distribution> status,
      List<Distribution> priority,
      List<Distribution> workload,
      List<Distribution> completedOverTime,
      List<Progress> projects,
      List<Activity> activity) {}

  private final JdbcClient db;
  private final OrganizationAccess orgs;
  private final ProjectAccess projects;

  public DashboardService(JdbcClient db, OrganizationAccess orgs, ProjectAccess projects) {
    this.db = db;
    this.orgs = orgs;
    this.projects = projects;
  }

  public Dashboard dashboard(UUID actor, UUID org, UUID project) {
    OrgRole role = orgs.require(actor, org, Permission.READ);
    if (project != null) projects.require(actor, org, project, Permission.READ);
    Map<String, Object> params =
        new HashMap<>(Map.of("org", org, "user", actor, "admin", role.managesOrganization()));
    String where = " WHERE p.organization_id=:org AND " + ProjectAccess.VISIBLE;
    if (project != null) {
      where += " AND p.id=:project";
      params.put("project", project);
    }
    long count =
        db.sql("SELECT count(*) FROM projects p" + where + " AND p.status<>'ARCHIVED'")
            .params(params)
            .query(Long.class)
            .single();
    String taskJoin =
        " FROM tasks t JOIN projects p ON p.id=t.project_id AND"
            + " p.organization_id=t.organization_id";
    String taskWhere = where + " AND t.deleted_at IS NULL";
    var values =
        db.sql(
                "SELECT count(*) AS total_tasks,count(*) FILTER(WHERE t.status='DONE') AS"
                    + " completed_tasks,count(*) FILTER(WHERE t.status='IN_PROGRESS') AS"
                    + " in_progress_tasks,count(*) FILTER(WHERE t.status='BLOCKED') AS"
                    + " blocked_tasks,count(*) FILTER(WHERE t.due_date<CURRENT_DATE AND"
                    + " t.status<>'DONE') AS overdue_tasks,count(*) FILTER(WHERE"
                    + " t.assignee_id=:user AND t.status<>'DONE') AS my_tasks"
                    + taskJoin
                    + taskWhere)
            .params(params)
            .query(
                (r, n) ->
                    new long[] {
                      r.getLong(1),
                      r.getLong(2),
                      r.getLong(3),
                      r.getLong(4),
                      r.getLong(5),
                      r.getLong(6)
                    })
            .single();
    long members =
        db.sql("SELECT count(*) FROM organization_members WHERE organization_id=:org")
            .param("org", org)
            .query(Long.class)
            .single();
    Stats stats =
        new Stats(count, values[0], values[1], values[2], values[3], values[4], members, values[5]);
    var status =
        db.sql(
                "SELECT t.status AS name,count(*) AS value"
                    + taskJoin
                    + taskWhere
                    + " GROUP BY t.status ORDER BY t.status")
            .params(params)
            .query(Distribution.class)
            .list();
    var priority =
        db.sql(
                "SELECT t.priority AS name,count(*) AS value"
                    + taskJoin
                    + taskWhere
                    + " GROUP BY t.priority ORDER BY t.priority")
            .params(params)
            .query(Distribution.class)
            .list();
    var workload =
        db.sql(
                "SELECT coalesce(u.name,'Unassigned') AS name,count(*) AS value"
                    + taskJoin
                    + " LEFT JOIN users u ON u.id=t.assignee_id"
                    + taskWhere
                    + " AND t.status<>'DONE' GROUP BY t.assignee_id,u.name ORDER BY value DESC,name"
                    + " LIMIT 20")
            .params(params)
            .query(Distribution.class)
            .list();
    var trend =
        db.sql(
                "SELECT to_char(t.completed_at AT TIME ZONE 'UTC','YYYY-MM-DD') AS name,count(*) AS"
                    + " value"
                    + taskJoin
                    + taskWhere
                    + " AND t.completed_at>=now()-interval '30 days' GROUP BY 1 ORDER BY 1")
            .params(params)
            .query(Distribution.class)
            .list();
    var progress =
        db.sql(
                "SELECT p.id,p.name,count(t.id) AS total,count(t.id) FILTER(WHERE t.status='DONE')"
                    + " AS completed FROM projects p LEFT JOIN tasks t ON"
                    + " t.organization_id=p.organization_id AND t.project_id=p.id AND t.deleted_at"
                    + " IS NULL"
                    + where
                    + " GROUP BY p.id ORDER BY p.created_at DESC,p.id LIMIT 12")
            .params(params)
            .query(Progress.class)
            .list();
    return new Dashboard(
        stats,
        status,
        priority,
        workload,
        trend,
        progress,
        activities(actor, org, project, null, 0, 10).items());
  }

  public PageResult<Activity> activities(
      UUID actor, UUID org, UUID project, UUID task, int page, int size) {
    OrgRole role = orgs.require(actor, org, Permission.READ);
    if (project != null) projects.require(actor, org, project, Permission.READ);
    var params =
        new HashMap<String, Object>(
            Map.of("org", org, "user", actor, "admin", role.managesOrganization()));
    String where = " WHERE a.organization_id=:org AND " + ProjectAccess.VISIBLE;
    if (project != null) {
      where += " AND a.project_id=:project";
      params.put("project", project);
    }
    if (task != null) {
      where += " AND a.task_id=:task";
      params.put("task", task);
    }
    String join =
        " FROM task_activity a JOIN projects p ON p.organization_id=a.organization_id AND"
            + " p.id=a.project_id JOIN users u ON u.id=a.actor_id";
    long total = db.sql("SELECT count(*)" + join + where).params(params).query(Long.class).single();
    page = PageResult.page(page);
    size = PageResult.size(size);
    var items =
        db.sql(
                "SELECT a.id,a.project_id,a.task_id,u.name AS"
                    + " actor_name,a.action,a.detail,a.created_at"
                    + join
                    + where
                    + " ORDER BY a.created_at DESC,a.id LIMIT :limit OFFSET :offset")
            .params(params)
            .param("limit", size)
            .param("offset", page * size)
            .query(Activity.class)
            .list();
    return new PageResult<>(items, total, page, size);
  }

  public PageResult<Notification> notifications(UUID actor, UUID org, int page, int size) {
    OrgRole role = orgs.require(actor, org, Permission.READ);
    page = PageResult.page(page);
    size = PageResult.size(size);
    String where =
        " WHERE n.organization_id=:org AND n.user_id=:user AND (n.project_id IS NULL OR"
            + " EXISTS(SELECT 1 FROM projects p WHERE p.organization_id=n.organization_id AND"
            + " p.id=n.project_id AND "
            + ProjectAccess.VISIBLE
            + "))";
    var params = Map.of("org", org, "user", actor, "admin", role.managesOrganization());
    long total =
        db.sql("SELECT count(*) FROM notifications n" + where)
            .params(params)
            .query(Long.class)
            .single();
    var items =
        db.sql(
                "SELECT n.id,n.message,n.project_id,n.read_at,n.created_at FROM notifications n"
                    + where
                    + " ORDER BY n.created_at DESC,n.id LIMIT :limit OFFSET :offset")
            .params(params)
            .param("limit", size)
            .param("offset", page * size)
            .query(Notification.class)
            .list();
    return new PageResult<>(items, total, page, size);
  }

  @Transactional
  public void read(UUID actor, UUID org, UUID id) {
    orgs.require(actor, org, Permission.READ);
    String condition = id == null ? "" : " AND id=:id";
    var query =
        db.sql(
                "UPDATE notifications SET read_at=now() WHERE organization_id=:org AND"
                    + " user_id=:user"
                    + condition)
            .param("org", org)
            .param("user", actor);
    if (id != null) query.param("id", id);
    query.update();
  }

  public PageResult<Audit> audits(UUID actor, UUID org, int page, int size) {
    orgs.require(actor, org, Permission.MANAGE_ORGANIZATION);
    page = PageResult.page(page);
    size = PageResult.size(size);
    long total =
        db.sql("SELECT count(*) FROM audit_logs WHERE organization_id=:org")
            .param("org", org)
            .query(Long.class)
            .single();
    var items =
        db.sql(
                "SELECT id,actor_id,action,resource_type,resource_id,created_at FROM audit_logs"
                    + " WHERE organization_id=:org ORDER BY created_at DESC,id LIMIT :limit OFFSET"
                    + " :offset")
            .param("org", org)
            .param("limit", size)
            .param("offset", page * size)
            .query(Audit.class)
            .list();
    return new PageResult<>(items, total, page, size);
  }
}

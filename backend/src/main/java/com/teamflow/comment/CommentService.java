package com.teamflow.comment;

import com.teamflow.activity.ActivityService;
import com.teamflow.common.*;
import com.teamflow.notification.NotificationService;
import com.teamflow.security.*;
import com.teamflow.task.TaskService;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommentService {
  public record Comment(
      UUID id,
      UUID authorId,
      String authorName,
      String body,
      Instant createdAt,
      Instant updatedAt) {}

  private static final String SELECT =
      "SELECT c.id,c.author_id,u.name AS author_name,c.body,c.created_at,c.updated_at FROM"
          + " task_comments c JOIN users u ON u.id=c.author_id ";
  private final JdbcClient db;
  private final TaskService tasks;
  private final OrganizationAccess orgs;
  private final ProjectAccess projects;
  private final ActivityService activity;
  private final NotificationService notifications;

  public CommentService(
      JdbcClient db,
      TaskService tasks,
      OrganizationAccess orgs,
      ProjectAccess projects,
      ActivityService activity,
      NotificationService notifications) {
    this.db = db;
    this.tasks = tasks;
    this.orgs = orgs;
    this.projects = projects;
    this.activity = activity;
    this.notifications = notifications;
  }

  public PageResult<Comment> list(
      UUID actor, UUID org, UUID project, UUID task, int page, int size) {
    tasks.get(actor, org, project, task);
    page = PageResult.page(page);
    size = PageResult.size(size);
    var params = Map.of("org", org, "project", project, "task", task);
    String where = " WHERE c.organization_id=:org AND c.project_id=:project AND c.task_id=:task";
    long total =
        db.sql("SELECT count(*) FROM task_comments c" + where)
            .params(params)
            .query(Long.class)
            .single();
    var items =
        db.sql(SELECT + where + " ORDER BY c.created_at,c.id LIMIT :limit OFFSET :offset")
            .params(params)
            .param("limit", size)
            .param("offset", page * size)
            .query(Comment.class)
            .list();
    return new PageResult<>(items, total, page, size);
  }

  @Transactional
  public Comment create(UUID actor, UUID org, UUID project, UUID task, String body) {
    orgs.lock(org);
    projects.require(actor, org, project, Permission.MANAGE_TASK);
    var current = tasks.get(actor, org, project, task);
    UUID id =
        db.sql(
                "INSERT INTO task_comments(organization_id,project_id,task_id,author_id,body)"
                    + " VALUES(:org,:project,:task,:actor,:body) RETURNING id")
            .param("org", org)
            .param("project", project)
            .param("task", task)
            .param("actor", actor)
            .param("body", body.trim())
            .query(UUID.class)
            .single();
    activity.record(actor, org, project, task, "COMMENT_ADDED", "Comment added");
    var recipients = new HashSet<UUID>();
    recipients.add(current.reporterId());
    if (current.assigneeId() != null) recipients.add(current.assigneeId());
    recipients.remove(actor);
    for (UUID user : recipients)
      notifications.send(org, project, user, "A comment was added to a relevant task");
    return get(org, project, task, id);
  }

  @Transactional
  public Comment update(UUID actor, UUID org, UUID project, UUID task, UUID id, String body) {
    orgs.lock(org);
    projects.require(actor, org, project, Permission.MANAGE_TASK);
    tasks.get(actor, org, project, task);
    Comment comment = get(org, project, task, id);
    if (!comment.authorId().equals(actor)) throw ApiException.forbidden();
    db.sql(
            "UPDATE task_comments SET body=:body,updated_at=now() WHERE organization_id=:org AND"
                + " project_id=:project AND task_id=:task AND id=:id")
        .param("body", body.trim())
        .param("org", org)
        .param("project", project)
        .param("task", task)
        .param("id", id)
        .update();
    activity.record(actor, org, project, task, "COMMENT_EDITED", "Comment edited");
    return get(org, project, task, id);
  }

  @Transactional
  public void delete(UUID actor, UUID org, UUID project, UUID task, UUID id) {
    orgs.lock(org);
    OrgRole role = projects.require(actor, org, project, Permission.MANAGE_TASK);
    tasks.get(actor, org, project, task);
    Comment comment = get(org, project, task, id);
    if (!comment.authorId().equals(actor) && !role.managesOrganization())
      throw ApiException.forbidden();
    db.sql(
            "DELETE FROM task_comments WHERE organization_id=:org AND project_id=:project AND"
                + " task_id=:task AND id=:id")
        .param("org", org)
        .param("project", project)
        .param("task", task)
        .param("id", id)
        .update();
    activity.record(actor, org, project, task, "COMMENT_DELETED", "Comment deleted");
  }

  private Comment get(UUID org, UUID project, UUID task, UUID id) {
    return db.sql(
            SELECT
                + " WHERE c.organization_id=:org AND c.project_id=:project AND c.task_id=:task AND"
                + " c.id=:id")
        .param("org", org)
        .param("project", project)
        .param("task", task)
        .param("id", id)
        .query(Comment.class)
        .optional()
        .orElseThrow(ApiException::missing);
  }
}

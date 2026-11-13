package com.teamflow.activity;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class ActivityService {
  private final JdbcClient db;

  public ActivityService(JdbcClient db) {
    this.db = db;
  }

  public void record(UUID actor, UUID org, UUID project, UUID task, String action, String detail) {
    db.sql(
            "INSERT INTO task_activity(actor_id,organization_id,project_id,task_id,action,detail)"
                + " VALUES(:actor,:org,:project,:task,:action,:detail)")
        .param("actor", actor)
        .param("org", org)
        .param("project", project)
        .param("task", task)
        .param("action", action)
        .param("detail", detail)
        .update();
  }
}

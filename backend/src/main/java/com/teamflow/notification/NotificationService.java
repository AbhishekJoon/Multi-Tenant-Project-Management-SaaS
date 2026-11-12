package com.teamflow.notification;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {
  private final JdbcClient db;

  public NotificationService(JdbcClient db) {
    this.db = db;
  }

  public void send(UUID org, UUID project, UUID user, String message) {
    if (user != null)
      db.sql(
              "INSERT INTO notifications(organization_id,project_id,user_id,message) SELECT"
                  + " :org,:project,:user,:message WHERE EXISTS(SELECT 1 FROM organization_members"
                  + " WHERE organization_id=:org AND user_id=:user)")
          .param("org", org)
          .param("project", project)
          .param("user", user)
          .param("message", message)
          .update();
  }
}

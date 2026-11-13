package com.teamflow.audit;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
  private final JdbcClient db;

  public AuditService(JdbcClient db) {
    this.db = db;
  }

  public void record(UUID actor, UUID org, String action, String type, UUID resource) {
    db.sql(
            "INSERT INTO audit_logs(actor_id,organization_id,action,resource_type,resource_id)"
                + " VALUES(:actor,:org,:action,:type,:resource)")
        .param("actor", actor)
        .param("org", org)
        .param("action", action)
        .param("type", type)
        .param("resource", resource)
        .update();
  }
}

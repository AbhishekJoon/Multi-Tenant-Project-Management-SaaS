package com.teamflow;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "TEAMFLOW_TEST_PRIVATE_SCHEMA", matches = "true")
class SupabaseSchemaIntegrationTest extends ApiTestSupport {
  @Autowired JdbcClient db;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

  @Test
  void migrationsAndApplicationTablesStayOutOfPublic() {
    assertThat(db.sql("SELECT current_schema()").query(String.class).single())
        .isEqualTo("teamflow");
    assertThat(
            db.sql("SELECT count(*) FROM teamflow.flyway_schema_history WHERE success")
                .query(Long.class)
                .single())
        .isEqualTo(4);
    assertThat(
            db.sql(
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND"
                        + " table_name IN"
                        + " ('users','refresh_tokens','organizations','tasks','flyway_schema_history')")
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void dataApiRolesCannotReadBackendTablesOrFutureTables() {
    db.sql("CREATE TABLE teamflow.future_grants_probe(id integer)").update();
    try {
      for (String role : List.of("anon", "authenticated", "service_role")) {
        assertThat(
                db.sql("SELECT has_schema_privilege(:role,'teamflow','USAGE')")
                    .param("role", role)
                    .query(Boolean.class)
                    .single())
            .isFalse();
        for (String table :
            List.of(
                "teamflow.users",
                "teamflow.refresh_tokens",
                "teamflow.tasks",
                "teamflow.future_grants_probe")) {
          assertThat(
                  db.sql("SELECT has_table_privilege(:role,:table,'SELECT')")
                      .param("role", role)
                      .param("table", table)
                      .query(Boolean.class)
                      .single())
              .isFalse();
        }
      }
    } finally {
      db.sql("DROP TABLE teamflow.future_grants_probe").update();
    }
  }

  @Test
  void directSqlAsAnAnonymousRoleIsDenied() {
    var transaction = new TransactionTemplate(transactions);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      db.sql("SET LOCAL ROLE anon").update();
                      db.sql("SELECT count(*) FROM teamflow.users").query(Long.class).single();
                    }))
        .isInstanceOf(DataAccessException.class);
  }
}

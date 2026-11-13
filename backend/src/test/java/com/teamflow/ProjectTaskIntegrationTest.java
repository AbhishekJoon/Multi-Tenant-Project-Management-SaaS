package com.teamflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.teamflow.common.ApiException;
import com.teamflow.project.ProjectTypes.Priority;
import com.teamflow.task.TaskService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

class ProjectTaskIntegrationTest extends ApiTestSupport {
  @Autowired JdbcClient db;
  @Autowired TaskService tasks;

  String project(Account a, String org, String visibility) throws Exception {
    return body(perform(
                as(post("/api/v1/organizations/" + org + "/projects"), a)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "name",
                                "Launch",
                                "projectKey",
                                "TF" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(),
                                "description",
                                "",
                                "status",
                                "ACTIVE",
                                "visibility",
                                visibility,
                                "priority",
                                "MEDIUM"))))
            .andExpect(status().isCreated())
            .andReturn())
        .path("id")
        .asText();
  }

  String base(String org, String project) {
    return "/api/v1/organizations/" + org + "/projects/" + project;
  }

  Map<String, Object> input(String title) {
    return new HashMap<>(
        Map.of(
            "title",
            title,
            "description",
            "A real persisted task",
            "status",
            "TODO",
            "priority",
            "HIGH",
            "version",
            0));
  }

  JsonNode task(Account a, String org, String project) throws Exception {
    return body(
        perform(
                as(post(base(org, project) + "/tasks"), a)
                    .content(json.writeValueAsString(input("Ship workspace"))))
            .andExpect(status().isCreated())
            .andReturn());
  }

  @Test
  void privateProjectAccessAndListsAreScoped() throws Exception {
    var owner = account();
    var member = account();
    String org = org(owner);
    join(owner, org, member, "MEMBER");
    String privateId = project(owner, org, "PRIVATE");
    project(owner, org, "ORGANIZATION");
    perform(as(get(base(org, privateId)), member)).andExpect(status().isNotFound());
    perform(as(get("/api/v1/organizations/" + org + "/projects"), member))
        .andExpect(jsonPath("$.total").value(1));
    perform(
            as(post(base(org, privateId) + "/members"), owner)
                .content(
                    json.writeValueAsString(Map.of("userId", member.id(), "role", "CONTRIBUTOR"))))
        .andExpect(status().isNoContent());
    perform(as(get(base(org, privateId)), member)).andExpect(status().isOk());
    task(member, org, privateId);
  }

  @Test
  void crossTenantAndMixedParentReadsWritesAndDeletesFail() throws Exception {
    var a = account();
    var b = account();
    String orgA = org(a),
        orgB = org(b),
        pA = project(a, orgA, "ORGANIZATION"),
        pB = project(b, orgB, "ORGANIZATION");
    var t = task(b, orgB, pB);
    String id = t.path("id").asText();
    perform(as(get(base(orgB, pB)), a)).andExpect(status().isForbidden());
    perform(as(get(base(orgA, pB)), a)).andExpect(status().isNotFound());
    perform(
            as(patch(base(orgA, pA) + "/tasks/" + id), a)
                .content(json.writeValueAsString(input("Stolen"))))
        .andExpect(status().isNotFound());
    perform(as(delete(base(orgA, pA) + "/tasks/" + id + "?version=0"), a))
        .andExpect(status().isNotFound());
    perform(
            as(post(base(orgA, pA) + "/tasks/" + id + "/comments"), a)
                .content("{\"body\":\"Leaked\"}"))
        .andExpect(status().isNotFound());
    perform(as(get(base(orgB, pB) + "/tasks/" + id), b))
        .andExpect(jsonPath("$.title").value("Ship workspace"));
  }

  @Test
  void assignmentRejectsForeignUsersAndDatabaseConstraintsBackItUp() throws Exception {
    var a = account();
    var b = account();
    String orgA = org(a), orgB = org(b), pA = project(a, orgA, "ORGANIZATION");
    project(b, orgB, "ORGANIZATION");
    var input = input("Wrong assignment");
    input.put("assigneeId", b.id());
    perform(as(post(base(orgA, pA) + "/tasks"), a).content(json.writeValueAsString(input)))
        .andExpect(status().isBadRequest());
    var task = task(a, orgA, pA);
    assertThatThrownBy(
            () ->
                db.sql(
                        "UPDATE tasks SET assignee_id=cast(:user as uuid) WHERE"
                            + " organization_id=cast(:org as uuid) AND id=cast(:id as uuid)")
                    .param("user", b.id())
                    .param("org", orgA)
                    .param("id", task.path("id").asText())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void staleUpdatesAndMovesReturnConflictAndOrderingPersists() throws Exception {
    var a = account();
    String org = org(a), p = project(a, org, "ORGANIZATION");
    var first = task(a, org, p);
    var second = task(a, org, p);
    String endpoint = base(org, p) + "/tasks/" + first.path("id").asText();
    perform(as(patch(endpoint), a).content(json.writeValueAsString(input("Renamed"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));
    perform(as(patch(endpoint), a).content(json.writeValueAsString(input("Lost write"))))
        .andExpect(status().isConflict());
    perform(as(patch(endpoint + "/move"), a).content("{\"status\":\"DONE\",\"version\":0}"))
        .andExpect(status().isConflict());
    perform(as(patch(endpoint + "/move"), a).content("{\"status\":\"DONE\",\"version\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.completedAt").isNotEmpty());
    perform(as(get(endpoint), a)).andExpect(jsonPath("$.status").value("DONE"));
    perform(
            as(patch(endpoint + "/move"), a)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "status",
                            "TODO",
                            "version",
                            2,
                            "beforeId",
                            second.path("id").asText()))))
        .andExpect(status().isOk());
    perform(as(get(base(org, p) + "/tasks"), a))
        .andExpect(jsonPath("$.items[0].id").value(first.path("id").asText()));
  }

  @Test
  void concurrentUpdatesHaveOneWinnerAndTaskNumbersAreUnique() throws Exception {
    var a = account();
    String org = org(a), p = project(a, org, "ORGANIZATION");
    var t = task(a, org, p);
    UUID actor = UUID.fromString(a.id()),
        tenant = UUID.fromString(org),
        project = UUID.fromString(p),
        id = UUID.fromString(t.path("id").asText());
    try (var executor = Executors.newFixedThreadPool(4)) {
      Callable<Boolean> edit =
          () -> {
            try {
              tasks.update(
                  actor,
                  tenant,
                  project,
                  id,
                  new TaskService.Input(
                      "Concurrent",
                      "",
                      TaskService.Status.TODO,
                      Priority.HIGH,
                      null,
                      null,
                      null,
                      0L));
              return true;
            } catch (ApiException e) {
              assertThat(e.status().value()).isEqualTo(409);
              return false;
            }
          };
      var writes = executor.invokeAll(List.of(edit, edit));
      assertThat(writes.get(0).get() ^ writes.get(1).get()).isTrue();
      Callable<Long> create =
          () ->
              tasks
                  .create(
                      actor,
                      tenant,
                      project,
                      new TaskService.Input(
                          "Parallel",
                          "",
                          TaskService.Status.TODO,
                          Priority.LOW,
                          null,
                          null,
                          null,
                          null))
                  .taskNumber();
      var created = executor.invokeAll(List.of(create, create, create, create));
      var numbers = new HashSet<Long>();
      for (var result : created) numbers.add(result.get());
      assertThat(numbers).hasSize(4);
    }
  }

  @Test
  void viewerCannotEscalateViaProjectRoleAndRevocationTakesEffect() throws Exception {
    var owner = account();
    var viewer = account();
    String org = org(owner);
    join(owner, org, viewer, "VIEWER");
    String p = project(owner, org, "PRIVATE");
    perform(
            as(post(base(org, p) + "/members"), owner)
                .content(
                    json.writeValueAsString(
                        Map.of("userId", viewer.id(), "role", "PROJECT_MANAGER"))))
        .andExpect(status().isNoContent());
    perform(as(get(base(org, p)), viewer)).andExpect(status().isOk());
    perform(
            as(post(base(org, p) + "/tasks"), viewer)
                .content(json.writeValueAsString(input("Escalation"))))
        .andExpect(status().isForbidden());
    perform(as(delete(base(org, p) + "/members/" + viewer.id()), owner))
        .andExpect(status().isNoContent());
    perform(as(get(base(org, p)), viewer)).andExpect(status().isNotFound());
  }

  @Test
  void commentsActivityNotificationsAndAnalyticsUseRealScopedData() throws Exception {
    var owner = account();
    var member = account();
    String org = org(owner);
    join(owner, org, member, "MEMBER");
    String p = project(owner, org, "PRIVATE");
    perform(
            as(post(base(org, p) + "/members"), owner)
                .content(
                    json.writeValueAsString(Map.of("userId", member.id(), "role", "CONTRIBUTOR"))))
        .andExpect(status().isNoContent());
    var input = input("Assigned task");
    input.put("assigneeId", member.id());
    var t =
        body(
            perform(
                    as(post(base(org, p) + "/tasks"), owner)
                        .content(json.writeValueAsString(input)))
                .andExpect(status().isCreated())
                .andReturn());
    String endpoint = base(org, p) + "/tasks/" + t.path("id").asText();
    var comment =
        body(
            perform(
                    as(post(endpoint + "/comments"), member)
                        .content("{\"body\":\"Ready for review\"}"))
                .andExpect(status().isCreated())
                .andReturn());
    perform(as(get(endpoint + "/comments"), owner)).andExpect(jsonPath("$.total").value(1));
    perform(
            as(patch(endpoint + "/comments/" + comment.path("id").asText()), owner)
                .content("{\"body\":\"Forged\"}"))
        .andExpect(status().isForbidden());
    perform(as(get("/api/v1/organizations/" + org + "/notifications"), member))
        .andExpect(jsonPath("$.total").value(1));
    perform(as(get("/api/v1/organizations/" + org + "/dashboard"), member))
        .andExpect(jsonPath("$.stats.totalTasks").value(1))
        .andExpect(jsonPath("$.stats.myTasks").value(1));
    perform(as(get(endpoint + "/activity"), owner)).andExpect(jsonPath("$.total").value(2));
    perform(as(delete(base(org, p) + "/members/" + member.id()), owner))
        .andExpect(status().isNoContent());
    perform(as(get("/api/v1/organizations/" + org + "/dashboard"), member))
        .andExpect(jsonPath("$.stats.totalTasks").value(0));
    perform(as(get("/api/v1/organizations/" + org + "/notifications"), member))
        .andExpect(jsonPath("$.total").value(0));
    perform(as(get(endpoint + "/comments"), member)).andExpect(status().isNotFound());
  }

  @Test
  void softDeleteAndArchivePreventMutation() throws Exception {
    var a = account();
    String org = org(a), p = project(a, org, "ORGANIZATION");
    var t = task(a, org, p);
    perform(as(delete(base(org, p) + "/tasks/" + t.path("id").asText() + "?version=0"), a))
        .andExpect(status().isNoContent());
    perform(as(get(base(org, p) + "/tasks"), a)).andExpect(jsonPath("$.total").value(0));
    perform(as(post(base(org, p) + "/archive"), a)).andExpect(status().isNoContent());
    perform(
            as(post(base(org, p) + "/tasks"), a)
                .content(json.writeValueAsString(input("Archived"))))
        .andExpect(status().isConflict());
  }
}

package com.teamflow.task;

import com.teamflow.common.PageResult;
import com.teamflow.project.ProjectTypes.Priority;
import com.teamflow.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{org}")
public class TaskController {
  private final TaskService service;

  public TaskController(TaskService service) {
    this.service = service;
  }

  @GetMapping({"/projects/{project}/tasks", "/tasks"})
  PageResult<TaskService.Task> list(
      @AuthenticationPrincipal Actor a,
      @PathVariable UUID org,
      @PathVariable(required = false) UUID project,
      @RequestParam(defaultValue = "") @Size(max = 200) String search,
      @RequestParam(required = false) TaskService.Status status,
      @RequestParam(required = false) Priority priority,
      @RequestParam(required = false) UUID assignee,
      @RequestParam(defaultValue = "false") boolean overdue,
      @RequestParam(defaultValue = "position") String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return service.list(
        a.id(), org, project, search, status, priority, assignee, overdue, sort, page, size);
  }

  @PostMapping("/projects/{project}/tasks")
  @ResponseStatus(HttpStatus.CREATED)
  TaskService.Task create(
      @AuthenticationPrincipal Actor a,
      @PathVariable UUID org,
      @PathVariable UUID project,
      @Valid @RequestBody TaskService.Input input) {
    return service.create(a.id(), org, project, input);
  }

  @GetMapping("/projects/{project}/tasks/{id}")
  TaskService.Task get(
      @AuthenticationPrincipal Actor a,
      @PathVariable UUID org,
      @PathVariable UUID project,
      @PathVariable UUID id) {
    return service.get(a.id(), org, project, id);
  }

  @PatchMapping("/projects/{project}/tasks/{id}")
  TaskService.Task update(
      @AuthenticationPrincipal Actor a,
      @PathVariable UUID org,
      @PathVariable UUID project,
      @PathVariable UUID id,
      @Valid @RequestBody TaskService.Input input) {
    return service.update(a.id(), org, project, id, input);
  }

  @DeleteMapping("/projects/{project}/tasks/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(
      @AuthenticationPrincipal Actor a,
      @PathVariable UUID org,
      @PathVariable UUID project,
      @PathVariable UUID id,
      @RequestParam long version) {
    service.delete(a.id(), org, project, id, version);
  }

  @PatchMapping({"/projects/{project}/tasks/{id}/status", "/projects/{project}/tasks/{id}/move"})
  TaskService.Task move(
      @AuthenticationPrincipal Actor a,
      @PathVariable UUID org,
      @PathVariable UUID project,
      @PathVariable UUID id,
      @Valid @RequestBody TaskService.Move move) {
    return service.move(a.id(), org, project, id, move);
  }
}

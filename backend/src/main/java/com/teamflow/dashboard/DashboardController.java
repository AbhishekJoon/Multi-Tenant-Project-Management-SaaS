package com.teamflow.dashboard;

import com.teamflow.common.PageResult;
import com.teamflow.security.Actor;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{org}")
public class DashboardController {
  private final DashboardService service;

  public DashboardController(DashboardService service) {
    this.service = service;
  }

  @GetMapping({"/dashboard", "/projects/{project}/dashboard"})
  DashboardService.Dashboard dashboard(
      @AuthenticationPrincipal Actor a,
      @PathVariable UUID org,
      @PathVariable(required = false) UUID project) {
    return service.dashboard(a.id(), org, project);
  }

  @GetMapping({
    "/activity",
    "/projects/{project}/activity",
    "/projects/{project}/tasks/{task}/activity"
  })
  PageResult<DashboardService.Activity> activity(
      @AuthenticationPrincipal Actor a,
      @PathVariable UUID org,
      @PathVariable(required = false) UUID project,
      @PathVariable(required = false) UUID task,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "30") int size) {
    return service.activities(a.id(), org, project, task, page, size);
  }

  @GetMapping("/notifications")
  PageResult<DashboardService.Notification> notifications(
      @AuthenticationPrincipal Actor a,
      @PathVariable UUID org,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "30") int size) {
    return service.notifications(a.id(), org, page, size);
  }

  @PatchMapping({"/notifications/read-all", "/notifications/{id}/read"})
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void read(
      @AuthenticationPrincipal Actor a,
      @PathVariable UUID org,
      @PathVariable(required = false) UUID id) {
    service.read(a.id(), org, id);
  }

  @GetMapping("/audit")
  PageResult<DashboardService.Audit> audits(
      @AuthenticationPrincipal Actor a,
      @PathVariable UUID org,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "30") int size) {
    return service.audits(a.id(), org, page, size);
  }
}

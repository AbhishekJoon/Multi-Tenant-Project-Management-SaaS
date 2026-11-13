package com.teamflow.project;

import com.teamflow.common.PageResult;
import com.teamflow.project.ProjectTypes.*;
import com.teamflow.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{org}/projects")
public class ProjectController {
  private final ProjectService service;

  record MemberInput(@NotNull UUID userId, @NotNull Role role) {}

  public ProjectController(ProjectService service) {
    this.service = service;
  }

  @GetMapping
  PageResult<ProjectService.Project> list(
      @AuthenticationPrincipal Actor a,
      @PathVariable UUID org,
      @RequestParam(defaultValue = "") @Size(max = 200) String search,
      @RequestParam(required = false) Status status,
      @RequestParam(required = false) Visibility visibility,
      @RequestParam(defaultValue = "createdAt") String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "24") int size) {
    return service.list(a.id(), org, search, status, visibility, sort, page, size);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  ProjectService.Project create(
      @AuthenticationPrincipal Actor a,
      @PathVariable UUID org,
      @Valid @RequestBody ProjectService.Input input) {
    return service.create(a.id(), org, input);
  }

  @GetMapping("/{id}")
  ProjectService.Project get(
      @AuthenticationPrincipal Actor a, @PathVariable UUID org, @PathVariable UUID id) {
    return service.get(a.id(), org, id);
  }

  @PatchMapping("/{id}")
  ProjectService.Project update(
      @AuthenticationPrincipal Actor a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @Valid @RequestBody ProjectService.Input input) {
    return service.update(a.id(), org, id, input);
  }

  @PostMapping("/{id}/archive")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void archive(@AuthenticationPrincipal Actor a, @PathVariable UUID org, @PathVariable UUID id) {
    service.archive(a.id(), org, id);
  }

  @GetMapping("/{id}/members")
  List<ProjectService.Member> members(
      @AuthenticationPrincipal Actor a, @PathVariable UUID org, @PathVariable UUID id) {
    return service.members(a.id(), org, id);
  }

  @PostMapping("/{id}/members")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void member(
      @AuthenticationPrincipal Actor a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @Valid @RequestBody MemberInput input) {
    service.member(a.id(), org, id, input.userId(), input.role());
  }

  @DeleteMapping("/{id}/members/{user}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void remove(
      @AuthenticationPrincipal Actor a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @PathVariable UUID user) {
    service.removeMember(a.id(), org, id, user);
  }
}

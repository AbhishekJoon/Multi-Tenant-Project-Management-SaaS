package com.teamflow.comment;

import com.teamflow.common.PageResult;
import com.teamflow.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{org}/projects/{project}/tasks/{task}/comments")
public class CommentController {
  private final CommentService service;

  record Input(@NotBlank @Size(max = 5000) String body) {}

  public CommentController(CommentService service) {
    this.service = service;
  }

  @GetMapping
  PageResult<CommentService.Comment> list(
      @AuthenticationPrincipal Actor a,
      @PathVariable UUID org,
      @PathVariable UUID project,
      @PathVariable UUID task,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return service.list(a.id(), org, project, task, page, size);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  CommentService.Comment create(
      @AuthenticationPrincipal Actor a,
      @PathVariable UUID org,
      @PathVariable UUID project,
      @PathVariable UUID task,
      @Valid @RequestBody Input input) {
    return service.create(a.id(), org, project, task, input.body());
  }

  @PatchMapping("/{id}")
  CommentService.Comment update(
      @AuthenticationPrincipal Actor a,
      @PathVariable UUID org,
      @PathVariable UUID project,
      @PathVariable UUID task,
      @PathVariable UUID id,
      @Valid @RequestBody Input input) {
    return service.update(a.id(), org, project, task, id, input.body());
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(
      @AuthenticationPrincipal Actor a,
      @PathVariable UUID org,
      @PathVariable UUID project,
      @PathVariable UUID task,
      @PathVariable UUID id) {
    service.delete(a.id(), org, project, task, id);
  }
}

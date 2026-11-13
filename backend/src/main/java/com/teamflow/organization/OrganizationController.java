package com.teamflow.organization;

import com.teamflow.security.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {
  private final OrganizationService service;

  record Create(
      @NotBlank @Size(max = 100) String name, @NotNull @Size(max = 2000) String description) {}

  record Update(
      @NotBlank @Size(max = 100) String name,
      @NotNull @Size(max = 2000) String description,
      @Size(max = 1000) @Pattern(regexp = "https://[^\\s]+", message = "must be an HTTPS URL")
          String logoUrl) {}

  record RoleRequest(@NotNull OrgRole role) {}

  record Transfer(@NotNull UUID userId) {}

  public OrganizationController(OrganizationService service) {
    this.service = service;
  }

  @GetMapping
  List<OrganizationService.Organization> list(@AuthenticationPrincipal Actor actor) {
    return service.list(actor.id());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  OrganizationService.Organization create(
      @AuthenticationPrincipal Actor actor, @Valid @RequestBody Create body) {
    return service.create(actor.id(), body.name(), body.description());
  }

  @GetMapping("/{org}")
  OrganizationService.Organization get(
      @AuthenticationPrincipal Actor actor, @PathVariable UUID org) {
    return service.get(actor.id(), org);
  }

  @PatchMapping("/{org}")
  OrganizationService.Organization update(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID org,
      @Valid @RequestBody Update body) {
    return service.update(actor.id(), org, body.name(), body.description(), body.logoUrl());
  }

  @GetMapping("/{org}/members")
  List<OrganizationService.Member> members(
      @AuthenticationPrincipal Actor actor, @PathVariable UUID org) {
    return service.members(actor.id(), org);
  }

  @PatchMapping("/{org}/members/{user}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void role(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID org,
      @PathVariable UUID user,
      @Valid @RequestBody RoleRequest body) {
    service.role(actor.id(), org, user, body.role());
  }

  @DeleteMapping("/{org}/members/{user}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void remove(
      @AuthenticationPrincipal Actor actor, @PathVariable UUID org, @PathVariable UUID user) {
    service.remove(actor.id(), org, user);
  }

  @PostMapping("/{org}/transfer-ownership")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void transfer(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID org,
      @Valid @RequestBody Transfer body) {
    service.transfer(actor.id(), org, body.userId());
  }

  @PostMapping("/{org}/archive")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void archive(@AuthenticationPrincipal Actor actor, @PathVariable UUID org) {
    service.archive(actor.id(), org);
  }
}

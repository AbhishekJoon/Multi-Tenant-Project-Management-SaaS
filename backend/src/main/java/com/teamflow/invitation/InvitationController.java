package com.teamflow.invitation;

import com.teamflow.security.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class InvitationController {
  private final InvitationService service;

  record Invite(@NotBlank @Email @Size(max = 254) String email, @NotNull OrgRole role) {}

  record Respond(@NotBlank @Size(max = 100) String token, boolean accept) {
    @Override
    public String toString() {
      return "Respond[REDACTED]";
    }
  }

  public InvitationController(InvitationService service) {
    this.service = service;
  }

  @GetMapping("/organizations/{org}/invitations")
  List<InvitationService.Invitation> list(
      @AuthenticationPrincipal Actor actor, @PathVariable UUID org) {
    return service.list(actor.id(), org);
  }

  @PostMapping("/organizations/{org}/invitations")
  @ResponseStatus(HttpStatus.CREATED)
  InvitationService.Invitation invite(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID org,
      @Valid @RequestBody Invite body) {
    return service.invite(actor.id(), org, body.email(), body.role());
  }

  @DeleteMapping("/organizations/{org}/invitations/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void revoke(@AuthenticationPrincipal Actor actor, @PathVariable UUID org, @PathVariable UUID id) {
    service.revoke(actor.id(), org, id);
  }

  @PostMapping("/invitations/respond")
  Map<String, UUID> respond(
      @AuthenticationPrincipal Actor actor, @Valid @RequestBody Respond body) {
    return Map.of("organizationId", service.respond(actor, body.token(), body.accept()));
  }
}

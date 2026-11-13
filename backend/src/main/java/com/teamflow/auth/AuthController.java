package com.teamflow.auth;

import com.teamflow.security.Actor;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(
    name = "teamflow.auth.provider",
    havingValue = "local",
    matchIfMissing = true)
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService auth;
  private final boolean secure;

  record Register(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(max = 100) String name,
      @NotBlank @Size(min = 12, max = 72) String password) {
    @Override
    public String toString() {
      return "Register[REDACTED]";
    }
  }

  record Login(
      @NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {
    @Override
    public String toString() {
      return "Login[REDACTED]";
    }
  }

  record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {}

  record TokenRequest(@NotBlank @Size(max = 100) String token) {
    @Override
    public String toString() {
      return "TokenRequest[REDACTED]";
    }
  }

  record Reset(
      @NotBlank @Size(max = 100) String token,
      @NotBlank @Size(min = 12, max = 72) String password) {
    @Override
    public String toString() {
      return "Reset[REDACTED]";
    }
  }

  record Name(@NotBlank @Size(max = 100) String name) {}

  record Password(
      @NotBlank @Size(max = 72) String currentPassword,
      @NotBlank @Size(min = 12, max = 72) String password) {
    @Override
    public String toString() {
      return "Password[REDACTED]";
    }
  }

  public record SessionResponse(String accessToken, AuthService.Profile user) {
    @Override
    public String toString() {
      return "SessionResponse[REDACTED]";
    }
  }

  public AuthController(AuthService auth, @Value("${teamflow.secure-cookies}") boolean secure) {
    this.auth = auth;
    this.secure = secure;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  SessionResponse register(@Valid @RequestBody Register body, HttpServletResponse response) {
    return session(auth.register(body.email(), body.name(), body.password()), response);
  }

  @PostMapping("/login")
  SessionResponse login(@Valid @RequestBody Login body, HttpServletResponse response) {
    return session(auth.login(body.email(), body.password()), response);
  }

  @PostMapping("/refresh")
  SessionResponse refresh(
      @CookieValue(name = "tf_refresh", required = false) String token,
      HttpServletResponse response) {
    return session(auth.refresh(token), response);
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void logout(
      @CookieValue(name = "tf_refresh", required = false) String token,
      HttpServletResponse response) {
    auth.logout(token);
    cookie(response, "", Duration.ZERO);
  }

  @GetMapping("/me")
  AuthService.Profile me(@AuthenticationPrincipal Actor actor) {
    return auth.profile(actor.id());
  }

  @PatchMapping("/me")
  AuthService.Profile update(@AuthenticationPrincipal Actor actor, @Valid @RequestBody Name body) {
    return auth.updateProfile(actor.id(), body.name());
  }

  @PostMapping("/change-password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void password(
      @AuthenticationPrincipal Actor actor,
      @Valid @RequestBody Password body,
      HttpServletResponse response) {
    auth.changePassword(actor.id(), body.currentPassword(), body.password());
    cookie(response, "", Duration.ZERO);
  }

  @PostMapping("/forgot-password")
  @ResponseStatus(HttpStatus.ACCEPTED)
  void forgot(@Valid @RequestBody EmailRequest body) {
    auth.forgot(body.email());
  }

  @PostMapping("/resend-confirmation")
  @ResponseStatus(HttpStatus.ACCEPTED)
  void resendByEmail(@Valid @RequestBody EmailRequest body) {
    auth.resendByEmail(body.email());
  }

  @PostMapping("/reset-password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void reset(@Valid @RequestBody Reset body) {
    auth.reset(body.token(), body.password());
  }

  @PostMapping("/verify-email")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void verify(@Valid @RequestBody TokenRequest body) {
    auth.verify(body.token());
  }

  @PostMapping("/resend-verification")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void resend(@AuthenticationPrincipal Actor actor) {
    auth.resendVerification(actor.id());
  }

  private SessionResponse session(AuthService.Session session, HttpServletResponse response) {
    cookie(response, session.refreshToken(), Duration.ofDays(7));
    return new SessionResponse(session.accessToken(), session.user());
  }

  private void cookie(HttpServletResponse response, String value, Duration age) {
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from("tf_refresh", value)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .path("/api/v1/auth")
            .maxAge(age)
            .build()
            .toString());
  }
}

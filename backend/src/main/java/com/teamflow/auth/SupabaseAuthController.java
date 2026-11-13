package com.teamflow.auth;

import com.teamflow.common.ApiException;
import com.teamflow.security.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name = "teamflow.auth.provider", havingValue = "supabase")
public class SupabaseAuthController {
  private final SupabaseAuthService auth;
  private final String origin;
  private final boolean secure;

  public SupabaseAuthController(
      SupabaseAuthService auth,
      @Value("${teamflow.origin}") String origin,
      @Value("${teamflow.secure-cookies}") boolean secure) {
    this.auth = auth;
    this.origin = origin;
    this.secure = secure;
  }

  record Callback(
      @NotBlank @Size(max = 2000) String code,
      @Pattern(regexp = "signup|recovery") @NotNull String flow) {
    @Override
    public String toString() {
      return "Callback[REDACTED]";
    }
  }

  record RecoveryPassword(@NotBlank @Size(min = 12, max = 72) String password) {
    @Override
    public String toString() {
      return "RecoveryPassword[REDACTED]";
    }
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  Object register(@Valid @RequestBody AuthController.Register body, HttpServletResponse response) {
    String verifier = begin(response, "signup");
    var result =
        auth.register(
            body.email(), body.name(), body.password(), challenge(verifier), redirect("signup"));
    if (result.hasNonNull("access_token")) return session(auth.session(result), response);
    return java.util.Map.of("confirmationRequired", true);
  }

  @PostMapping("/login")
  AuthController.SessionResponse login(
      @Valid @RequestBody AuthController.Login body, HttpServletResponse response) {
    return session(auth.login(body.email(), body.password()), response);
  }

  @PostMapping("/refresh")
  AuthController.SessionResponse refresh(
      @CookieValue(name = "tf_refresh", required = false) String refresh,
      HttpServletResponse response) {
    return session(auth.refresh(refresh), response);
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void logout(
      @CookieValue(name = "tf_refresh", required = false) String refresh,
      HttpServletResponse response) {
    try {
      auth.logout(null, refresh, false);
    } finally {
      clear(response);
    }
  }

  @GetMapping("/me")
  AuthService.Profile me(@RequestHeader("Authorization") String header) {
    return auth.profile(bearer(header));
  }

  @PatchMapping("/me")
  AuthService.Profile rename(
      @AuthenticationPrincipal Actor actor,
      @RequestHeader("Authorization") String header,
      @Valid @RequestBody AuthController.Name body) {
    return auth.rename(bearer(header), actor.id(), body.name());
  }

  @PostMapping("/forgot-password")
  @ResponseStatus(HttpStatus.ACCEPTED)
  void forgot(@Valid @RequestBody AuthController.EmailRequest body, HttpServletResponse response) {
    email(body.email(), response, "recovery");
  }

  @PostMapping("/resend-confirmation")
  @ResponseStatus(HttpStatus.ACCEPTED)
  void resend(@Valid @RequestBody AuthController.EmailRequest body, HttpServletResponse response) {
    email(body.email(), response, "signup");
  }

  @PostMapping("/resend-verification")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void resend(@AuthenticationPrincipal Actor actor, HttpServletResponse response) {
    email(actor.email(), response, "signup");
  }

  @PostMapping("/callback")
  Object callback(
      @Valid @RequestBody Callback body, HttpServletRequest request, HttpServletResponse response) {
    String verifier = null;
    if (request.getCookies() != null)
      for (Cookie cookie : request.getCookies())
        if (cookie.getName().equals("tf_" + body.flow() + "_verifier"))
          verifier = cookie.getValue();
    var result = auth.exchange(body.code(), verifier);
    cookie(response, "tf_" + body.flow() + "_verifier", "", Duration.ZERO);
    if (body.flow().equals("recovery")) {
      cookie(response, "tf_recovery", result.accessToken(), Duration.ofMinutes(10));
      // The recovery session stays server-side until a new password is chosen.
      return java.util.Map.of("recovery", true);
    }
    return session(result, response);
  }

  @PostMapping("/reset-password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void reset(
      @Valid @RequestBody RecoveryPassword body,
      @CookieValue(name = "tf_recovery", required = false) String recovery,
      HttpServletResponse response) {
    if (recovery == null)
      throw ApiException.invalid("Open a new password reset link in this browser first");
    auth.setPassword(recovery, body.password());
    clear(response);
  }

  @PostMapping("/change-password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void password(
      @AuthenticationPrincipal Actor actor,
      @Valid @RequestBody AuthController.Password body,
      HttpServletResponse response) {
    auth.changePassword(actor, body.currentPassword(), body.password());
    clear(response);
  }

  private void email(String email, HttpServletResponse response, String flow) {
    String verifier = begin(response, flow);
    auth.email(email, challenge(verifier), redirect(flow), flow.equals("recovery"));
  }

  private String redirect(String flow) {
    return origin + "/auth/callback?flow=" + flow;
  }

  private String begin(HttpServletResponse response, String flow) {
    String verifier = Tokens.generate();
    cookie(response, "tf_" + flow + "_verifier", verifier, Duration.ofHours(1));
    return verifier;
  }

  private String challenge(String verifier) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(HexFormat.of().parseHex(Tokens.hash(verifier)));
  }

  private String bearer(String header) {
    return header.startsWith("Bearer ") ? header.substring(7) : null;
  }

  private AuthController.SessionResponse session(
      AuthService.Session session, HttpServletResponse response) {
    cookie(response, "tf_refresh", session.refreshToken(), Duration.ofDays(7));
    return new AuthController.SessionResponse(session.accessToken(), session.user());
  }

  private void clear(HttpServletResponse response) {
    for (String name :
        java.util.List.of(
            "tf_refresh", "tf_recovery", "tf_signup_verifier", "tf_recovery_verifier"))
      cookie(response, name, "", Duration.ZERO);
  }

  private void cookie(HttpServletResponse response, String name, String value, Duration age) {
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .path("/api/v1/auth")
            .maxAge(age)
            .build()
            .toString());
  }
}

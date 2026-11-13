package com.teamflow.security;

import com.teamflow.auth.SupabaseAuthService;
import com.teamflow.common.ApiException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class RequestFilter extends OncePerRequestFilter {
  private final JdbcClient db;
  private final String origin;
  private final SupabaseAuthService supabase;
  private static final Set<String> PUBLIC_AUTH =
      Set.of(
          "register",
          "login",
          "refresh",
          "logout",
          "forgot-password",
          "reset-password",
          "verify-email",
          "callback",
          "resend-confirmation");
  private final ConcurrentHashMap<String, Window> attempts = new ConcurrentHashMap<>();

  private record Window(long minute, int count) {}

  public RequestFilter(JdbcClient db, String origin, SupabaseAuthService supabase) {
    this.db = db;
    this.origin = origin;
    this.supabase = supabase;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestId = UUID.randomUUID().toString();
    MDC.put("requestId", requestId);
    response.setHeader("X-Request-ID", requestId);
    response.setHeader("Cache-Control", "no-store");
    try {
      String path = request.getRequestURI();
      if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())
          && path.startsWith("/api/")) {
        if (!origin.equals(request.getHeader("Origin"))
            || !"TeamFlow".equals(request.getHeader("X-TeamFlow-Request"))) {
          problem(response, 403, "Request origin or CSRF header is invalid", requestId);
          return;
        }
      }
      if (path.startsWith("/api/v1/auth/") && "POST".equals(request.getMethod())) {
        long minute = System.currentTimeMillis() / 60000;
        if (attempts.size() > 10000)
          attempts.entrySet().removeIf(e -> e.getValue().minute() < minute);
        String key = request.getRemoteAddr();
        Window window =
            attempts.compute(
                key,
                (k, v) ->
                    v == null || v.minute() != minute
                        ? new Window(minute, 1)
                        : new Window(minute, v.count() + 1));
        if (window.count() > 40) {
          response.setHeader("Retry-After", "60");
          problem(response, 429, "Too many requests. Try again shortly", requestId);
          return;
        }
      }
      String auth = request.getHeader("Authorization");
      boolean publicAuth =
          path.startsWith("/api/v1/auth/")
              && PUBLIC_AUTH.contains(path.substring("/api/v1/auth/".length()));
      if (supabase != null && auth != null && !publicAuth) {
        try {
          if (!auth.startsWith("Bearer ")
              || auth.length() > 12007
              || auth.substring(7).split("\\.", -1).length != 3)
            throw ApiException.unauthenticated();
          Actor actor = supabase.authenticate(auth.substring(7));
          SecurityContextHolder.getContext()
              .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
        } catch (ApiException e) {
          problem(response, e.status().value(), e.getMessage(), requestId);
          return;
        }
      }
      if (supabase == null && auth != null && auth.startsWith("Bearer ") && auth.length() < 200) {
        var actor =
            db.sql(
                    "SELECT u.id,u.email,u.name FROM refresh_tokens r JOIN users u ON"
                        + " u.id=r.user_id WHERE r.access_hash=:hash AND r.access_expires_at>now()"
                        + " AND NOT r.revoked AND u.enabled")
                .param("hash", Tokens.hash(auth.substring(7)))
                .query(
                    (rs, n) ->
                        new Actor(
                            rs.getObject("id", UUID.class),
                            rs.getString("email"),
                            rs.getString("name")))
                .optional();
        actor.ifPresent(
            a ->
                SecurityContextHolder.getContext()
                    .setAuthentication(
                        new UsernamePasswordAuthenticationToken(a, null, List.of())));
      }
      chain.doFilter(request, response);
    } finally {
      MDC.remove("requestId");
    }
  }

  public static void problem(HttpServletResponse response, int status, String detail, String id)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    response
        .getWriter()
        .write(
            "{\"status\":"
                + status
                + ",\"title\":\"Request rejected\",\"detail\":\""
                + detail
                + "\",\"requestId\":\""
                + id
                + "\"}");
  }
}

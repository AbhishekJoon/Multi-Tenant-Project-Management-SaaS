package com.teamflow.config;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Reject unsafe connection overrides before the datasource or Flyway can connect. */
public class SupabaseEnvironmentValidator implements EnvironmentPostProcessor, Ordered {
  @Override
  public int getOrder() {
    return ConfigDataEnvironmentPostProcessor.ORDER + 1;
  }

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (!Arrays.asList(environment.getActiveProfiles()).contains("supabase")) return;
    if (!"supabase".equals(environment.getProperty("teamflow.auth.provider")))
      throw new IllegalStateException("The supabase profile requires Supabase Auth");
    try {
      URI authUrl = URI.create(required(environment, "SUPABASE_URL"));
      if (!"https".equals(authUrl.getScheme())
          || authUrl.getHost() == null
          || authUrl.getRawUserInfo() != null
          || authUrl.getRawQuery() != null
          || authUrl.getRawFragment() != null
          || !(authUrl.getPath().isEmpty() || authUrl.getPath().equals("/")))
        throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("SUPABASE_URL must be the HTTPS project origin");
    }
    String authKey =
        environment.getProperty(
            "SUPABASE_PUBLISHABLE_KEY", environment.getProperty("SUPABASE_ANON_KEY", ""));
    if (authKey.isBlank() || authKey.startsWith("sb_secret_") || authKey.contains("replace-with"))
      throw new IllegalStateException(
          "Set SUPABASE_PUBLISHABLE_KEY to the public publishable or anon key");
    String url = required(environment, "SUPABASE_DB_URL");
    required(environment, "SUPABASE_DB_USER");
    required(environment, "SUPABASE_DB_PASSWORD");
    String certificate = required(environment, "SUPABASE_DB_SSL_ROOT_CERT");
    try {
      if (!url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
      URI address = URI.create(url.substring("jdbc:".length()));
      if (address.getHost() == null
          || address.getRawUserInfo() != null
          || address.getRawQuery() != null
          || address.getRawFragment() != null
          || (address.getPort() != -1 && address.getPort() != 5432)
          || address.getPath() == null
          || !address.getPath().matches("/[A-Za-z][A-Za-z0-9_]*")) {
        throw new IllegalArgumentException();
      }
    } catch (IllegalArgumentException exception) {
      // Never include the supplied URL: an incorrectly pasted URL may contain a password.
      throw new IllegalStateException(
          "SUPABASE_DB_URL must be a credential-free JDBC PostgreSQL URL on port 5432, "
              + "without query parameters. Use the Session pooler or Direct connection; "
              + "TLS and the private schema are configured by the supabase profile.");
    }
    try {
      if (!Files.isRegularFile(Path.of(certificate)) || !Files.isReadable(Path.of(certificate))) {
        throw new IllegalStateException(
            "SUPABASE_DB_SSL_ROOT_CERT must point to a readable CA certificate file.");
      }
    } catch (InvalidPathException exception) {
      throw new IllegalStateException(
          "SUPABASE_DB_SSL_ROOT_CERT must point to a readable CA certificate file.");
    }
    String schema = environment.getProperty("spring.jpa.properties.hibernate.default_schema");
    String jdbcSchema =
        environment.getProperty("spring.datasource.hikari.data-source-properties.currentSchema");
    String tls = environment.getProperty("spring.datasource.hikari.data-source-properties.sslmode");
    if (!"teamflow".equals(schema)
        || !"teamflow,extensions".equals(jdbcSchema)
        || !"verify-full".equals(tls)
        || !"teamflow".equals(environment.getProperty("spring.flyway.default-schema"))
        || environment.getProperty("spring.flyway.create-schemas", Boolean.class, true)) {
      throw new IllegalStateException(
          "The supabase profile requires verified TLS and the pre-created private teamflow"
              + " schema.");
    }
    if (!url.equals(environment.getProperty("spring.datasource.url"))
        || !required(environment, "SUPABASE_DB_USER")
            .equals(environment.getProperty("spring.datasource.username"))
        || !required(environment, "SUPABASE_DB_PASSWORD")
            .equals(environment.getProperty("spring.datasource.password"))) {
      throw new IllegalStateException(
          "Configure Supabase through SUPABASE_DB_* variables, without Spring datasource"
              + " overrides.");
    }
  }

  private String required(ConfigurableEnvironment environment, String name) {
    String value = environment.getProperty(name);
    if (value == null
        || value.isBlank()
        || value.equals("replace-with-your-database-password")
        || value.contains("your-pooler-host")
        || value.contains("your-project-ref")) {
      throw new IllegalStateException(
          "Set "
              + name
              + " in your local Supabase environment file before selecting the supabase profile.");
    }
    return value;
  }
}

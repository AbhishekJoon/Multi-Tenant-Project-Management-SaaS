package com.teamflow;

import static org.assertj.core.api.Assertions.*;

import com.teamflow.config.SupabaseEnvironmentValidator;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.*;
import org.springframework.core.io.ClassPathResource;

class SupabaseConfigurationTest {
  @TempDir Path directory;

  private ConfigurableEnvironment environment(Map<String, Object> overrides) throws Exception {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    environment.setActiveProfiles("supabase");
    var values = new HashMap<String, Object>();
    values.put("SUPABASE_DB_URL", "jdbc:postgresql://aws-0-test.pooler.supabase.com:5432/postgres");
    values.put("SUPABASE_DB_USER", "postgres.testproject");
    values.put("SUPABASE_DB_PASSWORD", "test-only-not-a-real-credential");
    values.put(
        "SUPABASE_DB_SSL_ROOT_CERT",
        Files.writeString(
                directory.resolve("ca.crt"), "test certificate path, not a real certificate")
            .toString());
    values.putAll(overrides);
    environment.getPropertySources().addFirst(new MapPropertySource("test-values", values));
    new YamlPropertySourceLoader()
        .load("supabase", new ClassPathResource("application-supabase.yml"))
        .forEach(source -> environment.getPropertySources().addLast(source));
    return environment;
  }

  private void validate(ConfigurableEnvironment environment) {
    new SupabaseEnvironmentValidator()
        .postProcessEnvironment(environment, new SpringApplication(TeamFlowApplication.class));
  }

  @Test
  void sessionPoolerUsesPrivateSchemaVerifiedTlsAndSmallPool() throws Exception {
    var environment = environment(Map.of());
    validate(environment);
    assertThat(environment.getProperty("spring.datasource.hikari.maximum-pool-size"))
        .isEqualTo("5");
    assertThat(environment.getProperty("spring.datasource.hikari.minimum-idle")).isEqualTo("1");
    assertThat(environment.getProperty("spring.datasource.hikari.data-source-properties.sslmode"))
        .isEqualTo("verify-full");
    assertThat(environment.getProperty("spring.flyway.locations")).contains("db/supabase");
  }

  @Test
  void invalidOrCredentialBearingUrlsFailWithoutLeakingInput() throws Exception {
    for (String url :
        List.of(
            "jdbc:postgresql://host:6543/postgres",
            "jdbc:postgresql://user:TOP_SECRET@host:5432/postgres",
            "jdbc:postgresql://host:5432/postgres?password=TOP_SECRET",
            "jdbc:postgresql://host:5432/postgres?sslmode=disable",
            "postgresql://host/postgres")) {
      var environment = environment(Map.of("SUPABASE_DB_URL", url));
      assertThatThrownBy(() -> validate(environment))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining("TOP_SECRET")
          .hasMessageNotContaining(url);
    }
  }

  @Test
  void unsafeSchemaAndTlsOverridesAreRejected() throws Exception {
    for (var override :
        List.of(
            Map.<String, Object>of("spring.jpa.properties.hibernate.default_schema", "public"),
            Map.<String, Object>of(
                "spring.datasource.hikari.data-source-properties.sslmode", "disable"),
            Map.<String, Object>of("spring.flyway.create-schemas", true))) {
      var environment = environment(override);
      assertThatThrownBy(() -> validate(environment)).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void missingPasswordAndCertificateFailBeforeConnecting() throws Exception {
    var missingPassword = environment(Map.of("SUPABASE_DB_PASSWORD", ""));
    assertThatThrownBy(() -> validate(missingPassword))
        .hasMessageContaining("SUPABASE_DB_PASSWORD");
    var missingCertificate =
        environment(
            Map.of("SUPABASE_DB_SSL_ROOT_CERT", directory.resolve("missing.crt").toString()));
    assertThatThrownBy(() -> validate(missingCertificate))
        .hasMessageContaining("readable CA certificate");
  }

  @Test
  void localDevelopmentDoesNotRequireSupabaseCredentials() {
    var environment = new StandardEnvironment();
    environment.setActiveProfiles("dev");
    assertThatCode(() -> validate(environment)).doesNotThrowAnyException();
  }
}

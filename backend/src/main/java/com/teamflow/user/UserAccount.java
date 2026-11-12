package com.teamflow.user;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserAccount {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, unique = true, length = 254)
  private String email;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(nullable = false)
  private boolean enabled = true;

  @Column(name = "email_verified", nullable = false)
  private boolean emailVerified;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  protected UserAccount() {}

  public UserAccount(String email, String name, String passwordHash) {
    this.email = email;
    this.name = name;
    this.passwordHash = passwordHash;
  }

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getName() {
    return name;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public boolean isEmailVerified() {
    return emailVerified;
  }

  public void setName(String name) {
    this.name = name;
  }

  public void setPasswordHash(String hash) {
    this.passwordHash = hash;
  }

  public void verifyEmail() {
    emailVerified = true;
  }
}

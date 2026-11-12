package com.teamflow.project;

public final class ProjectTypes {
  private ProjectTypes() {}

  public enum Status {
    PLANNED,
    ACTIVE,
    ON_HOLD,
    COMPLETED,
    ARCHIVED
  }

  public enum Visibility {
    ORGANIZATION,
    PRIVATE
  }

  public enum Role {
    PROJECT_MANAGER,
    CONTRIBUTOR,
    VIEWER
  }

  public enum Priority {
    LOW,
    MEDIUM,
    HIGH,
    URGENT
  }
}

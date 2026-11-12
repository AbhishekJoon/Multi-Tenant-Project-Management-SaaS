package com.teamflow.security;

public enum OrgRole {
  OWNER,
  ADMIN,
  MEMBER,
  VIEWER;

  public boolean managesOrganization() {
    return this == OWNER || this == ADMIN;
  }

  public boolean createsProjects() {
    return this != VIEWER;
  }
}

package com.teamflow;

import static org.assertj.core.api.Assertions.*;

import com.teamflow.security.*;
import org.junit.jupiter.api.Test;

class PolicyTest {
  @Test
  void projectPermissionsRespectOrganizationCapAndProjectRole() {
    assertThat(
            ProjectAccess.allows(
                OrgRole.VIEWER,
                com.teamflow.project.ProjectTypes.Role.PROJECT_MANAGER,
                Permission.MANAGE_TASK))
        .isFalse();
    assertThat(
            ProjectAccess.allows(
                OrgRole.MEMBER,
                com.teamflow.project.ProjectTypes.Role.CONTRIBUTOR,
                Permission.MANAGE_TASK))
        .isTrue();
    assertThat(
            ProjectAccess.allows(
                OrgRole.MEMBER,
                com.teamflow.project.ProjectTypes.Role.CONTRIBUTOR,
                Permission.MANAGE_PROJECT))
        .isFalse();
    assertThat(ProjectAccess.allows(OrgRole.MEMBER, null, Permission.MANAGE_TASK)).isFalse();
    assertThat(ProjectAccess.allows(OrgRole.OWNER, null, Permission.MANAGE_PROJECT)).isTrue();
  }

  @Test
  void organizationCapabilitiesHaveAReadOnlyFloor() {
    assertThat(OrgRole.VIEWER.createsProjects()).isFalse();
    assertThat(OrgRole.MEMBER.createsProjects()).isTrue();
    assertThat(OrgRole.MEMBER.managesOrganization()).isFalse();
    assertThat(OrgRole.ADMIN.managesOrganization()).isTrue();
  }

  @Test
  void opaqueTokensHaveEntropyAndOnlyDeterministicDigestsAreStored() {
    var first = Tokens.generate();
    var second = Tokens.generate();
    assertThat(first).hasSize(43).isNotEqualTo(second);
    assertThat(Tokens.hash(first)).hasSize(64).isEqualTo(Tokens.hash(first)).isNotEqualTo(first);
  }
}

import { describe, expect, it } from "vitest";
import { createsProjects, managesOrg, managesTasks } from "./permissions";
describe("permission display rules", () => {
  it("caps viewer permissions even with project membership", () => {
    expect(createsProjects("VIEWER")).toBe(false);
    expect(managesTasks("VIEWER", "PROJECT_MANAGER")).toBe(false);
  });
  it("requires a project role for ordinary members to edit tasks", () => {
    expect(managesTasks("MEMBER", null)).toBe(false);
    expect(managesTasks("MEMBER", "CONTRIBUTOR")).toBe(true);
    expect(managesTasks("MEMBER", "PROJECT_MANAGER")).toBe(true);
  });
  it("permits owner/admin management and member creation", () => {
    expect(managesOrg("OWNER")).toBe(true);
    expect(managesOrg("ADMIN")).toBe(true);
    expect(managesOrg("MEMBER")).toBe(false);
    expect(createsProjects("MEMBER")).toBe(true);
    expect(managesTasks("ADMIN", null)).toBe(true);
  });
});

import { test, expect, type Page } from "@playwright/test";
const password = "e2e-password-12345";
async function register(page: Page, name: string, email: string) {
  await page.goto("/register");
  await page.getByLabel("Full name").fill(name);
  await page.getByLabel("Email address").fill(email);
  await page.getByLabel("Password", { exact: false }).fill(password);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page).toHaveURL(/workspaces/);
}
async function createOrg(page: Page, name: string) {
  await page.getByRole("button", { name: "New workspace" }).click();
  await page.getByLabel("Organization name").fill(name);
  await page.getByRole("button", { name: "Create workspace" }).click();
  await expect(page).toHaveURL(/dashboard/);
  return new URL(page.url()).pathname.split("/")[2];
}
test("real multi-account workflow: invitation, private project, assignment, board, comments, notifications and isolation", async ({
  page,
  browser,
  request,
}) => {
  const suffix = Date.now();
  const ownerEmail = `owner-${suffix}@example.test`;
  const memberEmail = `member-${suffix}@example.test`;
  await register(page, "Alex Morgan", ownerEmail);
  const org = await createOrg(page, "Product Studio " + suffix);
  await page.getByRole("link", { name: "Team members" }).click();
  await page.getByRole("button", { name: "Invite member" }).click();
  await page.getByLabel("Email address").fill(memberEmail);
  await page.getByRole("button", { name: "Send invitation" }).click();
  await expect(
    page.getByText("Invitation sent", { exact: true }),
  ).toBeVisible();
  const mailUrl = process.env.E2E_MAIL_URL ?? "http://localhost:8026";
  let invitationToken = "";
  await expect
    .poll(async () => {
      const inbox = await (
        await request.get(`${mailUrl}/api/v1/messages`)
      ).json();
      const message = inbox.messages?.find(
        (m: { To: { Address: string }[]; Subject: string }) =>
          m.To.some((to) => to.Address === memberEmail) &&
          m.Subject.includes("Join"),
      );
      if (!message) return false;
      const content = await (
        await request.get(`${mailUrl}/api/v1/message/${message.ID}`)
      ).json();
      invitationToken = content.Text.match(/token=([A-Za-z0-9_-]+)/)?.[1] ?? "";
      return !!invitationToken;
    })
    .toBe(true);
  const memberContext = await browser.newContext();
  const member = await memberContext.newPage();
  await register(member, "Jamie Chen", memberEmail);
  await member.goto(`/accept-invitation?token=${invitationToken}`);
  await member.getByRole("button", { name: "Accept invitation" }).click();
  await expect(member).toHaveURL(new RegExp(org + "/dashboard"));
  await page.getByRole("link", { name: "Projects", exact: true }).click();
  await page.getByRole("button", { name: "New project" }).click();
  await page
    .getByRole("textbox", { name: "Project name", exact: true })
    .fill("Platform launch");
  await page.getByLabel("Project key").fill("SHIP");
  await page.getByLabel("Visibility", { exact: true }).selectOption("PRIVATE");
  await page
    .getByRole("button", { name: "Create project", exact: true })
    .click();
  await page.getByRole("link", { name: /Platform launch/ }).click();
  const projectUrl = page.url();
  await member.goto(projectUrl);
  await expect(member.getByRole("alert")).toContainText("Resource not found");
  await page.getByRole("tab", { name: "Members", exact: true }).click();
  await page
    .getByLabel("Workspace member")
    .selectOption({ label: `Jamie Chen (${memberEmail})` });
  await page.getByRole("button", { name: "Add or update member" }).click();
  await expect(page.getByText("Project access updated")).toBeVisible();
  await page.getByRole("button", { name: "New task" }).click();
  await page.getByLabel("Task title").fill("Deliver the launch checklist");
  await page
    .getByLabel("Assignee", { exact: true })
    .selectOption({ label: "Jamie Chen" });
  await page.getByRole("button", { name: "Create task", exact: true }).click();
  await page.getByRole("tab", { name: "Kanban board" }).click();
  await expect(
    page.getByRole("button", {
      name: "Deliver the launch checklist",
      exact: true,
    }),
  ).toBeVisible();
  const handle = page.getByRole("button", { name: "Move SHIP-1", exact: true });
  const target = page.getByRole("region", { name: "DONE", exact: true });
  // Pointer interaction drives dnd-kit; the subsequent reload proves backend persistence.
  const from = await handle.boundingBox();
  const to = await target.boundingBox();
  expect(from).toBeTruthy();
  expect(to).toBeTruthy();
  await handle.scrollIntoViewIfNeeded();
  const start = await handle.boundingBox();
  const end = await target.boundingBox();
  await page.mouse.move(
    start!.x + start!.width / 2,
    start!.y + start!.height / 2,
  );
  await page.mouse.down();
  await page.mouse.move(end!.x + end!.width / 2, end!.y + 110, { steps: 24 });
  await page.mouse.up();
  await expect(
    target.getByRole("button", {
      name: "Deliver the launch checklist",
      exact: true,
    }),
  ).toBeVisible();
  await page.reload();
  await expect(
    page
      .getByRole("region", { name: "DONE", exact: true })
      .getByRole("button", {
        name: "Deliver the launch checklist",
        exact: true,
      }),
  ).toBeVisible();
  await member.goto(projectUrl);
  await member
    .getByRole("button", { name: "Deliver the launch checklist", exact: true })
    .click();
  await member
    .getByLabel("Your comment")
    .fill("Launch checklist reviewed and ready.");
  await member.getByRole("button", { name: "Post comment" }).click();
  await expect(
    member.getByText("Launch checklist reviewed and ready.", { exact: true }),
  ).toBeVisible();
  await member.getByRole("button", { name: "Close dialog" }).click();
  await member.getByRole("link", { name: /Notifications/ }).click();
  await expect(
    member.getByText("A task has been assigned to you", { exact: true }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Activity", exact: true }).click();
  await expect(
    page.getByText("comment added", { exact: false }).first(),
  ).toBeVisible();
  await page.getByRole("link", { name: "Overview", exact: true }).click();
  await expect(
    page.getByText("Tasks completed", { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: "../docs/screenshots/workspace-desktop.png",
    animations: "disabled",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: "../docs/screenshots/workspace-mobile.png",
    animations: "disabled",
    fullPage: true,
  });
  await page.getByRole("button", { name: "Open navigation" }).click();
  await expect(page.getByRole("link", { name: "Team members" })).toBeVisible();
  await page
    .getByRole("button", { name: "Close navigation", exact: true })
    .click();
  await member.goto("/workspaces");
  const otherOrg = await createOrg(member, "Independent workspace " + suffix);
  await member.getByLabel("Active organization").selectOption(org);
  await expect(member).toHaveURL(new RegExp(org + "/dashboard"));
  await expect(
    member.getByRole("link", { name: /Platform launch/ }),
  ).toBeVisible();
  await page.goto(`/org/${otherOrg}/dashboard`);
  await expect(page.getByRole("alert")).toContainText("permission");
  await memberContext.close();
});
test("password reset and email verification use expiring single-use email links", async ({
  page,
  request,
}) => {
  const email = `reset-${Date.now()}@example.test`;
  await register(page, "Taylor Reed", email);
  const mailUrl = process.env.E2E_MAIL_URL ?? "http://localhost:8026";
  let verificationToken = "";
  await expect
    .poll(async () => {
      const inbox = await (
        await request.get(`${mailUrl}/api/v1/messages`)
      ).json();
      const message = inbox.messages?.find(
        (m: { To: { Address: string }[]; Subject: string }) =>
          m.To.some((to) => to.Address === email) &&
          m.Subject.includes("Verify"),
      );
      if (!message) return false;
      const content = await (
        await request.get(`${mailUrl}/api/v1/message/${message.ID}`)
      ).json();
      verificationToken =
        content.Text.match(/token=([A-Za-z0-9_-]+)/)?.[1] ?? "";
      return !!verificationToken;
    })
    .toBe(true);
  await page.goto(`/verify-email?token=${verificationToken}`);
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(
    page.getByText("Your email is verified.", { exact: false }),
  ).toBeVisible();
  await page.goto("/workspaces");
  await page.getByRole("button", { name: "Sign out" }).click();
  await page.goto("/forgot-password");
  await page.getByLabel("Email address").fill(email);
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "reset link" }),
  ).toBeVisible();
  let token = "";
  await expect
    .poll(async () => {
      const inbox = await (
        await request.get(`${mailUrl}/api/v1/messages`)
      ).json();
      const message = inbox.messages?.find(
        (m: { To: { Address: string }[]; Subject: string }) =>
          m.To.some((to) => to.Address === email) &&
          m.Subject.includes("Reset"),
      );
      if (!message) return false;
      const content = await (
        await request.get(`${mailUrl}/api/v1/message/${message.ID}`)
      ).json();
      token = content.Text.match(/token=([A-Za-z0-9_-]+)/)?.[1] ?? "";
      return !!token;
    })
    .toBe(true);
  await page.goto(`/reset-password?token=${token}`);
  await page.getByLabel("Password").fill("replacement-password-456");
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByText(/Your password has been changed/)).toBeVisible();
  await page.goto(`/reset-password?token=${token}`);
  await page.getByLabel("Password").fill("another-password-789");
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByRole("alert")).toContainText("invalid or expired");
  await page.goto("/login");
  await page.getByLabel("Email address").fill(email);
  await page.getByLabel("Password").fill("replacement-password-456");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page).toHaveURL(/workspaces/);
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Welcome, Taylor." }),
  ).toBeVisible();
});

import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Routes, Route, Outlet } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import Board from "./Board";
import { api } from "../../services/api";
import type { Organization, Project, Task } from "../../types";
vi.mock("../../services/api", async (original) => ({
  ...(await original<typeof import("../../services/api")>()),
  api: vi.fn(),
}));
const workspace: Organization = {
  id: "org",
  name: "Workspace",
  description: "",
  role: "MEMBER",
  logoUrl: null,
  archivedAt: null,
};
const project: Project = {
  id: "project",
  organizationId: "org",
  name: "Project",
  projectKey: "TF",
  description: "",
  status: "ACTIVE",
  visibility: "PRIVATE",
  priority: "MEDIUM",
  startDate: null,
  dueDate: null,
  createdAt: "",
  projectRole: "CONTRIBUTOR",
};
const task: Task = {
  id: "task",
  organizationId: "org",
  projectId: "project",
  identifier: "TF-1",
  taskNumber: 1,
  title: "Review launch",
  description: "",
  status: "TODO",
  priority: "HIGH",
  reporterId: "owner",
  assigneeId: null,
  assigneeName: null,
  dueDate: null,
  estimatedEffort: null,
  position: 0,
  version: 0,
  createdAt: "",
  updatedAt: "",
  completedAt: null,
};
function show(org = workspace) {
  const cache = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={cache}>
      <MemoryRouter>
        <Routes>
          <Route element={<Outlet context={org} />}>
            <Route path="/" element={<Board project={project} />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
afterEach(() => vi.resetAllMocks());
describe("board states and permission controls", () => {
  it("shows loading while real task fetching is pending", () => {
    vi.mocked(api).mockReturnValue(new Promise(() => {}));
    show();
    expect(screen.getByRole("status")).toHaveTextContent("Loading");
  });
  it("surfaces fetch failures instead of presenting fake tasks", async () => {
    vi.mocked(api).mockRejectedValue(new Error("Workspace unavailable"));
    show();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Workspace unavailable",
    );
  });
  it("shows cards by status and removes write controls for organization viewers", async () => {
    vi.mocked(api).mockResolvedValue({
      items: [task],
      total: 1,
      page: 0,
      size: 100,
    });
    show({ ...workspace, role: "VIEWER" });
    expect(
      await screen.findByRole("button", { name: "Review launch" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Move TF-1" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /Add task/ }),
    ).not.toBeInTheDocument();
  });
  it("offers keyboard drag handles to authorized project contributors", async () => {
    vi.mocked(api).mockResolvedValue({
      items: [task],
      total: 1,
      page: 0,
      size: 100,
    });
    show();
    expect(
      await screen.findByRole("button", { name: "Move TF-1" }),
    ).toHaveAttribute("tabindex", "0");
    expect(screen.getAllByRole("button", { name: /Add task/ })).toHaveLength(5);
  });
});

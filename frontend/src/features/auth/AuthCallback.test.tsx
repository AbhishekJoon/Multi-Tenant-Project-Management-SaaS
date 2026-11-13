import { StrictMode } from "react";
import { render, screen, waitFor } from "@testing-library/react";
import { BrowserRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import AuthCallback from "./AuthCallback";
import { api, setSession } from "../../services/api";

vi.mock("../../services/api", () => ({ api: vi.fn(), setSession: vi.fn() }));
beforeEach(() => vi.clearAllMocks());
afterEach(() => window.history.replaceState(null, "", "/"));
function mount(query: string) {
  window.history.replaceState(null, "", "/auth/callback" + query);
  return render(
    <StrictMode>
      <BrowserRouter>
        <Routes>
          <Route path="/auth/callback" element={<AuthCallback />} />
          <Route path="/workspaces" element={<p>Workspace loaded</p>} />
          <Route path="/reset-password" element={<p>Choose a password</p>} />
        </Routes>
      </BrowserRouter>
    </StrictMode>,
  );
}
it("exchanges a confirmation code once under StrictMode and removes it from the URL", async () => {
  const session = {
    accessToken: "test-access",
    user: {
      id: "user",
      email: "person@example.test",
      name: "Person",
      emailVerified: true,
    },
  };
  vi.mocked(api).mockResolvedValue(session);
  mount("?code=single-use-secret&flow=signup");
  expect(await screen.findByText("Workspace loaded")).toBeInTheDocument();
  expect(api).toHaveBeenCalledExactlyOnceWith(
    "/auth/callback",
    "POST",
    { code: "single-use-secret", flow: "signup" },
    false,
  );
  expect(setSession).toHaveBeenCalledExactlyOnceWith(session);
  expect(window.location.href).not.toContain("single-use-secret");
});
it("takes recovery links to password reset without establishing a normal session", async () => {
  vi.mocked(api).mockResolvedValue({ recovery: true });
  mount("?code=recovery-code&flow=recovery");
  expect(await screen.findByText("Choose a password")).toBeInTheDocument();
  expect(setSession).not.toHaveBeenCalled();
  expect(window.location.search).toBe("?supabase=1");
});
it("rejects malformed and failed links without displaying provider error details", async () => {
  mount("?error=denied&error_description=private-provider-detail");
  expect(screen.getByRole("alert")).toHaveTextContent("invalid or expired");
  expect(api).not.toHaveBeenCalled();
  await waitFor(() => expect(window.location.search).toBe(""));
  expect(screen.queryByText("private-provider-detail")).not.toBeInTheDocument();
});

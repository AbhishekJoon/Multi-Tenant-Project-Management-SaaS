import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { useState } from "react";
import { Layers3, ArrowRight } from "lucide-react";
import { Form, type Field } from "../../components/Form";
import { api, setSession, useSession } from "../../services/api";
import type { User } from "../../types";
export default function AuthPage({ mode }: { mode: string }) {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const { user } = useSession();
  const [message, setMessage] = useState("");
  const titles: Record<string, string> = {
    login: "Welcome back",
    register: "Make room for great work",
    "forgot-password": "Reset your password",
    "reset-password": "Choose a new password",
    "verify-email": "Verify your email",
    "accept-invitation": "Your team is waiting",
  };
  const fields: Field[] = [];
  if (mode === "register")
    fields.push({ name: "name", label: "Full name", required: true, max: 100 });
  if (["login", "register", "forgot-password"].includes(mode))
    fields.push({
      name: "email",
      label: "Email address",
      type: "email",
      required: true,
      max: 254,
    });
  if (["login", "register", "reset-password"].includes(mode))
    fields.push({
      name: "password",
      label: "Password",
      type: "password",
      required: true,
      min: mode === "login" ? 1 : 12,
      max: 72,
    });
  const returnTo =
    params.get("returnTo") ??
    (mode === "accept-invitation"
      ? "/accept-invitation?token=" + params.get("token")
      : "");
  const returnQuery = returnTo
    ? `?returnTo=${encodeURIComponent(returnTo)}`
    : "";
  return (
    <div className="auth-layout">
      <aside className="auth-story">
        <Link className="brand" to="/">
          <Layers3 /> teamflow<span className="brand-dot">.</span>
        </Link>
        <div>
          <span className="eyebrow">A LITTLE CLARITY. A LOT OF MOMENTUM.</span>
          <h1>
            Great teams.
            <br />
            Thoughtful work.
            <br />
            <span>One shared space.</span>
          </h1>
          <p>
            Bring your people, projects, and next big ideas together. Keep every
            workspace securely its own.
          </p>
        </div>
        <small>Built for the way your team moves.</small>
      </aside>
      <main className="auth-panel">
        <div className="auth-card">
          <span className="eyebrow">YOUR NEXT CHAPTER</span>
          <h2>{titles[mode]}</h2>
          <p className="muted">
            {mode === "login"
              ? "Sign in and pick up where you left off."
              : "A calmer, clearer way to move work forward."}
          </p>
          {message ? (
            <div role="status" className="success-banner">
              {message}
              <Link to="/login">
                Back to sign in <ArrowRight size={16} />
              </Link>
            </div>
          ) : mode === "accept-invitation" && !user ? (
            <>
              <p>
                Sign in with the email address that received this invitation.
              </p>
              <Link className="button primary" to={"/login" + returnQuery}>
                Sign in to accept
              </Link>
              <Link className="text-link" to={"/register" + returnQuery}>
                Create an account
              </Link>
            </>
          ) : (
            <Form
              fields={fields}
              submit={
                mode === "login"
                  ? "Sign in"
                  : mode === "register"
                    ? "Create account"
                    : mode === "accept-invitation"
                      ? "Accept invitation"
                      : "Continue"
              }
              onSubmit={async (values) => {
                if (mode === "login" || mode === "register") {
                  setSession(
                    await api<{ accessToken: string; user: User }>(
                      `/auth/${mode}`,
                      "POST",
                      values,
                      false,
                    ),
                  );
                  navigate(
                    returnTo.startsWith("/accept-invitation?")
                      ? returnTo
                      : "/workspaces",
                    { replace: true },
                  );
                } else if (mode === "forgot-password") {
                  await api("/auth/forgot-password", "POST", values, false);
                  setMessage(
                    "If an eligible account exists, a reset link has been sent. Please check your inbox.",
                  );
                } else if (
                  mode === "reset-password" ||
                  mode === "verify-email"
                ) {
                  await api(
                    `/auth/${mode}`,
                    "POST",
                    { ...values, token: params.get("token") ?? "" },
                    false,
                  );
                  setMessage(
                    mode === "verify-email"
                      ? "Your email is verified."
                      : "Your password has been changed. Please sign in again.",
                  );
                } else {
                  const result = await api<{ organizationId: string }>(
                    "/invitations/respond",
                    "POST",
                    { token: params.get("token") ?? "", accept: true },
                  );
                  navigate(`/org/${result.organizationId}/dashboard`);
                }
              }}
            />
          )}
          {mode === "accept-invitation" && user && !message && (
            <button
              className="button quiet mt"
              onClick={async () => {
                try {
                  await api("/invitations/respond", "POST", {
                    token: params.get("token") ?? "",
                    accept: false,
                  });
                  setMessage("Invitation declined.");
                } catch (error) {
                  setMessage(
                    error instanceof Error
                      ? error.message
                      : "Unable to decline invitation",
                  );
                }
              }}
            >
              Decline invitation
            </button>
          )}
          {mode === "login" && (
            <>
              <Link className="text-link" to="/forgot-password">
                Forgot password?
              </Link>
              <p className="auth-footer">
                New here?{" "}
                <Link to={"/register" + returnQuery}>Create an account</Link>
              </p>
            </>
          )}
          {mode === "register" && (
            <p className="auth-footer">
              Already have an account?{" "}
              <Link to={"/login" + returnQuery}>Sign in</Link>
            </p>
          )}
        </div>
      </main>
    </div>
  );
}

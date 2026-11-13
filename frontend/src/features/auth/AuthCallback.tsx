import { useEffect, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { api, setSession } from "../../services/api";
import type { User } from "../../types";

type CallbackResult = { recovery?: boolean; accessToken: string; user: User };

export default function AuthCallback() {
  const navigate = useNavigate();
  const [error, setError] = useState("");
  const [params] = useState(() => new URLSearchParams(window.location.search));
  const exchange = useRef<Promise<CallbackResult> | null>(null);
  const invalid =
    params.has("error") ||
    !params.get("code") ||
    !["signup", "recovery"].includes(params.get("flow") ?? "");
  const message =
    error ||
    (invalid
      ? "This email link is invalid or expired. Request a new link and open it in the same browser."
      : "");
  useEffect(() => {
    const code = params.get("code");
    const flow = params.get("flow");
    // Remove the single-use code before navigating or loading any other content.
    window.history.replaceState(null, "", "/auth/callback");
    if (invalid) return;
    // Reuse the exchange during StrictMode replay: the provider code is single-use.
    exchange.current ??= api("/auth/callback", "POST", { code, flow }, false);
    let active = true;
    void exchange.current
      .then((result) => {
        if (!active) return;
        if (result.recovery)
          navigate("/reset-password?supabase=1", { replace: true });
        else {
          setSession(result);
          navigate("/workspaces", { replace: true });
        }
      })
      .catch((e: unknown) => {
        if (active)
          setError(
            e instanceof Error ? e.message : "Unable to confirm this link",
          );
      });
    return () => {
      active = false;
    };
  }, [navigate, params, invalid]);
  return (
    <main className="workspace-page">
      <h1>
        {message
          ? "Email link could not be confirmed"
          : "Confirming your email link…"}
      </h1>
      {message && (
        <>
          <p role="alert">{message}</p>
          <Link to="/forgot-password">Request a password reset</Link>
          <p>
            <Link to="/resend-confirmation">Request a confirmation email</Link>
          </p>
          <Link to="/login">Back to sign in</Link>
        </>
      )}
    </main>
  );
}

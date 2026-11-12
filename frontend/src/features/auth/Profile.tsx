import { useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { Form } from "../../components/Form";
import {
  api,
  setSession,
  updateSessionUser,
  useSession,
} from "../../services/api";
import type { User } from "../../types";
export default function Profile() {
  const { user } = useSession();
  const navigate = useNavigate();
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">YOUR ACCOUNT</span>
          <h1>A little about you.</h1>
          <p className="muted">{user?.email}</p>
        </div>
        <span className="badge">
          {user?.emailVerified ? "EMAIL VERIFIED" : "VERIFICATION PENDING"}
        </span>
      </div>
      <div className="settings-grid">
        <section className="panel">
          <h3>Personal details</h3>
          <Form
            fields={[
              { name: "name", label: "Full name", required: true, max: 100 },
            ]}
            initial={{ name: user?.name ?? "" }}
            onSubmit={async (values) => {
              updateSessionUser(await api<User>("/auth/me", "PATCH", values));
              toast.success("Profile saved");
            }}
          />
          {!user?.emailVerified && (
            <button
              className="button quiet mt"
              onClick={async () => {
                try {
                  await api("/auth/resend-verification", "POST");
                  toast.success("Verification email sent");
                } catch (e) {
                  toast.error((e as Error).message);
                }
              }}
            >
              Send verification email
            </button>
          )}
        </section>
        <section className="panel">
          <h3>Change password</h3>
          <p className="muted">
            Changing your password signs out all sessions.
          </p>
          <Form
            fields={[
              {
                name: "currentPassword",
                label: "Current password",
                type: "password",
                required: true,
                max: 72,
              },
              {
                name: "password",
                label: "New password",
                type: "password",
                required: true,
                min: 12,
                max: 72,
              },
            ]}
            submit="Change password"
            onSubmit={async (values) => {
              await api("/auth/change-password", "POST", values);
              setSession(null);
              navigate("/login");
              toast.success(
                "Password changed. Sign in with your new password.",
              );
            }}
          />
        </section>
      </div>
    </>
  );
}

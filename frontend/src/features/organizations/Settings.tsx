import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { useOrg, useRefreshOrg } from "../../hooks/useOrg";
import { api, useSession } from "../../services/api";
import { managesOrg } from "../../lib/permissions";
import type { Member } from "../../types";
import { Form } from "../../components/Form";
export default function Settings() {
  const org = useOrg();
  const refresh = useRefreshOrg();
  const navigate = useNavigate();
  const { user } = useSession();
  const members = useQuery({
    queryKey: ["org", org.id, "members"],
    queryFn: () => api<Member[]>(`/organizations/${org.id}/members`),
  });
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">MAKE IT YOURS</span>
          <h1>Workspace settings.</h1>
          <p className="muted">A few details that make this space your own.</p>
        </div>
      </div>
      <div className="settings-grid">
        <section className="panel">
          <h3>Organization details</h3>
          {managesOrg(org.role) && !org.archivedAt ? (
            <Form
              key={org.name + org.description}
              initial={{
                name: org.name,
                description: org.description,
                logoUrl: org.logoUrl ?? "",
              }}
              fields={[
                { name: "name", label: "Name", required: true, max: 100 },
                { name: "description", label: "Description", type: "textarea" },
                { name: "logoUrl", label: "Logo URL (HTTPS)", max: 1000 },
              ]}
              onSubmit={async (values) => {
                await api(`/organizations/${org.id}`, "PATCH", {
                  ...values,
                  logoUrl: values.logoUrl || null,
                });
                await refresh();
                toast.success("Workspace settings saved");
              }}
            />
          ) : (
            <p className="muted">
              {org.name}
              <br />
              {org.description}
              <br />
              Your role: {org.role}
            </p>
          )}
        </section>
        <div>
          {org.role === "OWNER" && !org.archivedAt && (
            <section className="panel">
              <h3>Transfer ownership</h3>
              <p className="muted">
                The selected member becomes owner. Your role changes to Admin.
              </p>
              {members.data && members.data.length > 1 && (
                <Form
                  fields={[
                    {
                      name: "userId",
                      label: "New owner",
                      required: true,
                      options: members.data
                        .filter((m) => m.userId !== user?.id)
                        .map((m) => ({
                          value: m.userId,
                          label: `${m.name} (${m.email})`,
                        })),
                    },
                  ]}
                  submit="Transfer ownership"
                  onSubmit={async (values) => {
                    if (!window.confirm("Transfer ownership to this member?"))
                      return;
                    await api(
                      `/organizations/${org.id}/transfer-ownership`,
                      "POST",
                      values,
                    );
                    await refresh();
                    toast.success("Ownership transferred");
                  }}
                />
              )}
            </section>
          )}
          <section className="panel">
            <h3>Workspace lifecycle</h3>
            <p className="muted">
              Archiving retains history and blocks changes. The final owner must
              transfer ownership before leaving.
            </p>
            {org.role === "OWNER" && !org.archivedAt && (
              <button
                className="button danger"
                onClick={async () => {
                  if (
                    !window.confirm(
                      "Archive this workspace? History will remain available, and changes will be blocked.",
                    )
                  )
                    return;
                  try {
                    await api(`/organizations/${org.id}/archive`, "POST");
                    await refresh();
                  } catch (e) {
                    toast.error((e as Error).message);
                  }
                }}
              >
                Archive workspace
              </button>
            )}
            <button
              className="button quiet danger"
              onClick={async () => {
                if (!window.confirm("Leave this workspace?")) return;
                try {
                  await api(
                    `/organizations/${org.id}/members/${user?.id}`,
                    "DELETE",
                  );
                  navigate("/workspaces");
                } catch (e) {
                  toast.error((e as Error).message);
                }
              }}
            >
              Leave workspace
            </button>
          </section>
        </div>
      </div>
    </>
  );
}

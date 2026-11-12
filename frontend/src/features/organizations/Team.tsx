import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { UserPlus, Mail, ShieldCheck } from "lucide-react";
import { toast } from "sonner";
import { useOrg, useRefreshOrg } from "../../hooks/useOrg";
import { api, useSession } from "../../services/api";
import { managesOrg } from "../../lib/permissions";
import type { Member, Invitation } from "../../types";
import { Form } from "../../components/Form";
import { Empty, ErrorState, Loading, Modal } from "../../components/UI";
export default function Team() {
  const org = useOrg();
  const refresh = useRefreshOrg();
  const { user } = useSession();
  const [invite, setInvite] = useState(false);
  const manage = managesOrg(org.role) && !org.archivedAt;
  const members = useQuery({
    queryKey: ["org", org.id, "members"],
    queryFn: () => api<Member[]>(`/organizations/${org.id}/members`),
  });
  const invitations = useQuery({
    queryKey: ["org", org.id, "invitations"],
    queryFn: () => api<Invitation[]>(`/organizations/${org.id}/invitations`),
    enabled: manage,
  });
  async function change(path: string, method: string, body?: unknown) {
    try {
      await api(`/organizations/${org.id}${path}`, method, body);
      await refresh();
      toast.success("Team updated");
    } catch (e) {
      toast.error((e as Error).message);
    }
  }
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">BETTER, TOGETHER</span>
          <h1>Your people.</h1>
          <p className="muted">
            The team behind the progress. Manage access to this workspace.
          </p>
        </div>
        {manage && (
          <button className="button primary" onClick={() => setInvite(true)}>
            <UserPlus size={16} />
            Invite member
          </button>
        )}
      </div>
      {members.isPending ? (
        <Loading />
      ) : members.error ? (
        <ErrorState error={members.error} />
      ) : (
        <section className="panel">
          <div className="panel-title">
            <h3>Workspace members</h3>
            <span className="badge">{members.data?.length} people</span>
          </div>
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>MEMBER</th>
                  <th>ROLE</th>
                  <th>JOINED</th>
                  <th>
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {members.data?.map((member) => (
                  <tr key={member.userId}>
                    <td>
                      <div className="person">
                        <span className="avatar">
                          {member.name.slice(0, 2).toUpperCase()}
                        </span>
                        <div>
                          <strong>
                            {member.name}
                            {member.userId === user?.id ? " (you)" : ""}
                          </strong>
                          <small>{member.email}</small>
                        </div>
                      </div>
                    </td>
                    <td>
                      {manage &&
                      member.role !== "OWNER" &&
                      !(org.role === "ADMIN" && member.userId === user?.id) ? (
                        <select
                          aria-label={`Role for ${member.name}`}
                          value={member.role}
                          onChange={(e) =>
                            void change(`/members/${member.userId}`, "PATCH", {
                              role: e.target.value,
                            })
                          }
                        >
                          {["ADMIN", "MEMBER", "VIEWER"].map((role) => (
                            <option key={role}>{role}</option>
                          ))}
                        </select>
                      ) : (
                        <span className="badge">
                          <ShieldCheck size={12} />
                          {member.role}
                        </span>
                      )}
                    </td>
                    <td className="muted">
                      {new Date(member.joinedAt).toLocaleDateString()}
                    </td>
                    <td>
                      {manage &&
                        member.role !== "OWNER" &&
                        member.userId !== user?.id && (
                          <button
                            className="button quiet danger"
                            onClick={() => {
                              if (
                                window.confirm(
                                  `Remove ${member.name} from this workspace?`,
                                )
                              )
                                void change(
                                  `/members/${member.userId}`,
                                  "DELETE",
                                );
                            }}
                          >
                            Remove
                          </button>
                        )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
      {manage && (
        <section className="panel">
          <div className="panel-title">
            <h3>Invitations</h3>
            <Mail size={17} />
          </div>
          {invitations.error ? (
            <ErrorState error={invitations.error} />
          ) : invitations.isPending ? (
            <Loading />
          ) : !invitations.data?.length ? (
            <Empty title="No invitations yet" />
          ) : (
            <div className="list-rows">
              {invitations.data.map((invitation) => (
                <div className="list-row" key={invitation.id}>
                  <div>
                    <strong>{invitation.email}</strong>
                    <small>
                      {invitation.role} · Expires{" "}
                      {new Date(invitation.expiresAt).toLocaleDateString()}
                    </small>
                  </div>
                  <span className="badge">{invitation.status}</span>
                  {invitation.status === "PENDING" && (
                    <button
                      className="button quiet"
                      onClick={() =>
                        void change(`/invitations/${invitation.id}`, "DELETE")
                      }
                    >
                      Revoke
                    </button>
                  )}
                </div>
              ))}
            </div>
          )}
        </section>
      )}
      <Modal
        title="Invite someone to your workspace"
        open={invite}
        onOpenChange={setInvite}
      >
        <p className="muted">
          They’ll receive an email with a secure invitation link.
        </p>
        <Form
          fields={[
            {
              name: "email",
              label: "Email address",
              type: "email",
              required: true,
              max: 254,
            },
            {
              name: "role",
              label: "Workspace role",
              options: ["MEMBER", "ADMIN", "VIEWER"],
            },
          ]}
          submit="Send invitation"
          onSubmit={async (values) => {
            await api(`/organizations/${org.id}/invitations`, "POST", values);
            await refresh();
            setInvite(false);
            toast.success("Invitation sent");
          }}
        />
      </Modal>
    </>
  );
}

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import { Layers3, ArrowUpRight, Plus } from "lucide-react";
import { useState } from "react";
import { api, setSession, useSession } from "../../services/api";
import type { Organization } from "../../types";
import { Empty, ErrorState, Loading, Modal } from "../../components/UI";
import { Form } from "../../components/Form";
export default function Workspaces() {
  const query = useQuery({
    queryKey: ["organizations"],
    queryFn: () => api<Organization[]>("/organizations"),
  });
  const [creating, setCreating] = useState(false);
  const navigate = useNavigate();
  const cache = useQueryClient();
  const { user } = useSession();
  return (
    <main className="workspace-page">
      <header className="workspace-header">
        <Link className="brand" to="/">
          <Layers3 />
          teamflow<span className="brand-dot">.</span>
        </Link>
        <button
          className="button quiet"
          onClick={async () => {
            await api("/auth/logout", "POST");
            setSession(null);
            cache.clear();
            navigate("/login");
          }}
        >
          Sign out
        </button>
      </header>
      <div className="page-heading">
        <div>
          <span className="eyebrow">A SPACE FOR EVERY TEAM</span>
          <h1>Welcome, {user?.name.split(" ")[0]}.</h1>
          <p className="muted">Choose a workspace and make something happen.</p>
        </div>
        <button className="button primary" onClick={() => setCreating(true)}>
          <Plus size={17} />
          New workspace
        </button>
      </div>
      {query.isPending ? (
        <Loading />
      ) : query.error ? (
        <ErrorState error={query.error} />
      ) : !query.data?.length ? (
        <Empty title="Your first workspace starts here">
          <p>
            Create an organization to bring your projects and people together.
          </p>
        </Empty>
      ) : (
        <div className="workspace-grid">
          {query.data.map((org) => (
            <Link
              key={org.id}
              to={`/org/${org.id}/dashboard`}
              className="workspace-tile"
            >
              <div className="workspace-symbol">
                {org.name.slice(0, 2).toUpperCase()}
              </div>
              <ArrowUpRight className="tile-arrow" size={20} />
              <h2>{org.name}</h2>
              <p>{org.description || "A shared space for your team."}</p>
              <span className="badge">
                {org.archivedAt ? "ARCHIVED" : org.role}
              </span>
            </Link>
          ))}
        </div>
      )}
      <Modal
        title="Create a workspace"
        open={creating}
        onOpenChange={setCreating}
      >
        <Form
          fields={[
            {
              name: "name",
              label: "Organization name",
              required: true,
              max: 100,
            },
            { name: "description", label: "Description", type: "textarea" },
          ]}
          submit="Create workspace"
          onSubmit={async (values) => {
            const org = await api<Organization>(
              "/organizations",
              "POST",
              values,
            );
            await cache.invalidateQueries({ queryKey: ["organizations"] });
            navigate(`/org/${org.id}/dashboard`);
          }}
        />
      </Modal>
    </main>
  );
}

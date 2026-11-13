import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { useOrg, useRefreshOrg } from "../../hooks/useOrg";
import { api } from "../../services/api";
import { managesOrg } from "../../lib/permissions";
import type { Page, Activity, Notification } from "../../types";
import { Empty, ErrorState, Loading } from "../../components/UI";
import { ActivityList } from "./Dashboard";
export default function Updates({
  mode,
}: {
  mode: "activity" | "notifications" | "audit";
}) {
  const org = useOrg();
  const refresh = useRefreshOrg();
  const [page, setPage] = useState(0);
  const query = useQuery({
    queryKey: ["org", org.id, mode, page],
    queryFn: () =>
      api<
        Page<Activity & Notification & { action: string; resourceType: string }>
      >(`/organizations/${org.id}/${mode}?page=${page}`),
  });
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">STAY IN THE LOOP</span>
          <h1>
            {mode === "activity"
              ? "A shared sense of progress."
              : mode === "audit"
                ? "Your workspace audit trail."
                : "Your latest updates."}
          </h1>
          <p className="muted">
            {mode === "activity"
              ? "Meaningful moments across your accessible projects."
              : "Everything here belongs to this workspace."}
          </p>
        </div>
        {mode === "notifications" && (
          <button
            className="button"
            onClick={async () => {
              try {
                await api(
                  `/organizations/${org.id}/notifications/read-all`,
                  "PATCH",
                );
                await refresh();
              } catch (e) {
                toast.error((e as Error).message);
              }
            }}
          >
            Mark all read
          </button>
        )}
        {mode === "activity" && managesOrg(org.role) && (
          <Link className="button" to={`/org/${org.id}/audit`}>
            Security audit
          </Link>
        )}
      </div>
      {query.isPending ? (
        <Loading />
      ) : query.error ? (
        <ErrorState error={query.error} />
      ) : !query.data?.items.length ? (
        <Empty title="You’re all caught up" />
      ) : (
        <section className="panel">
          {mode === "activity" ? (
            <ActivityList items={query.data.items} />
          ) : (
            <div className="list-rows">
              {query.data.items.map((item) => (
                <div
                  key={item.id}
                  className={
                    "list-row " +
                    (mode === "notifications" && !item.readAt ? "unread" : "")
                  }
                >
                  <div>
                    <strong>
                      {mode === "notifications"
                        ? item.message
                        : item.action.replaceAll("_", " ")}
                    </strong>
                    <small>{new Date(item.createdAt).toLocaleString()}</small>
                  </div>
                  {mode === "notifications" && !item.readAt && (
                    <button
                      className="button quiet"
                      onClick={async () => {
                        try {
                          await api(
                            `/organizations/${org.id}/notifications/${item.id}/read`,
                            "PATCH",
                          );
                          await refresh();
                        } catch (e) {
                          toast.error((e as Error).message);
                        }
                      }}
                    >
                      Mark read
                    </button>
                  )}
                  {item.projectId && (
                    <Link
                      className="text-link"
                      to={`/org/${org.id}/projects/${item.projectId}`}
                    >
                      Open project
                    </Link>
                  )}
                </div>
              ))}
            </div>
          )}
          <div className="pagination">
            <span>{query.data.total} records</span>
            <button
              className="button"
              disabled={!page}
              onClick={() => setPage(page - 1)}
            >
              Previous
            </button>
            <button
              className="button"
              disabled={(page + 1) * query.data.size >= query.data.total}
              onClick={() => setPage(page + 1)}
            >
              Next
            </button>
          </div>
        </section>
      )}
    </>
  );
}

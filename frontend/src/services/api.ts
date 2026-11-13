import { useSyncExternalStore } from "react";
import type { User } from "../types";
type Session = { user: User | null; ready: boolean };
let token: string | null = null;
let state: Session = { user: null, ready: false };
const listeners = new Set<() => void>();
export function updateSessionUser(user: User) {
  state = { ...state, user };
  listeners.forEach((fn) => fn());
}
export function setSession(
  session: { accessToken: string; user: User } | null,
) {
  token = session?.accessToken ?? null;
  state = { user: session?.user ?? null, ready: true };
  listeners.forEach((fn) => fn());
}
export function useSession() {
  return useSyncExternalStore(
    (fn) => {
      listeners.add(fn);
      return () => {
        listeners.delete(fn);
      };
    },
    () => state,
  );
}
export class ApiError extends Error {
  status: number;
  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}
let refreshing: Promise<void> | null = null;
async function refresh() {
  const rotate = async () => {
    const response = await fetch("/api/v1/auth/refresh", {
      method: "POST",
      credentials: "include",
      headers: {
        "X-TeamFlow-Request": "TeamFlow",
        "Content-Type": "application/json",
      },
    });
    if (!response.ok) {
      setSession(null);
      throw new ApiError("Please sign in to continue", response.status);
    }
    setSession(await response.json());
  };
  refreshing ??= (
    navigator.locks
      ? navigator.locks.request("teamflow-refresh", rotate)
      : rotate()
  ).finally(() => {
    refreshing = null;
  });
  return refreshing;
}
export async function bootstrap() {
  try {
    await refresh();
  } catch {
    setSession(null);
  }
}
export async function api<T>(
  path: string,
  method = "GET",
  body?: unknown,
  retry = true,
): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    method,
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      "X-TeamFlow-Request": "TeamFlow",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  });
  if (response.status === 401 && retry && !path.startsWith("/auth/")) {
    await refresh();
    return api(path, method, body, false);
  }
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new ApiError(
      problem.detail ?? "Something went wrong. Please try again.",
      response.status,
    );
  }
  return response.status === 204 || response.status === 202
    ? (undefined as T)
    : response.json();
}

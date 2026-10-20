// One fetch wrapper for every call: adds the login token and turns error responses into ApiError.

const TOKEN_KEY = "flakehunter.token";

export const auth = {
  get: () => sessionStorage.getItem(TOKEN_KEY),
  set: (token: string) => sessionStorage.setItem(TOKEN_KEY, token),
  clear: () => sessionStorage.removeItem(TOKEN_KEY),
};

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    // Field name -> message, from the services' RFC 7807 "errors" property.
    readonly fields: Record<string, string> = {},
  ) {
    super(message);
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  const token = auth.get();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (init.body) headers.set("Content-Type", "application/json");

  const res = await fetch(path, { ...init, headers });
  if (res.status === 401 && path !== "/api/auth/login") {
    // Token missing or expired (8 h): start over at the login page.
    auth.clear();
    location.assign("/login");
  }
  if (!res.ok) {
    const problem = await res.json().catch(() => ({}));
    throw new ApiError(
      res.status,
      problem.detail ?? `${res.status} ${res.statusText}`,
      problem.errors,
    );
  }
  return res.json();
}

export const post = <T>(path: string, body: unknown) =>
  api<T>(path, { method: "POST", body: JSON.stringify(body) });

export type Project = {
  id: string;
  name: string;
  repoUrl: string;
  defaultBranch: string;
  testCommand: string | null;
  jdkVersion: number | null;
};

export type HuntStatus =
  | "CREATED"
  | "PREPARING"
  | "RUNNING"
  | "ANALYZING"
  | "COMPLETED"
  | "FAILED"
  | string;

export type Hunt = {
  id: string;
  projectId: string;
  commitSha: string;
  status: HuntStatus;
  failureReason: string | null;
  budget: { maxRuns: number; maxParallelPods: number };
  createdAt: string;
};

export type Finding = {
  id: string;
  testId: string;
  score: number;
  failureRate: number;
  evidence: { runs: number; failures: number; triggeringStrategies: string[] };
};

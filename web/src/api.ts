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
  return res.status === 204 ? (undefined as T) : res.json();
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
  budget: Budget;
  createdAt: string;
};

export type Budget = { maxRuns: number; maxParallelPods: number };

/** What this machine can run; the API rejects any other budget. */
export type HuntOptions = {
  maxRuns: number[];
  maxParallelPods: number[];
  defaults: Budget;
};

/** The AI kill switch; available is false under Compose, which runs no agents. */
export type AiSettings = {
  enabled: boolean;
  available: boolean;
  pendingFindings: number;
};

export type RunnerSettings = {
  memoryMb: number;
  totalMemoryMb: number;
  memoryMbOptions: number[];
  totalMemoryMbOptions: number[];
  dockerMemoryMb: number;
  dockerCpus: number;
  infraMemoryMb: number;
};

export type Finding = {
  id: string;
  huntId: string;
  testId: string;
  score: number;
  failureRate: number;
  evidence: { runs: number; failures: number; triggeringStrategies: string[] };
  diagnosis: Diagnosis | null; // null until an agent session reports
};

/** Run counts of one experiment or fix verification an agent asked for. */
export type ExperimentProgress = {
  id: string;
  findingId: string;
  verification: boolean;
  status: "PREPARING" | "RUNNING" | "COMPLETED" | "REJECTED" | "FAILED";
  planned: number;
  finished: number;
  failing: number; // runs where a planned test failed
  crashed: number; // runs that ended without results
};

/** The agents' session for one finding; status is null while it runs. */
export type Diagnosis = {
  status: string | null;
  category: string | null;
  confidence: number | null;
  explanation: string | null;
  patch: string | null; // the verified fix, a unified diff
  steps: Step[];
};

/** One entry of the reasoning timeline; the details depend on the agent. */
export type Step = {
  agent: string;
  at: string;
  summary: string;
  diff?: string;
  plan?: {
    tests: string[];
    strategy: string;
    timezone: string | null;
    repeat: number;
  };
};

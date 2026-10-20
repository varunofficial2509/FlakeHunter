import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { ApiError, api, auth } from "./api";

// Vitest runs in Node, which has fetch but no sessionStorage or location; stub just what api.ts uses.
const store = new Map<string, string>();
const assign = vi.fn();

beforeEach(() => {
  store.clear();
  vi.stubGlobal("sessionStorage", {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => store.set(k, v),
    removeItem: (k: string) => store.delete(k),
  });
  vi.stubGlobal("location", { assign });
});

afterEach(() => {
  vi.unstubAllGlobals();
  assign.mockReset();
});

test("sends the token as a Bearer header", async () => {
  const fetch = vi.fn().mockResolvedValue(Response.json([]));
  vi.stubGlobal("fetch", fetch);
  auth.set("abc");

  await api("/api/projects");

  expect(new Headers(fetch.mock.calls[0][1].headers).get("Authorization")).toBe(
    "Bearer abc",
  );
});

test("a 401 clears the token and goes to the login page", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(new Response(null, { status: 401 })),
  );
  auth.set("expired");

  await expect(api("/api/projects")).rejects.toBeInstanceOf(ApiError);
  expect(auth.get()).toBeNull();
  expect(assign).toHaveBeenCalledWith("/login");
});

test("field errors from a problem detail reach the caller", async () => {
  const problem = {
    detail: "Invalid request content.",
    errors: { repoUrl: "must be https://github.com/<owner>/<repo>" },
  };
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(Response.json(problem, { status: 400 })),
  );

  await expect(api("/api/projects")).rejects.toMatchObject({
    status: 400,
    fields: { repoUrl: expect.stringContaining("github.com") },
  });
});

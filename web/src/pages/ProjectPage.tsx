import { useMutation, useQuery } from "@tanstack/react-query";
import type { FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router";
import {
  ApiError,
  api,
  post,
  type Budget,
  type Hunt,
  type HuntOptions,
  type Project,
} from "../api";

export function ProjectPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const project = useQuery({
    queryKey: ["project", id],
    queryFn: () => api<Project>(`/api/projects/${id}`),
  });
  const hunts = useQuery({
    queryKey: ["hunts", id],
    queryFn: () => api<Hunt[]>(`/api/hunts?projectId=${id}`),
  });
  const options = useQuery({
    queryKey: ["hunt-options"],
    queryFn: () => api<HuntOptions>("/api/hunts/options"),
  });
  const start = useMutation({
    mutationFn: (body: { commitSha: string; budget: Budget }) =>
      post<Hunt>("/api/hunts", { projectId: id, ...body }),
    onSuccess: (hunt) => navigate(`/hunts/${hunt.id}`),
  });

  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    start.mutate({
      commitSha: String(form.get("commitSha")).trim(),
      budget: {
        maxRuns: Number(form.get("maxRuns")),
        maxParallelPods: Number(form.get("maxParallelPods")),
      },
    });
  }

  if (project.error) return <p role="alert">{project.error.message}</p>;
  if (options.error) return <p role="alert">{options.error.message}</p>;
  if (!project.data || !options.data) return <p>Loading…</p>;
  const o = options.data;
  const p = project.data;
  const shaError =
    start.error instanceof ApiError
      ? (start.error.fields.commitSha ??
        start.error.fields.budget ??
        start.error.message)
      : null;

  return (
    <>
      <h1>{p.name}</h1>
      <p>
        <a href={p.repoUrl}>{p.repoUrl}</a> · branch {p.defaultBranch} · JDK{" "}
        {p.jdkVersion ?? "?"}
      </p>

      <h2>Start a hunt</h2>
      <form onSubmit={submit}>
        <label>
          Commit SHA (full 40 characters)
          <input
            name="commitSha"
            pattern="[0-9a-f]{40}"
            required
            spellCheck={false}
          />
        </label>
        <label>
          Runs
          <select name="maxRuns" defaultValue={o.defaults.maxRuns}>
            {o.maxRuns.map((n) => (
              <option key={n}>{n}</option>
            ))}
          </select>
        </label>
        <label>
          Containers at once (what this machine's Docker memory allows)
          <select
            name="maxParallelPods"
            defaultValue={o.defaults.maxParallelPods}
          >
            {o.maxParallelPods.map((n) => (
              <option key={n}>{n}</option>
            ))}
          </select>
        </label>
        {shaError && <p role="alert">{shaError}</p>}
        <button type="submit" disabled={start.isPending}>
          Start hunt
        </button>
      </form>

      <h2>Hunts</h2>
      {hunts.data?.length === 0 && <p>No hunts yet.</p>}
      <ul>
        {hunts.data?.map((h) => (
          <li key={h.id}>
            <Link to={`/hunts/${h.id}`}>
              {new Date(h.createdAt).toLocaleString()}
            </Link>{" "}
            {h.status} <code>{h.commitSha.slice(0, 8)}</code>
          </li>
        ))}
      </ul>
    </>
  );
}

import { useMutation, useQuery } from "@tanstack/react-query";
import type { FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { ApiError, api, post, type Hunt, type Project } from "../api";

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
  const start = useMutation({
    mutationFn: (commitSha: string) =>
      post<Hunt>("/api/hunts", { projectId: id, commitSha }),
    onSuccess: (hunt) => navigate(`/hunts/${hunt.id}`),
  });

  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    start.mutate(String(new FormData(e.currentTarget).get("commitSha")).trim());
  }

  if (project.error) return <p role="alert">{project.error.message}</p>;
  if (!project.data) return <p>Loading…</p>;
  const p = project.data;
  const shaError =
    start.error instanceof ApiError
      ? (start.error.fields.commitSha ?? start.error.message)
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

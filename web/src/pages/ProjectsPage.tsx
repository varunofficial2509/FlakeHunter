import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { FormEvent } from "react";
import { Link } from "react-router";
import { ApiError, api, post, type Project } from "../api";

export function ProjectsPage() {
  const projects = useQuery({
    queryKey: ["projects"],
    queryFn: () => api<Project[]>("/api/projects"),
  });
  const queryClient = useQueryClient();
  const create = useMutation({
    mutationFn: (body: object) => post<Project>("/api/projects", body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["projects"] }),
  });

  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    create.mutate(
      {
        name: form.get("name"),
        repoUrl: form.get("repoUrl"),
        defaultBranch: form.get("defaultBranch") || null,
        buildTool: "MAVEN",
        jdkVersion: Number(form.get("jdkVersion")),
        testCommand: form.get("testCommand") || null,
      },
      { onSuccess: () => (e.target as HTMLFormElement).reset() },
    );
  }

  const fields = create.error instanceof ApiError ? create.error.fields : {};

  return (
    <>
      <h1>Projects</h1>
      {projects.isPending && <p>Loading…</p>}
      {projects.error && <p role="alert">{projects.error.message}</p>}
      <ul>
        {projects.data?.map((p) => (
          <li key={p.id}>
            <Link to={`/projects/${p.id}`}>{p.name}</Link>{" "}
            <small>{p.repoUrl}</small>
          </li>
        ))}
      </ul>

      <h2>Add a project</h2>
      <form onSubmit={submit}>
        <label>
          Name <input name="name" required />
          {fields.name && <small role="alert">{fields.name}</small>}
        </label>
        <label>
          GitHub URL{" "}
          <input
            name="repoUrl"
            placeholder="https://github.com/owner/repo"
            required
          />
          {fields.repoUrl && <small role="alert">{fields.repoUrl}</small>}
        </label>
        <label>
          Branch <input name="defaultBranch" placeholder="main" />
          {fields.defaultBranch && (
            <small role="alert">{fields.defaultBranch}</small>
          )}
        </label>
        <label>
          JDK
          <select name="jdkVersion" defaultValue="17">
            {[8, 11, 17, 21].map((v) => (
              <option key={v}>{v}</option>
            ))}
          </select>
        </label>
        <label>
          Extra Maven arguments{" "}
          <input name="testCommand" placeholder="-Djdk.version=1.8" />
        </label>
        {create.error && !Object.keys(fields).length && (
          <p role="alert">{create.error.message}</p>
        )}
        <button type="submit" disabled={create.isPending}>
          Add project
        </button>
      </form>
    </>
  );
}

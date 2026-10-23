import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { FormEvent } from "react";
import { Link } from "react-router";
import { ApiError, api, post, type Project } from "../api";
import {
  Alert,
  Button,
  Card,
  Empty,
  Field,
  Icon,
  PageHeader,
  Skeleton,
} from "../ui";

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
      <PageHeader
        title="Projects"
        subtitle="Java repositories FlakeHunter can hunt flaky tests in."
      />

      <Card title="Your projects" icon="folder">
        {projects.isPending && <Skeleton />}
        {projects.error && <Alert>{projects.error.message}</Alert>}
        {projects.data?.length === 0 && (
          <Empty title="No projects yet" icon="folder">
            Add a GitHub repository below to start your first hunt.
          </Empty>
        )}
        {!!projects.data?.length && (
          <div className="project-grid">
            {projects.data.map((p) => (
              <Link
                key={p.id}
                to={`/projects/${p.id}`}
                className="project-card"
              >
                <strong>
                  <Icon name="folder" />
                  {p.name}
                </strong>
                <small>{p.repoUrl}</small>
                <small>
                  <Icon name="branch" size={12} /> {p.defaultBranch} · JDK{" "}
                  {p.jdkVersion ?? "?"}
                </small>
              </Link>
            ))}
          </div>
        )}
      </Card>

      <Card title="Add a project" icon="plus">
        <form onSubmit={submit}>
          <div className="form-row">
            <Field label="Name" error={fields.name}>
              <input name="name" required />
            </Field>
            <Field label="GitHub URL" error={fields.repoUrl}>
              <input
                name="repoUrl"
                placeholder="https://github.com/owner/repo"
                required
              />
            </Field>
          </div>
          <div className="form-row">
            <Field
              label="Branch"
              hint="Defaults to main"
              error={fields.defaultBranch}
            >
              <input name="defaultBranch" placeholder="main" />
            </Field>
            <Field label="JDK">
              <select name="jdkVersion" defaultValue="17">
                {[8, 11, 17, 21].map((v) => (
                  <option key={v}>{v}</option>
                ))}
              </select>
            </Field>
            <Field label="Extra Maven arguments" hint="Optional">
              <input
                name="testCommand"
                placeholder="-Djdk.version=1.8"
                className="mono"
              />
            </Field>
          </div>
          {create.error && !Object.keys(fields).length && (
            <Alert>{create.error.message}</Alert>
          )}
          {create.isSuccess && <Alert kind="success">Project added.</Alert>}
          <Button type="submit" icon="plus" pending={create.isPending}>
            Add project
          </Button>
        </form>
      </Card>
    </>
  );
}

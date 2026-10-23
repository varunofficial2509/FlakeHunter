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
import {
  Alert,
  Button,
  Card,
  Empty,
  Field,
  Icon,
  PageHeader,
  PageSkeleton,
  Skeleton,
  StatusBadge,
  shortSha,
} from "../ui";

const FINISHED = ["COMPLETED", "FAILED", "CANCELLED", "AWAITING_AI"];

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
    // Keep the status badges live while any hunt is still working.
    refetchInterval: (query) =>
      query.state.data?.some((h) => !FINISHED.includes(h.status))
        ? 3000
        : false,
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

  if (project.error) return <Alert>{project.error.message}</Alert>;
  if (options.error) return <Alert>{options.error.message}</Alert>;
  if (!project.data || !options.data) return <PageSkeleton />;
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
      <PageHeader
        back={
          <Link to="/">
            <Icon name="back" /> Projects
          </Link>
        }
        title={p.name}
        subtitle={
          <>
            <span>
              <Icon name="external" size={14} />
              <a href={p.repoUrl} target="_blank" rel="noreferrer">
                {p.repoUrl.replace(/^https?:\/\//, "")}
              </a>
            </span>
            <span>
              <Icon name="branch" size={14} />
              {p.defaultBranch}
            </span>
            <span>
              <Icon name="cpu" size={14} />
              JDK {p.jdkVersion ?? "?"}
            </span>
          </>
        }
      />

      <Card title="Start a hunt" icon="play">
        <form onSubmit={submit}>
          <Field label="Commit SHA" hint="The full 40-character hash">
            <input
              name="commitSha"
              className="mono"
              pattern="[0-9a-f]{40}"
              placeholder="e.g. 3f2c9a1…"
              required
              spellCheck={false}
            />
          </Field>
          <div className="form-row">
            <Field label="Runs">
              <select name="maxRuns" defaultValue={o.defaults.maxRuns}>
                {o.maxRuns.map((n) => (
                  <option key={n}>{n}</option>
                ))}
              </select>
            </Field>
            <Field
              label="Containers at once"
              hint="What this machine's Docker memory allows"
            >
              <select
                name="maxParallelPods"
                defaultValue={o.defaults.maxParallelPods}
              >
                {o.maxParallelPods.map((n) => (
                  <option key={n}>{n}</option>
                ))}
              </select>
            </Field>
          </div>
          {shaError && <Alert>{shaError}</Alert>}
          <Button type="submit" icon="play" pending={start.isPending}>
            {start.isPending ? "Starting…" : "Start hunt"}
          </Button>
        </form>
      </Card>

      <Card title="Hunts" icon="clock">
        {hunts.isPending && <Skeleton />}
        {hunts.error && <Alert>{hunts.error.message}</Alert>}
        {hunts.data?.length === 0 && (
          <Empty title="No hunts yet" icon="bug">
            Start one above with a commit SHA from this repository.
          </Empty>
        )}
        {!!hunts.data?.length && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Started</th>
                  <th>Commit</th>
                  <th>Status</th>
                  <th>Budget</th>
                </tr>
              </thead>
              <tbody>
                {hunts.data.map((h) => (
                  <tr key={h.id}>
                    <td>
                      <Link to={`/hunts/${h.id}`}>
                        {new Date(h.createdAt).toLocaleString()}
                      </Link>
                    </td>
                    <td>
                      <code>{shortSha(h.commitSha)}</code>
                    </td>
                    <td>
                      <StatusBadge status={h.status} />
                    </td>
                    <td className="num muted">
                      {h.budget.maxRuns} runs · {h.budget.maxParallelPods} at
                      once
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { api, type AiSettings, type RunnerSettings } from "../api";
import {
  Alert,
  Badge,
  Button,
  Card,
  Field,
  PageHeader,
  PageSkeleton,
  Skeleton,
  Spinner,
  Switch,
} from "../ui";

const gb = (mb: number) => `${(mb / 1024).toFixed(1)} GB`;

export function AdminPage() {
  const queryClient = useQueryClient();
  const runner = useQuery({
    queryKey: ["admin-runner"],
    queryFn: () => api<RunnerSettings>("/api/admin/runner"),
  });
  const save = useMutation({
    mutationFn: (body: { memoryMb: number; totalMemoryMb: number }) =>
      api<RunnerSettings>("/api/admin/runner", {
        method: "PUT",
        body: JSON.stringify(body),
      }),
    onSuccess: (data) => {
      queryClient.setQueryData(["admin-runner"], data);
      queryClient.invalidateQueries({ queryKey: ["hunt-options"] });
    },
  });
  const clearCache = useMutation({
    mutationFn: () =>
      api<void>("/api/admin/runner/maven-cache", { method: "DELETE" }),
  });
  const ai = useQuery({
    queryKey: ["admin-ai"],
    queryFn: () => api<AiSettings>("/api/admin/ai"),
    refetchInterval: 5000, // watch the backlog drain
  });
  const switchAi = useMutation({
    mutationFn: (enabled: boolean) =>
      api<AiSettings>("/api/admin/ai", {
        method: "PUT",
        body: JSON.stringify({ enabled }),
      }),
    onSuccess: (data) => queryClient.setQueryData(["admin-ai"], data),
  });
  const [memory, setMemory] = useState<number>();

  if (runner.error) return <Alert>{runner.error.message}</Alert>;
  if (!runner.data) return <PageSkeleton />;
  const r = runner.data;
  const perRunner = memory ?? r.memoryMb;

  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    save.mutate({
      memoryMb: Number(form.get("memoryMb")),
      totalMemoryMb: Number(form.get("totalMemoryMb")),
    });
  }

  return (
    <>
      <PageHeader
        title="Admin"
        subtitle="Runner resources, AI agents and caches."
      />

      <Card title="AI agents" icon="sparkles">
        {ai.isPending && <Skeleton lines={2} />}
        {ai.error && <Alert>{ai.error.message}</Alert>}
        {ai.data && !ai.data.available && (
          <Alert kind="info">
            Agents run only in the Kubernetes deployment.
          </Alert>
        )}
        {ai.data?.available && (
          <>
            <div className="toggle-row">
              <div>
                <strong>
                  AI diagnosis
                  <Badge tone={ai.data.enabled ? "ai" : "neutral"}>
                    {ai.data.enabled ? "on" : "off"}
                  </Badge>
                  {switchAi.isPending && <Spinner />}
                </strong>
                <small className="muted">
                  {ai.data.enabled
                    ? "Detection never needs them; switching off stops the agent pods."
                    : "AI analysis paused: findings wait in Kafka until you switch it on."}
                </small>
              </div>
              <Switch
                label="AI agents"
                checked={ai.data.enabled}
                disabled={switchAi.isPending}
                onChange={(enabled) => switchAi.mutate(enabled)}
              />
            </div>
            <p className="muted">
              <strong>{ai.data.pendingFindings}</strong> findings waiting for a
              diagnosis.
            </p>
          </>
        )}
        {switchAi.error && <Alert>{switchAi.error.message}</Alert>}
        {switchAi.isSuccess && (
          <Alert kind="success">
            AI agents switched {switchAi.data.enabled ? "on" : "off"}.
          </Alert>
        )}
      </Card>

      <Card title="Runners" icon="cpu">
        {/* infraMemoryMb is 0 in Kubernetes: there the runners have their own quota. */}
        <p className="muted">
          {r.infraMemoryMb > 0
            ? `Docker has ${gb(r.dockerMemoryMb)} and ${r.dockerCpus} CPUs; ${gb(r.infraMemoryMb)} of that is kept for Kafka and Postgres (set by the deploy preset).`
            : `Runners share a ${gb(r.dockerMemoryMb)} quota in the cluster (set by the Helm chart) on ${r.dockerCpus} CPUs.`}{" "}
          Changes apply to the next hunt.
        </p>
        <form onSubmit={submit}>
          <div className="form-row">
            <Field label="Memory per runner container">
              <select
                name="memoryMb"
                defaultValue={r.memoryMb}
                onChange={(e) => setMemory(Number(e.target.value))}
              >
                {r.memoryMbOptions.map((mb) => (
                  <option key={mb} value={mb}>
                    {gb(mb)}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Total for all runners of a hunt">
              <select name="totalMemoryMb" defaultValue={r.totalMemoryMb}>
                {r.totalMemoryMbOptions
                  .filter((mb) => mb >= perRunner)
                  .map((mb) => (
                    <option key={mb} value={mb}>
                      {gb(mb)} ({Math.floor(mb / perRunner)} at once)
                    </option>
                  ))}
              </select>
            </Field>
          </div>
          {save.error && <Alert>{save.error.message}</Alert>}
          {save.isSuccess && (
            <Alert kind="success">
              Saved. The next hunt uses these limits.
            </Alert>
          )}
          <Button type="submit" pending={save.isPending} icon="check">
            {save.isPending ? "Saving…" : "Save"}
          </Button>
        </form>
      </Card>

      <Card title="Maven cache" icon="database">
        <p className="muted">
          Dependencies downloaded by earlier hunts. Clear it if it grows too big
          or a download got corrupted; the next hunt downloads them again.
        </p>
        <Button
          variant="danger"
          icon="trash"
          onClick={() =>
            confirm("Delete the Maven cache?") && clearCache.mutate()
          }
          pending={clearCache.isPending}
        >
          {clearCache.isPending ? "Clearing…" : "Clear Maven cache"}
        </Button>
        {clearCache.error && (
          <div style={{ marginTop: 12 }}>
            <Alert>{clearCache.error.message}</Alert>
          </div>
        )}
        {clearCache.isSuccess && (
          <div style={{ marginTop: 12 }}>
            <Alert kind="success">Maven cache cleared.</Alert>
          </div>
        )}
      </Card>
    </>
  );
}

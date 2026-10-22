import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { api, type AiSettings, type RunnerSettings } from "../api";

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

  if (runner.error) return <p role="alert">{runner.error.message}</p>;
  if (!runner.data) return <p>Loading…</p>;
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
      <h1>Admin: runners</h1>
      {/* infraMemoryMb is 0 in Kubernetes: there the runners have their own quota. */}
      {r.infraMemoryMb > 0 ? (
        <p>
          Docker has {gb(r.dockerMemoryMb)} and {r.dockerCpus} CPUs;{" "}
          {gb(r.infraMemoryMb)} of that is kept for Kafka and Postgres (set by
          the deploy preset). Changes apply to the next hunt.
        </p>
      ) : (
        <p>
          Runners share a {gb(r.dockerMemoryMb)} quota in the cluster (set by
          the Helm chart) on {r.dockerCpus} CPUs. Changes apply to the next
          hunt.
        </p>
      )}

      <form onSubmit={submit}>
        <label>
          Memory per runner container
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
        </label>
        <label>
          Total for all runners of a hunt
          <select name="totalMemoryMb" defaultValue={r.totalMemoryMb}>
            {r.totalMemoryMbOptions
              .filter((mb) => mb >= perRunner)
              .map((mb) => (
                <option key={mb} value={mb}>
                  {gb(mb)} ({Math.floor(mb / perRunner)} at once)
                </option>
              ))}
          </select>
        </label>
        {save.error && <p role="alert">{save.error.message}</p>}
        {save.isSuccess && <p>Saved.</p>}
        <button type="submit" disabled={save.isPending}>
          Save
        </button>
      </form>

      <h2>AI agents</h2>
      {ai.data && !ai.data.available && (
        <p>Agents run only in the Kubernetes deployment.</p>
      )}
      {ai.data?.available && (
        <>
          <p>
            AI agents are <strong>{ai.data.enabled ? "on" : "off"}</strong>.{" "}
            {ai.data.enabled
              ? "Detection never needs them; switching off stops the agent pods."
              : "AI analysis paused: findings wait in Kafka until you switch it on."}{" "}
            {ai.data.pendingFindings} findings waiting for a diagnosis.
          </p>
          <button
            onClick={() => switchAi.mutate(!ai.data.enabled)}
            disabled={switchAi.isPending}
          >
            {ai.data.enabled ? "Switch AI off" : "Switch AI on"}
          </button>
        </>
      )}
      {ai.error && <p role="alert">{ai.error.message}</p>}
      {switchAi.error && <p role="alert">{switchAi.error.message}</p>}

      <h2>Maven cache</h2>
      <p>
        Dependencies downloaded by earlier hunts. Clear it if it grows too big
        or a download got corrupted; the next hunt downloads them again.
      </p>
      <button
        onClick={() =>
          confirm("Delete the Maven cache?") && clearCache.mutate()
        }
        disabled={clearCache.isPending}
      >
        Clear Maven cache
      </button>
      {clearCache.error && <p role="alert">{clearCache.error.message}</p>}
      {clearCache.isSuccess && <p>Cleared.</p>}
    </>
  );
}

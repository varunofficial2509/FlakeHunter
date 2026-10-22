import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router";
import { api, type Finding, type Hunt } from "../api";

const FINISHED = ["COMPLETED", "FAILED", "CANCELLED"];
// Detection is over in these: the findings are final, agents may still be diagnosing them.
const ANALYZED = ["COMPLETED", "DIAGNOSING", "AWAITING_AI"];

export function HuntPage() {
  const { id } = useParams();
  const hunt = useQuery({
    queryKey: ["hunt", id],
    queryFn: () => api<Hunt>(`/api/hunts/${id}`),
    // shortcut: polls every 2 s; switch to the gateway's SSE stream when it exists (tech doc section 5).
    refetchInterval: (query) =>
      FINISHED.includes(query.state.data?.status ?? "") ? false : 2000,
  });
  const done = ANALYZED.includes(hunt.data?.status ?? "");
  const findings = useQuery({
    queryKey: ["findings", id],
    queryFn: () => api<Finding[]>(`/api/findings?huntId=${id}`),
    enabled: done,
  });

  if (hunt.error) return <p role="alert">{hunt.error.message}</p>;
  if (!hunt.data) return <p>Loading…</p>;
  const h = hunt.data;

  return (
    <>
      <p>
        <Link to={`/projects/${h.projectId}`}>← project</Link>
      </p>
      <h1>Hunt {h.commitSha.slice(0, 8)}</h1>
      <p>
        Status: <strong>{h.status}</strong>
        {!FINISHED.includes(h.status) && " (refreshing…)"} · up to{" "}
        {h.budget.maxRuns} runs, {h.budget.maxParallelPods} at a time
      </p>
      {h.failureReason && <p role="alert">{h.failureReason}</p>}
      {h.status === "AWAITING_AI" && (
        <p role="status">
          AI analysis paused: the findings wait until AI agents are switched on
          in <Link to="/admin">Admin</Link>.
        </p>
      )}

      {done && (
        <>
          <h2>Findings</h2>
          {findings.data?.length === 0 && <p>No flaky tests found.</p>}
          {!!findings.data?.length && (
            <table>
              <thead>
                <tr>
                  <th>Test</th>
                  <th>Score</th>
                  <th>Failed</th>
                  <th>Triggered by</th>
                </tr>
              </thead>
              <tbody>
                {findings.data.map((f) => (
                  <tr key={f.id}>
                    <td>
                      <code>{f.testId}</code>
                    </td>
                    <td>{Number(f.score).toFixed(2)}</td>
                    <td>
                      {f.evidence.failures}/{f.evidence.runs}
                    </td>
                    <td>
                      {f.evidence.triggeringStrategies.join(", ") ||
                        "fails without any change"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </>
  );
}

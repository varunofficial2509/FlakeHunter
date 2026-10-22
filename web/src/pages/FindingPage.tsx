import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router";
import { api, type Finding } from "../api";
import { Diff } from "./Diff";

const OUTCOME: Record<string, string> = {
  FIX_VERIFIED: "Fix verified",
  FIX_FAILED: "No fix survived verification",
  FIX_REJECTED: "The critic rejected every fix",
  INVESTIGATED: "Cause proven, no fix proposed",
  UNKNOWN: "No cause proven",
  BUDGET_EXCEEDED: "Stopped at the token budget",
  ERROR: "The agent session failed",
};

export function FindingPage() {
  const { id } = useParams();
  const finding = useQuery({
    queryKey: ["finding", id],
    queryFn: () => api<Finding>(`/api/findings/${id}`),
    // shortcut: polls every 3 s until the session ends; switch to SSE with the hunt page.
    refetchInterval: (query) =>
      query.state.data?.diagnosis?.status ? false : 3000,
  });

  if (finding.error) return <p role="alert">{finding.error.message}</p>;
  if (!finding.data) return <p>Loading…</p>;
  const f = finding.data;
  const d = f.diagnosis;

  return (
    <>
      <p>
        <Link to={`/hunts/${f.huntId}`}>← hunt</Link>
      </p>
      <h1>
        <code>{f.testId}</code>
      </h1>
      <p>
        Failed {f.evidence.failures}/{f.evidence.runs} runs, triggered by{" "}
        {f.evidence.triggeringStrategies.join(", ") ||
          "nothing: it fails without any change"}
        .
      </p>

      <h2>Diagnosis</h2>
      {!d && (
        <p>
          Waiting for the agents (if AI is off, switch it on in{" "}
          <Link to="/admin">Admin</Link>).
        </p>
      )}
      {d && (
        <p>
          <strong>
            {d.status ? (OUTCOME[d.status] ?? d.status) : "Diagnosing…"}
          </strong>
          {d.category && (
            <>
              {" "}
              · {d.category}
              {d.confidence != null &&
                `, confidence ${Math.round(d.confidence * 100)}%`}
            </>
          )}
          {d.explanation && (
            <>
              <br />
              {d.explanation}
            </>
          )}
        </p>
      )}
      {d?.patch && (
        <>
          <h2>Verified fix</h2>
          <Diff diff={d.patch} />
        </>
      )}

      {!!d?.steps.length && (
        <>
          <h2>Reasoning timeline</h2>
          <ol className="timeline">
            {d.steps.map((s, i) => (
              <li key={i}>
                <time dateTime={s.at}>
                  {new Date(s.at).toLocaleTimeString()}
                </time>{" "}
                <strong>{s.agent}</strong>: {s.summary}
                {s.plan && (
                  <div className="plan">
                    {s.plan.strategy}
                    {s.plan.timezone && ` ${s.plan.timezone}`} × {s.plan.repeat}{" "}
                    on {s.plan.tests.join(", ")}
                  </div>
                )}
                {s.diff && <Diff diff={s.diff} />}
              </li>
            ))}
          </ol>
        </>
      )}
    </>
  );
}

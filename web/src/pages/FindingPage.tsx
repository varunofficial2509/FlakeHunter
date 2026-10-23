import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router";
import { api, type ExperimentProgress, type Finding } from "../api";
import {
  Alert,
  Badge,
  Card,
  Empty,
  Icon,
  PageHeader,
  PageSkeleton,
  Progress,
  StatusBadge,
} from "../ui";
import { Busy, isRunning, progressText } from "./Busy";
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
  const huntId = finding.data?.huntId;
  const over = !!finding.data?.diagnosis?.status;
  const experiments = useQuery({
    queryKey: ["experiments", huntId],
    queryFn: () =>
      api<ExperimentProgress[]>(`/api/hunts/${huntId}/experiments`),
    enabled: !!huntId && !over,
    refetchInterval: 3000,
  });

  if (finding.error) return <Alert>{finding.error.message}</Alert>;
  if (!finding.data) return <PageSkeleton />;
  const f = finding.data;
  const d = f.diagnosis;
  const running = over
    ? []
    : (experiments.data ?? []).filter(
        (e) => e.findingId === f.id && isRunning(e),
      );

  return (
    <>
      <PageHeader
        back={
          <Link to={`/hunts/${f.huntId}`}>
            <Icon name="back" /> Hunt
          </Link>
        }
        title={<code>{f.testId}</code>}
        subtitle={
          <>
            <span>
              <Icon name="bug" size={14} />
              Failed {f.evidence.failures}/{f.evidence.runs} runs
            </span>
            <span>
              Triggered by{" "}
              {f.evidence.triggeringStrategies.length
                ? f.evidence.triggeringStrategies.map((s) => (
                    <Badge key={s} tone="info">
                      {s}
                    </Badge>
                  ))
                : "nothing extra: it fails in the default setup too (UTC, normal test order)"}
            </span>
          </>
        }
      />

      <div className="stats">
        <div className="stat">
          <small>Flakiness score</small>
          <strong>{Number(f.score).toFixed(2)}</strong>
        </div>
        <div className="stat">
          <small>Failure rate</small>
          <strong>{Math.round(f.failureRate * 100)}%</strong>
        </div>
        <div className="stat">
          <small>Runs</small>
          <strong>{f.evidence.runs}</strong>
        </div>
        {d?.confidence != null && (
          <div className="stat">
            <small>AI confidence</small>
            <strong>{Math.round(d.confidence * 100)}%</strong>
          </div>
        )}
      </div>

      <Card title="Diagnosis" icon="sparkles">
        {!d && (
          <Empty title="Waiting for the agents" icon="sparkles">
            If AI is off, switch it on in <Link to="/admin">Admin</Link>.
          </Empty>
        )}
        {d && (
          <div className="diagnosis">
            <div className="row">
              {d.status ? (
                <StatusBadge
                  status={d.status}
                  label={OUTCOME[d.status] ?? d.status}
                />
              ) : (
                <StatusBadge status="DIAGNOSING" />
              )}
              {d.category && <Badge tone="ai">{d.category}</Badge>}
            </div>
            {d.explanation && <p>{d.explanation}</p>}
          </div>
        )}
      </Card>

      {d?.patch && (
        <Card title="Verified fix" icon="check">
          <Diff diff={d.patch} />
        </Card>
      )}

      {!!d?.steps.length && (
        <Card title="Reasoning timeline" icon="clock">
          <ol className="timeline">
            {d.steps.map((s, i) => (
              <li key={i}>
                <div className="step-head">
                  <Badge tone="ai">{s.agent}</Badge>
                  <time dateTime={s.at}>
                    {new Date(s.at).toLocaleTimeString()}
                  </time>
                </div>
                {s.summary}
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
            {running.map((e) => (
              <li key={e.id} className="running">
                <Busy>{progressText(e)}</Busy>
                <Progress
                  label={progressText(e)}
                  value={e.status === "PREPARING" ? null : e.finished}
                  max={e.planned}
                />
              </li>
            ))}
          </ol>
        </Card>
      )}
    </>
  );
}

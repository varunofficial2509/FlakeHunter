import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router";
import {
  api,
  type ExperimentProgress,
  type Finding,
  type Hunt,
  type RunProgress,
} from "../api";
import {
  Alert,
  Card,
  Empty,
  Icon,
  PageHeader,
  PageSkeleton,
  Progress,
  Skeleton,
  StatusBadge,
  shortSha,
} from "../ui";
import { Busy, isRunning, progressText } from "./Busy";

const FINISHED = ["COMPLETED", "FAILED", "CANCELLED"];
// Detection is over in these: the findings are final, agents may still be diagnosing them.
const ANALYZED = ["COMPLETED", "DIAGNOSING", "AWAITING_AI"];
const DETECTING = ["CREATED", "PREPARING", "RUNNING"];

// The phases shown as a stepper; each status maps to the phase it belongs to.
const PHASES = [
  "Preparing",
  "Running tests",
  "Analyzing",
  "AI diagnosis",
  "Completed",
];
const PHASE: Record<string, number> = {
  CREATED: 0,
  PREPARING: 0,
  RUNNING: 1,
  ANALYZING: 2,
  AWAITING_AI: 3,
  DIAGNOSING: 3,
  VERIFYING: 3,
  COMPLETED: 5, // past the last step: everything done
};

function diagnosisCell(f: Finding, experiments: ExperimentProgress[]) {
  if (f.diagnosis?.status) return <StatusBadge status={f.diagnosis.status} />;
  if (!f.diagnosis) return <StatusBadge status="CREATED" label="waiting" />;
  const running = experiments.find((e) => e.findingId === f.id && isRunning(e));
  return running ? (
    <span className="muted">
      <Busy>{progressText(running)}</Busy>
    </span>
  ) : (
    <StatusBadge status="DIAGNOSING" />
  );
}

export function HuntPage() {
  const { id } = useParams();
  const hunt = useQuery({
    queryKey: ["hunt", id],
    queryFn: () => api<Hunt>(`/api/hunts/${id}`),
    // shortcut: polls every 2 s; switch to the gateway's SSE stream when it exists (tech doc section 5).
    refetchInterval: (query) =>
      FINISHED.includes(query.state.data?.status ?? "") ? false : 2000,
  });
  const status = hunt.data?.status ?? "";
  const progress = useQuery({
    queryKey: ["hunt-progress", id],
    queryFn: () => api<RunProgress>(`/api/hunts/${id}/progress`),
    enabled: !!hunt.data,
    refetchInterval: DETECTING.includes(status) ? 2000 : false,
  });
  const done = ANALYZED.includes(status);
  const findings = useQuery({
    queryKey: ["findings", id],
    queryFn: () => api<Finding[]>(`/api/findings?huntId=${id}`),
    enabled: done,
    // While agents work, their status per finding changes.
    refetchInterval: status === "COMPLETED" ? false : 3000,
  });
  const experiments = useQuery({
    queryKey: ["experiments", id],
    queryFn: () => api<ExperimentProgress[]>(`/api/hunts/${id}/experiments`),
    enabled: done && status !== "COMPLETED",
    refetchInterval: 3000,
  });

  if (hunt.error) return <Alert>{hunt.error.message}</Alert>;
  if (!hunt.data) return <PageSkeleton />;
  const h = hunt.data;
  const phase = PHASE[h.status];
  const p = progress.data;
  // Runs are planned when preparing ends; until then the budget is the best total.
  const total = p?.planned || h.budget.maxRuns;
  const preparing = !p?.planned && DETECTING.includes(h.status);

  return (
    <>
      <PageHeader
        back={
          <Link to={`/projects/${h.projectId}`}>
            <Icon name="back" /> Project
          </Link>
        }
        title={
          <>
            Hunt <code>{shortSha(h.commitSha)}</code>
          </>
        }
        subtitle={
          <>
            <span>
              <Icon name="clock" size={14} />
              {new Date(h.createdAt).toLocaleString()}
            </span>
            <span>
              <Icon name="cpu" size={14} />
              up to {h.budget.maxRuns} runs, {h.budget.maxParallelPods} at a
              time
            </span>
          </>
        }
        actions={<StatusBadge status={h.status} />}
      />

      <Card title="Progress" icon="play">
        {phase != null && (
          <ol className="steps">
            {PHASES.map((name, i) => (
              <li
                key={name}
                className={i < phase ? "done" : i === phase ? "current" : ""}
              >
                {name}
              </li>
            ))}
          </ol>
        )}
        <Progress
          label="Detection runs"
          value={preparing ? null : (p?.finished ?? 0)}
          max={total}
        />
        <div className="progress-meta">
          <span>
            {preparing
              ? "Preparing the workspace: cloning and resolving dependencies…"
              : `${p?.finished ?? 0} / ${total} runs completed`}
          </span>
          <span>
            {!!p?.crashed && `${p.crashed} crashed · `}
            {!preparing && total
              ? `${Math.round(((p?.finished ?? 0) / total) * 100)}%`
              : ""}
          </span>
        </div>
        {h.failureReason && (
          <div style={{ marginTop: 14 }}>
            <Alert>{h.failureReason}</Alert>
          </div>
        )}
        {h.status === "AWAITING_AI" && (
          <div style={{ marginTop: 14 }}>
            <Alert kind="warn">
              AI analysis paused: the findings wait until AI agents are switched
              on in <Link to="/admin">Admin</Link>.
            </Alert>
          </div>
        )}
      </Card>

      <Card title="Findings" icon="bug">
        {!done && !FINISHED.includes(h.status) && (
          <Empty title="Hunting…" icon="clock">
            Flaky tests appear here once all runs finish and the results are
            analyzed.
          </Empty>
        )}
        {done && findings.isPending && <Skeleton />}
        {findings.error && <Alert>{findings.error.message}</Alert>}
        {findings.data?.length === 0 && (
          <Empty title="No flaky tests found" icon="check">
            Every test behaved the same in all {total} runs.
          </Empty>
        )}
        {!!findings.data?.length && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Test</th>
                  <th>Score</th>
                  <th>Failed</th>
                  <th>Triggered by</th>
                  <th>Diagnosis</th>
                </tr>
              </thead>
              <tbody>
                {findings.data.map((f) => (
                  <tr key={f.id}>
                    <td>
                      <Link to={`/findings/${f.id}`}>
                        <code>{f.testId}</code>
                      </Link>
                    </td>
                    <td className="num">{Number(f.score).toFixed(2)}</td>
                    <td className="num">
                      {f.evidence.failures}/{f.evidence.runs}
                    </td>
                    <td className="muted">
                      {f.evidence.triggeringStrategies.join(", ") ||
                        "nothing extra: fails in the default setup (UTC, normal order)"}
                    </td>
                    <td>{diagnosisCell(f, experiments.data ?? [])}</td>
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

import type { ReactNode } from "react";
import type { ExperimentProgress } from "../api";

/** A spinner next to text that says what is running; role="status" lets screen readers announce changes. */
export function Busy({ children }: { children: ReactNode }) {
  return (
    <span role="status" className="busy">
      {children}
    </span>
  );
}

export const isRunning = (e: ExperimentProgress) =>
  e.status === "PREPARING" || e.status === "RUNNING";

/** "Verifying the fix: 8/100 runs, 0 failed" */
export function progressText(e: ExperimentProgress) {
  const what = e.verification ? "Verifying the fix" : "Running an experiment";
  if (e.status === "PREPARING") return `${what}: preparing the workspace`;
  const crashed = e.crashed ? `, ${e.crashed} crashed` : "";
  return `${what}: ${e.finished}/${e.planned} runs, ${e.failing} failed${crashed}`;
}

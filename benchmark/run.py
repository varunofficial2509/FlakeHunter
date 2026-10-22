"""Hunts each benchmark target through FlakeHunter's API and scores the findings against benchmark/expected.json.

  FLAKEHUNTER_ADMIN_PASSWORD=... python3 benchmark/run.py               # every target
  FLAKEHUNTER_ADMIN_PASSWORD=... python3 benchmark/run.py control --runs 40 --parallel 2
  python3 benchmark/run.py --report                                       # rewrite benchmark/results.md

Needs the k3d deployment (deploy/k3d/up.sh). With AI on it waits for the agents (up to --timeout); with AI off it
stops after detection and scores only that. Each run is saved to benchmark/results/<time>-<target>.json, and the
report uses the newest file per target. LLM tokens come from Postgres through kubectl: the API doesn't expose them.
"""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import UTC, datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
DETECTED = {"DIAGNOSING", "AWAITING_AI", "COMPLETED"}  # detection is over in these
FINISHED = {"COMPLETED", "FAILED", "CANCELLED"}
PROPOSED = {
    "FIX_VERIFIED",
    "FIX_FAILED",
    "FIX_REJECTED",
}  # sessions that wrote at least one fix


class Api:
    def __init__(self, base: str, password: str):
        self.base = base
        self.token = None
        self.token = self.call(
            "POST", "/api/auth/login", {"username": "admin", "password": password}
        )["token"]

    def call(self, method: str, path: str, body: dict | None = None):
        req = urllib.request.Request(
            self.base + path,
            method=method,
            data=json.dumps(body).encode() if body else None,
        )
        req.add_header("Content-Type", "application/json")
        if self.token:
            req.add_header("Authorization", f"Bearer {self.token}")
        try:
            with urllib.request.urlopen(req, timeout=30) as res:
                return json.load(res)
        except urllib.error.HTTPError as e:
            sys.exit(f"{method} {path}: {e.code} {e.read().decode()[:300]}")


def commit_of(target: dict) -> str:
    """The pinned SHA, or for this repo's control project the newest pushed commit that changed it."""
    if target["sha"]:
        return target["sha"]
    sha = subprocess.run(
        ["git", "log", "-1", "--format=%H", "--", "benchmark/control"],
        cwd=HERE,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    pushed = subprocess.run(
        ["git", "branch", "-r", "--contains", sha],
        check=False,
        cwd=HERE,
        capture_output=True,
        text=True,
    )
    if not pushed.stdout.strip():
        sys.exit(
            f"Commit {sha[:8]} with benchmark/control is not on GitHub yet: push it, the runner clones from there"
        )
    return sha


def project_id(api: Api, name: str, target: dict) -> str:
    for p in api.call("GET", "/api/projects"):
        if p["name"] == name:
            return p["id"]
    body = {
        "name": name,
        "repoUrl": target["repoUrl"],
        "buildTool": "MAVEN",
        "testCommand": target["testCommand"],
        "jdkVersion": target["jdkVersion"],
    }
    return api.call("POST", "/api/projects", body)["id"]


def tokens_by_finding(hunt_id: str, context: str) -> dict[str, int]:
    sql = (
        "SELECT finding_id, sum(tokens_in + tokens_out) FROM agents.llm_calls "
        f"WHERE hunt_id = '{hunt_id}' GROUP BY 1"
    )  # hunt_id is a UUID from our own API, not user input
    out = subprocess.run(
        [
            "kubectl",
            "--context",
            context,
            "-n",
            "flakehunter",
            "exec",
            "postgres-0",
            "--",
            "psql",
            "-U",
            "flakehunter",
            "-d",
            "flakehunter",
            "-At",
            "-F",
            " ",
            "-c",
            sql,
        ],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    return {f: int(t) for f, t in (line.split() for line in out.splitlines() if line)}


def hunt(api: Api, name: str, target: dict, args) -> dict:
    sha = commit_of(target)
    budget = {"maxRuns": args.runs, "maxParallelPods": args.parallel}
    h = api.call(
        "POST",
        "/api/hunts",
        {
            "projectId": project_id(api, name, target),
            "commitSha": sha,
            "budget": budget,
        },
    )
    started, detected_at = time.monotonic(), None
    print(
        f"{name}: hunt {h['id']} on {sha[:8]}, {args.runs} runs, {args.parallel} at a time",
        flush=True,
    )
    while True:
        time.sleep(10)
        h = api.call("GET", f"/api/hunts/{h['id']}")
        if detected_at is None and h["status"] in DETECTED:
            detected_at = time.monotonic()
            print(
                f"{name}: detection done in {detected_at - started:.0f} s ({h['status']})",
                flush=True,
            )
        # With AI off the findings wait for the switch; score detection only.
        if (
            h["status"] in FINISHED
            or h["status"] == "AWAITING_AI"
            or time.monotonic() - started > args.timeout * 60
        ):
            break
    findings = api.call("GET", f"/api/findings?huntId={h['id']}")
    tokens = tokens_by_finding(h["id"], args.context)
    return {
        "target": name,
        "sha": sha,
        "huntId": h["id"],
        "status": h["status"],
        "budget": budget,
        "at": datetime.now(UTC).isoformat(timespec="seconds"),
        "detectionSeconds": round(detected_at - started) if detected_at else None,
        "totalSeconds": round(time.monotonic() - started),
        "findings": [
            {
                "testId": f["testId"],
                "score": float(f["score"]),
                "failures": f["evidence"]["failures"],
                "runs": f["evidence"]["runs"],
                "triggeredBy": f["evidence"]["triggeringStrategies"],
                "outcome": (f["diagnosis"] or {}).get("status"),
                "category": (f["diagnosis"] or {}).get("category"),
                "tokens": tokens.get(f["id"], 0),
            }
            for f in findings
        ],
    }


def score(result: dict, target: dict) -> dict:
    expected, also = target["flaky"], target["alsoFlaky"]
    found = {f["testId"]: f for f in result["findings"]}
    hits = [t for t in expected if t in found]
    diagnosed = [t for t in hits if found[t]["category"]]
    right = [
        t
        for t in diagnosed
        if found[t]["category"]
        in {expected[t]["category"], *expected[t].get("also", [])}
    ]
    with_tokens = [f["tokens"] for f in result["findings"] if f["tokens"]]
    return {
        "expected": len(expected),
        "detected": len(hits),
        "falsePositives": sorted(
            t for t in found if t not in expected and t not in also
        ),
        "diagnosed": len(diagnosed),
        "categoryRight": len(right),
        "fixesProposed": sum(found[t]["outcome"] in PROPOSED for t in hits),
        "fixesVerified": sum(found[t]["outcome"] == "FIX_VERIFIED" for t in hits),
        "tokensPerFinding": round(sum(with_tokens) / len(with_tokens))
        if with_tokens
        else None,
    }


def report(expected: dict) -> str:
    latest = {}
    for path in sorted(
        RESULTS.glob("*.json")
    ):  # names start with the time, so the newest wins
        result = json.loads(path.read_text())
        latest[result["target"]] = result
    lines = [
        "# Benchmark results",
        "",
        "Generated by `benchmark/run.py --report` from the newest run per target. Ground truth: `benchmark/expected.json`.",
        "",
        "| Target | Runs | Detected | False positives | Category right | Fixes proposed | Fixes verified"
        + " | Tokens per finding | Detection time |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    details = []
    for name, result in latest.items():
        target, s = expected[name], score(result, expected[name])
        category = (
            f"{s['categoryRight']}/{s['diagnosed']} diagnosed"
            if s["diagnosed"]
            else "AI off"
        )
        detection = (
            f"{result['detectionSeconds']} s"
            if result["detectionSeconds"]
            else "unfinished"
        )
        lines.append(
            f"| {name} | {result['budget']['maxRuns']} | {s['detected']}/{s['expected']} | {len(s['falsePositives'])} "
            f"| {category} | {s['fixesProposed']} | {s['fixesVerified']} | {s['tokensPerFinding'] or '-'} "
            f"| {detection} |"
        )
        found = {f["testId"]: f for f in result["findings"]}
        about = f"{target['about']} Hunt `{result['huntId']}` at `{result['sha'][:8]}`, {result['at']}."
        details += [
            "",
            f"## {name}",
            "",
            about,
            "",
            "| Test | Expected | Failed | Triggered by | Agent's category | Outcome |",
            "| --- | --- | --- | --- | --- | --- |",
        ]
        for test, truth in target["flaky"].items():
            f = found.get(test)
            failed = f"{f['failures']}/{f['runs']}" if f else "**missed**"
            trig = ", ".join(f["triggeredBy"]) or "default setup" if f else "-"
            details.append(
                f"| `{test.split('.')[-1]}` | {truth['category']} | {failed} | {trig} "
                f"| {(f or {}).get('category') or '-'} | {(f or {}).get('outcome') or '-'} |"
            )
        for test in s["falsePositives"]:
            details.append(
                f"| `{test.split('.')[-1]}` | not flaky (false positive) | {found[test]['failures']}/"
                f"{found[test]['runs']} | - | - | - |"
            )
    return "\n".join(lines + details) + "\n"


def main() -> None:
    expected = json.loads((HERE / "expected.json").read_text())
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "targets", nargs="*", help=f"any of {', '.join(expected)}; default: all"
    )
    parser.add_argument("--runs", type=int, default=40)
    parser.add_argument("--parallel", type=int, default=2)
    parser.add_argument(
        "--timeout",
        type=int,
        default=90,
        help="minutes to wait per hunt, agents included",
    )
    parser.add_argument("--base", default="http://localhost:8090")
    parser.add_argument("--context", default="k3d-flakehunter")
    parser.add_argument(
        "--report", action="store_true", help="only rewrite benchmark/results.md"
    )
    args = parser.parse_args()
    if unknown := set(args.targets) - set(expected):
        parser.error(f"unknown targets {sorted(unknown)}")

    if not args.report:
        password = os.environ.get("FLAKEHUNTER_ADMIN_PASSWORD") or sys.exit(
            "Set FLAKEHUNTER_ADMIN_PASSWORD"
        )
        api = Api(args.base, password)
        RESULTS.mkdir(exist_ok=True)
        for name in args.targets or expected:
            result = hunt(api, name, expected[name], args)
            stamp = result["at"].replace(":", "").replace("+0000", "Z")
            (RESULTS / f"{stamp}-{name}.json").write_text(
                json.dumps(result, indent=2) + "\n"
            )
            print(json.dumps(score(result, expected[name])), flush=True)
    (HERE / "results.md").write_text(report(expected))
    print(f"Wrote {HERE / 'results.md'}")


if __name__ == "__main__":
    main()

"""The first prototype: rerun a repo's tests N times in Docker with shuffled class order, report flaky tests.

Usage: see benchmark/targets.md for a working command.

Kept to reproduce benchmark/targets.md; the platform's runner images replaced it.
"""

import argparse
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORK = ROOT / "benchmark" / ".work"
# Surefire 3.x supports a fixed seed for random order; old target poms pin older versions.
SUREFIRE = "org.apache.maven.plugins:maven-surefire-plugin:3.5.2:test"


def default_image():
    for line in (ROOT / "deploy" / "images.env").read_text().splitlines():
        if line.startswith("MAVEN_JDK21_IMAGE="):
            return line.split("=", 1)[1].strip()
    sys.exit("MAVEN_JDK21_IMAGE missing from deploy/images.env")


def checkout(url, sha):
    repo = WORK / url.rstrip("/").split("/")[-1]
    if not repo.exists():
        subprocess.run(["git", "clone", "--quiet", url, str(repo)], check=True)
    subprocess.run(
        ["git", "-C", str(repo), "checkout", "--quiet", "--force", sha], check=True
    )
    return repo


def mvn(repo, image, args):
    # Named volume caches ~/.m2 across runs so dependencies download once.
    cmd = [
        "docker",
        "run",
        "--rm",
        "-v",
        f"{repo}:/src",
        "-w",
        "/src",
        "-v",
        "flakehunter-m2:/root/.m2",
        image,
        "mvn",
        "-B",
        "-q",
        *args,
    ]
    with open(WORK / "mvn.log", "a") as log:
        return subprocess.run(
            cmd, check=False, stdout=log, stderr=subprocess.STDOUT
        ).returncode


def parse_reports(reports_dir):
    """Return {test_id: outcome} from Surefire JUnit XML."""
    results = {}
    for f in reports_dir.glob("TEST-*.xml"):
        for case in ET.parse(f).getroot().iter("testcase"):
            test_id = f"{case.get('classname')}#{case.get('name')}"
            if case.find("skipped") is not None:
                continue
            failed = case.find("failure") is not None or case.find("error") is not None
            results[test_id] = "FAILED" if failed else "PASSED"
    return results


def main():
    p = argparse.ArgumentParser()
    p.add_argument("url")
    p.add_argument("sha")
    p.add_argument("--runs", type=int, default=10)
    p.add_argument("--image", default=default_image())
    p.add_argument("--module", default=".", help="module path inside the repo")
    p.add_argument(
        "--tests",
        help="Surefire -Dtest pattern to run a subset, e.g. '**/serializer/date/*Test*'",
    )
    p.add_argument(
        "--define",
        action="append",
        default=[],
        help="extra -D for every mvn call, e.g. jdk.version=1.8",
    )
    a = p.parse_args()

    repo = checkout(a.url, a.sha)
    module_args = ([] if a.module == "." else ["-pl", a.module, "-am"]) + [
        f"-D{d}" for d in a.define
    ]
    print(f"Compiling {repo.name}@{a.sha[:8]} in {a.image} ...")
    if mvn(repo, a.image, [*module_args, "test-compile", "-DskipTests"]) != 0:
        sys.exit(
            f"test-compile failed, see {WORK / 'mvn.log'}; try another --image (e.g. an older JDK)"
        )

    reports = repo / a.module / "target" / "surefire-reports"
    outcomes = defaultdict(lambda: defaultdict(list))  # test -> outcome -> [seeds]
    for seed in range(1, a.runs + 1):
        shutil.rmtree(reports, ignore_errors=True)
        mvn(
            repo,
            a.image,
            [
                *module_args,
                SUREFIRE,
                "-Dsurefire.runOrder=random",
                f"-Dsurefire.runOrder.random.seed={seed}",
                "-Dsurefire.failIfNoSpecifiedTests=false",
                "-DfailIfNoTests=false",
                *([f"-Dtest={a.tests}"] if a.tests else []),
            ],
        )
        results = parse_reports(reports)
        failed = sum(o == "FAILED" for o in results.values())
        print(f"run {seed}/{a.runs}: {len(results)} tests, {failed} failed")
        for test_id, outcome in results.items():
            outcomes[test_id][outcome].append(seed)

    flaky = {t: o for t, o in outcomes.items() if "PASSED" in o and "FAILED" in o}
    print(f"\n{len(flaky)} flaky test(s) out of {len(outcomes)}:")
    for test_id, o in sorted(flaky.items()):
        print(
            f"  {test_id}  passed {len(o['PASSED'])}x, failed {len(o['FAILED'])}x "
            f"(failing seeds: {o['FAILED']})"
        )


if __name__ == "__main__":
    main()

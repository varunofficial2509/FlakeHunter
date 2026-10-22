"""Turns the fix writer's edits into a unified diff, and rejects fixes that only hide a flaky failure.

The fix writer answers with search/replace edits, not a diff: models get hunk headers and context lines wrong, and
`git apply` refuses those. Each search must appear exactly once in a file we exported, so every edit is grounded
in real code; difflib then writes a diff that applies.
"""

import difflib
import re
from typing import Any

# Tech doc section 8, banned fixes: they make the test pass without removing the cause.
BANNED = {
    r"@(Disabled|Ignore)\b": "disables the test",
    r"@(RepeatedTest|RetryingTest|Rerun\w*|Flaky)\b|RetryRule|rerunFailingTestsCount": "retries the test",
    r"\b(Thread\.sleep|TimeUnit\.\w+\.sleep|SECONDS\.sleep|MILLISECONDS\.sleep)\b": "adds a sleep",
}
ASSERTION = re.compile(r"\bassert\w*|\bverify\(|\bfail\(")


def apply_edits(sources: dict[str, str], edits: list[Any]) -> str:
    """edits have path, search and replace. Returns a unified diff for `git apply -p1`; raises ValueError."""
    changed: dict[str, str] = {}
    for edit in edits:
        if edit.path not in sources:
            raise ValueError(f"{edit.path} is not one of the files you were given: {sorted(sources)}")
        content = changed.get(edit.path, sources[edit.path])
        found = content.count(edit.search) if edit.search else 0
        if found != 1:
            raise ValueError(
                f"search text must appear exactly once in {edit.path}, found {found} times: {edit.search!r}"
            )
        changed[edit.path] = content.replace(edit.search, edit.replace)
    diff = "".join(_diff(path, sources[path], new) for path, new in changed.items() if new != sources[path])
    if not diff:
        raise ValueError("the edits change nothing")
    return diff


def _diff(path: str, old: str, new: str) -> str:
    lines = difflib.unified_diff(old.splitlines(keepends=True), new.splitlines(keepends=True), f"a/{path}", f"b/{path}")
    # A last line without a newline needs git's marker, or the patch is corrupt.
    return "".join(line if line.endswith("\n") else line + "\n\\ No newline at end of file\n" for line in lines)


def banned(diff: str) -> str | None:
    """Why the diff is a banned fix, or None. Checked before the critic, so no model call is spent on these."""
    added = [line[1:] for line in diff.splitlines() if line.startswith("+") and not line.startswith("+++")]
    removed = [line[1:] for line in diff.splitlines() if line.startswith("-") and not line.startswith("---")]
    for pattern, why in BANNED.items():
        if any(re.search(pattern, line) for line in added):
            return f"The fix {why}"
    if sum(bool(ASSERTION.search(line)) for line in removed) > sum(bool(ASSERTION.search(line)) for line in added):
        return "The fix deletes assertions"
    return None

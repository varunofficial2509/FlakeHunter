import subprocess

import pytest

from app.graph import Edit
from app.patch import apply_edits, banned

SOURCES = {"src/A.java": "class A {\n    int x = 1;\n    int y = 1;\n}", "src/B.java": "class B {}\n"}


def git_apply_check(tmp_path, diff: str) -> None:
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    for path, content in SOURCES.items():
        (tmp_path / path).parent.mkdir(parents=True, exist_ok=True)
        (tmp_path / path).write_text(content)
    subprocess.run(["git", "apply", "--check", "-p1", "-"], cwd=tmp_path, input=diff, text=True, check=True)


def test_edits_become_a_diff_git_applies(tmp_path):
    # The last line of A has no newline: the diff must say so, or git calls it corrupt.
    diff = apply_edits(SOURCES, [Edit(path="src/A.java", search="}", replace="    int z;\n}")])
    assert diff.startswith("--- a/src/A.java\n+++ b/src/A.java\n")
    git_apply_check(tmp_path, diff)


@pytest.mark.parametrize(
    ("edit", "error"),
    [
        (Edit(path="src/C.java", search="x", replace="y"), "not one of the files"),
        (Edit(path="src/A.java", search="= 1;", replace="= 2;"), "found 2 times"),
        (Edit(path="src/A.java", search="int x = 1;", replace="int x = 1;"), "change nothing"),
    ],
)
def test_edits_must_be_grounded_in_the_files(edit, error):
    with pytest.raises(ValueError, match=error):
        apply_edits(SOURCES, [edit])


def test_fixes_that_hide_the_failure_are_banned():
    def diff(old, new):
        return f"--- a/T.java\n+++ b/T.java\n@@ -1 +1 @@\n-{old}\n+{new}\n"

    assert banned(diff("void t() {", "@Disabled void t() {")) == "The fix disables the test"
    assert banned(diff("await(latch);", "Thread.sleep(500);")) == "The fix adds a sleep"
    assert banned(diff("assertEquals(1, x);", "")) == "The fix deletes assertions"
    assert banned(diff("assertEquals(1, x);", "assertEquals(1, x.get());")) is None

"""Checks run.py's scoring on a made-up result: python3 benchmark/test_run.py"""

from run import score

TARGET = {
    "flaky": {
        "a.A#t": {"category": "TIME_DEPENDENT"},
        "a.B#t": {"category": "SHARED_STATE", "also": ["ORDER_DEPENDENCY"]},
        "a.C#t": {"category": "RANDOMNESS"},
    },
    "alsoFlaky": {"a.D#t": "known extra"},
}


def finding(test, category=None, outcome=None, tokens=0):
    return {"testId": test, "category": category, "outcome": outcome, "tokens": tokens}


result = {
    "findings": [
        finding("a.A#t", "TIME_DEPENDENT", "FIX_VERIFIED", 1000),
        finding(
            "a.B#t", "ORDER_DEPENDENCY", "FIX_FAILED", 3000
        ),  # an accepted alternative
        finding("a.D#t"),  # known extra: neither a hit nor a false positive
        finding(
            "a.E#t", "CONCURRENCY", "INVESTIGATED", 2000
        ),  # not flaky by the truth file
    ]
}
s = score(result, TARGET)
assert (s["expected"], s["detected"]) == (3, 2), s
assert s["falsePositives"] == ["a.E#t"], s
assert (s["diagnosed"], s["categoryRight"]) == (2, 2), s
assert (s["fixesProposed"], s["fixesVerified"]) == (2, 1), s
assert s["tokensPerFinding"] == 2000, s
assert score({"findings": []}, TARGET)["tokensPerFinding"] is None
print("ok")

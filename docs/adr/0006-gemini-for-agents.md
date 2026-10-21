# ADR 0006 — Agents use Gemini, with a fake LLM when no key is set

- Status: Accepted
- Date: 2026-10-21
- Settles: tech doc section 14 open decision "LLM providers and default models per agent tier"

## Context

The tech doc lists hosted Gemini or OpenAI models (section 2), routed by task: a small model for triage and classification, a large one for investigation, fixes and critique (section 8). This is a solo project with a per-day spending cap, so the price of the first thousand calls matters more than small quality differences.

## Decision

- Gemini through `langchain-google-genai`. Small tier `gemini-2.5-flash`, large tier `gemini-2.5-pro`, both overridable with `FLAKEHUNTER_SMALL_MODEL` and `FLAKEHUNTER_LARGE_MODEL`.
- The key comes from `GEMINI_API_KEY` (in the cluster: the optional `gemini-api-key` in `flakehunter-secrets`, set by `GEMINI_API_KEY=... deploy/k3d/up.sh`).
- Without a key, agent-service uses a fake LLM that returns a canned `UNKNOWN` plan. The whole pipeline still runs, so the cluster and the tests need no key and cost nothing.
- Agents ask for JSON (`response_mime_type`) and validate it with Pydantic themselves, instead of using provider-specific tool calling, so the fake and real models go through the same code path.
- `langchain-openai` is removed.

## Consequences

- AI Studio's free tier covers development; its rate limits, not tokens, are the first limit to be hit.
- Switching provider is one `make_llm` branch plus a dependency.
- Every LLM call is recorded in `agents.llm_calls` and checked against per-finding, per-hunt and per-day token caps before it is made.

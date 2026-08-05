# datom — Roadmap (post-MVP)

**Basis:** Research round 2026-08-05 — Hindsight (Vectorize.io) deep dive, 9-framework memory bake-off, community sentiment. Sources: `doc/references/memory-systems-comparison.md`, `doc/references/agent-memory-comparison.md` (copied into this repo). Deferred review items: `AGENTS.md` → Post-MVP: Deferred Items.

**Verdict the roadmap implements:** *build on datom, steal from Hindsight.* Hindsight validated datom's hybrid+graph direction (91.4% LongMemEval) and contributes a feature order: consolidation → temporal → entity resolution → opinion. The community's #1 warning (hidden prompt debt from uninspectable, uncorrectable, nonexpiring agent-written memories) dictates the #1 phase: lifecycle.

**The 4-move plan** (referenced throughout; defined once here):
1. Stub MEMORY.md → bootstrap pointer ("persistent memory lives in datom")
2. Live `system_prompt_block()` (top-N core memories injected per turn)
3. `bootstrap_recall` hook (top memories injected at session start)
4. Nightly consolidation cron (expire → dedup → consolidate → compact)

**How to use this document:** Milestones are ordered by value/risk, not novelty. Each is independently actionable in a future session — do them in order, one or more snapshot commits per milestone, `cljfmt fix` + `clj-kondo --lint` before every commit, tests green always. Check off items as they land. When a milestone is done, update this file's checkboxes **and the README.md / AGENTS.md tool tables** (new `update` tool, `:content/ttl`, timestamps) in the same commit. Work continues on `feat/mvp`.

---

## Current Status

### Done (MVP — phases A–H complete)
- [x] Hybrid retrieval: fulltext (TF-IDF) + vector (HNSW) + RRF fusion (k=60) in one embedded LMDB store
- [x] Paragraph chunking (`datom.chunk`, max-chars 2000, overlap 200)
- [x] Graph: markdown link extraction, `:content/depends` canonical edge, 1-hop neighbors/dependents
- [x] ContentSource protocol + LUDS markdown adapter (reference impl)
- [x] Write tools: `remember`, `forget` (removes from datalog + both indices)
- [x] MCP server on 9090 — 9 tools, Streamable HTTP, protocol 2025-11-25
- [x] JSON HTTP API on 9091 (search, answer, remember, forget, stats, lookup)
- [x] Hermes plugin (`plugins/memory/datom/`) wired as `memory.provider=datom`; 5 tools + `prefetch` + `sync_turn`
- [x] Deployed on NixOS lattice as systemd service; mcp-injector exposes as MCP server
- [x] Tests green: 16 Clojure / 91 assertions, 38 Python
- [x] Config via env vars (`datom.config/get-env`), incremental `index-docs!`, graceful shutdown hooks

### Not yet done (this roadmap)
- [ ] `system_prompt_block()` is a hardcoded stub — does not live-query the store
- [ ] No lifecycle layer: TTL / dedup / contradiction on `sync_turn` auto-memories
- [ ] No observation consolidation, no temporal layer, no entity resolution, no opinion network
- [ ] MEMORY.md still dual-tracked with datom (not stubbed to a bootstrap pointer)
- [ ] No bootstrap_recall at session start, no nightly consolidation cron

---

## Phase 1 — Quick wins & ops hardening

*Why first: zero design risk, all deferred review items, everything later depends on a healthy, observable, safe store.*

### M1.1 — API body size limit — S — no deps
- **What:** explicit http-kit `:max-body` on the 9091 API (per AGENTS.md deferred item).
- **Why:** no *explicit* limit today; relies on http-kit's built-in decoder cap (~8MB default). Set an explicit, documented cap so the contract is deliberate and testable.
- **Acceptance:** POST with payload > 10 MB returns 413; a test asserts the limit; cljfmt + clj-kondo pass.

### M1.2 — LMDB lock recovery — S — no deps
- **What:** systemd `ExecStartPre` (or service wrapper) removes stale `lock.mdb`/lock files before start; document manual `rm -rf` fallback.
- **Why:** crash → stale lock → infinite restart loop on lattice. Ops-critical today.
- **Acceptance:** `kill -9` the datom service, restart succeeds without manual intervention; the cleanup step is idempotent (safe when no stale lock exists).
- **Note:** the service runs `ProtectSystem=strict` + `StateDirectory`, so stale-lock cleanup must run in `ExecStartPre` (root) against `cfg.dataDir`, not inside the service. Add an idempotency unit test for the cleanup step.

### M1.3 — Chunk children stop inheriting `:content/depends` — S — no deps
- **What:** fix `extract-links` in `datom.graph` so chunk children don't inherit the parent doc's `:content/depends` (over-counted graph edges).
- **Why:** correctness review item; the graph currently over-reports connectivity, which pollutes `graph-expand`/`context` and any later entity resolution.
- **Acceptance:** test: parent with 2 links chunked into 3 children → neighbor counts don't multiply per chunk; counts stable across re-ingest.

### M1.4 — MCP idle-session expiry + session-aware semantics — S — no deps
- **What:** the pinned mcp-toolkit transport already validates session IDs on every request (unknown/terminated → JSON-RPC -32600) and sweeps *terminated* sessions (1h TTL). What's missing: expiry for *idle* sessions, and any datom-level use of the session id.
- **Why:** protocol/safety review item — validate the current behavior with a live test first, then add idle-session expiry and (optionally) per-session isolation.
- **Acceptance:** live test confirms unknown/terminated → -32600 (already true); idle sessions expire after TTL; session-aware semantics (e.g. per-session isolation or per-session usage stats) implemented or explicitly deferred.

### M1.5 — Structured logging — M — no deps
- **What:** mulog (or similar) events for startup, ingest, search, lifecycle ops; replace `println` in the server path.
- **Why:** observability on lattice; required before the nightly cron (M4.2) so automated jobs are auditable.
- **Acceptance:** structured events visible in journald with namespaced keys; no `println` in server code; a log line per lifecycle op.

### M1.6 — ingest-luds path traversal guard — M — needs one design decision
- **What:** validate the ingest-luds path against an allowed base directory before walking it.
- **Why:** security review item; currently accepts any server-readable path.
- **Acceptance:** decide the allowed base (config option, default `DATOM_INGEST_BASE` or the configured data dir), then: path outside base → error; symlink escape blocked; test covers traversal attempts.
- **Depends on:** a 5-minute design decision (what is the allowed base?), not on code.

### M1.7 — `list-sources` tool — S — no deps
- **What:** expose the ContentSource registry as an MCP tool (`list-sources`) + JSON route — enumerate installed sources, their ids, types, and last-ingest status.
- **Why:** deferred review item (HANDOFF Phase C parked C4); with new adapters declared non-goals, there's currently no way to enumerate what's loaded.
- **Acceptance:** `list-sources` returns all registered sources with id/type/status; test covers the empty and non-empty cases.

### M1.8 — LMDB backup/export — M — no deps
- **What:** stop-and-copy backup (safe LMDB snapshot) and/or an export CLI (`datom export` → JSON/EDN dump of the store).
- **Why:** this is the agent's memory store; Phase 1 is "ops hardening" yet nothing backs it up or exports it.
- **Acceptance:** `datom export` produces a complete JSON dump (ids, bodies, metadata, graph edges); backup/restore roundtrip test with a fresh LMDB dir; documented in README.

### M1.9 — Recall eval harness — M — no deps
- **What:** a small recall test set (or LongMemEval-style subset) that measures datom's own retrieval quality before/after each feature milestone.
- **Why:** the roadmap's rationale rests on Hindsight's 91.4% LongMemEval, but nothing measures datom's own recall. "Bounded growth over a week" (M4.2) is size, not quality. Baseline must be captured before M2.x.
- **Acceptance:** a `test/recall/` corpus with N queries + expected answers; `clojure -M:test` runs it and reports a score; baseline score recorded in the repo before Phase 2.

---

## Phase 2 — Lifecycle layer

*Why first among features: the community's #1 advanced-user warning — "if a memory cannot be inspected, corrected, expired, or tied back to why it was saved, it will eventually become hidden prompt debt." `sync_turn` writes raw turns with no lifecycle; that store becomes debt the moment it fills. Build the write-path guardrails before the store grows.*

### M2.1 — `update` primitive + MCP tool — S — no deps
- **What:** transact updated attrs + re-index (deferred item C3); expose as MCP `update` tool and JSON route.
- **Why:** prerequisite for contradiction handling and the ecosystem's "auto-capture with human override" pattern — agents must be able to *correct* memories, not only add/delete.
- **Acceptance:** update changes body/attrs; search reflects the new text; MCP roundtrip test.

### M2.2 — TTL & expiry on auto-memories — S — no deps
- **What:** `:content/ttl` (or a type-based default) on `remember`; expiry pass removes/quarantines expired docs from store + indices; `sync_turn` auto-memories get a conservative default TTL (e.g. 30 days).
- **Why:** "expired" is one third of the debt warning; the forgetting problem is the #1 community frustration — explicit expiry beats implicit decay.
- **Acceptance:** store a memory with ttl, run expiry → `lookup` nil, search excludes it, `stats` reports expired count. **Plus TTL backfill:** existing `type=conversation` docs (no `:content/ttl` today) get a default TTL on the first expiry pass — otherwise "stop the bleeding" only stops future bleeding; the accrued pile stays eternal.

### M2.3 — Dedup of auto-memories — M — depends on M2.1
- **What:** near-duplicate detection (normalized-text hash first, vector-similarity threshold second) before insert; keep newest, mark old superseded via `:content/supersedes`.
- **Why:** `sync_turn` re-stores the same facts every session → retrieval noise + index bloat; this is consolidation-lite.
- **Acceptance:** two turns stating the same fact → one live doc + supersede link; duplicates reported in `stats`.

### M2.4 — Contradiction handling — M — depends on M2.1, M2.3
- **What:** when a new auto-memory contradicts a live one (same entity + attribute, opposite value), supersede the old instead of leaving both retrievable.
- **Why:** "agent-written memories without review forever alter behavior in ways you don't want"; contradictory memories poison retrieval and confuse the agent about itself.
- **Acceptance:** contradictory pair → retrieval returns only the newer; `lookup` on the old shows `:content/supersedes`; test covers the write path.

### M2.5 — Concurrent access tests — M — parallel to M2.2–M2.4
- **What:** tests exercising concurrent ingest/search/forget/expiry from multiple threads.
- **Why:** deferred review item; lifecycle adds background writers (expiry, dedup) so races become real.
- **Acceptance:** N parallel writers + readers complete without exceptions; randomized per-test LMDB dirs (existing convention) still prevent collisions.

---

## Phase 3 — Live memory injection

*Why next: the comparison's verdict on datom's gap is blunt — "the theoretical advantage is unrealized until system_prompt_block actually queries the store." This is the 4-move plan's moves 2 and 3. It makes datom's injected context real before we stub MEMORY.md (M4.1).*

### M3.1 — Live `system_prompt_block()` — M — **3 hidden deps (audited)**
- **What:** replace the hardcoded stub with a live query: top-N core memories for the session, reusing the `prefetch` distillation format; budget ~500–1000 tokens (community consensus: core memory in context + retrieval for the rest); degrade gracefully to `""` when the server is down. **Budget note:** `_format_context` (prefetch) is documented at 200–500 tokens — the core-memory block and retrieval snippets are distinct budgets; reconcile explicitly in code.
- **Why:** the single most-cited unrealized advantage in the comparison; unlocks the two-tier architecture datom was built for.
- **Hidden deps (verified against source, 2026-08-05):**
  - (a) **Server capability missing:** `POST /api/search` requires `:query` — no query-less top-N path exists today. Need a new route (e.g. `/api/top` or empty-query semantics) — `index/rrf-search` on `""` is undefined (garbage ranking).
  - (b) **"High-importance" selection is unimplementable:** `core.clj remember` defaults `:content/importance` to 0; `sync_turn` sends no importance → every auto-memory is importance 0. Needs a ranking decision: recency fallback and/or exclude `type=conversation` from injection (M2.2's per-type TTL gives this taxonomy).
  - (c) **Unverified Hermes contract:** `system_prompt_block()` takes no args; "for the session" implies Hermes core must pass query/session — verify the call signature (10-min check) before starting; if core can't pass a query, design must be query-less top-N.
- **Acceptance:** block content varies with store contents; output is token-budget-capped; mocked-httpx tests; server-down → `""` and no exception.

### M3.2 — `bootstrap_recall` hook — M — depends on M3.1
- **What:** session-start hook that prefetches top memories and injects distilled facts (OpenClaw's MEMORY.md-at-start pattern, moved 3 of the 4-move plan).
- **Why:** the ecosystem pattern every serious system ships (OpenClaw, Claude Code, gptme all inject core context at session start).
- **Acceptance:** a fresh session includes distilled top facts; idempotent; clear division of labor with `system_prompt_block` (no duplicate injection).
- **Depends on:** M3.1 (shares formatting/distillation code).

### M3.3 — Plugin ABC inheritance — S — no deps
- **What:** `DatomMemoryProvider` inherits Hermes' `MemoryProvider` ABC instead of duck-typing.
- **Why:** deferred review item; makes the plugin contract self-enforcing.
- **Acceptance:** `isinstance` check passes; 38 pytest cases still green.

---

## Phase 4 — Bootstrap migration & nightly consolidation cron

*Why next: finishes the 4-move plan (moves 1 and 4). Only stub MEMORY.md after live injection exists so context isn't lost. The cron is a skeleton here; Phase 5 fills in the consolidation logic it runs.*

### M4.1 — MEMORY.md stub → bootstrap pointer — S — depends on M3.1, M3.2
- **What:** replace MEMORY.md's content with a short pointer ("persistent memory lives in datom — use datom_search / datom_context / datom_stats").
- **Why:** the comparison flags file+datom dual-track as redundant and confusing; MEMORY.md frozen-at-start stales against the live store.
- **Acceptance:** stub pointer text present at session start; live top-N injection present; no duplicate injection (stub + live block distinct); N-query recall smoke test (ask 3 known facts, verify they're retrieved) passes.

### M4.2 — Nightly consolidation cron — M — depends on M1.5, M2.2, M2.3, 5.1
- **What:** systemd timer on lattice runs a datom CLI job nightly: expire (M2.2), dedup (M2.3), then run the consolidation engine (M5.1) and the first real `compact` implementation (merge small chunks, purge superseded).
- **Why:** move 4 of the 4-move plan; the OpenClaw "dreaming" pattern; keeps the passive store bounded instead of growing into debt.
- **Acceptance:** timer fires nightly; job emits structured log events; a synthetic fast-forward test (inject 7 days of turns, run the pass) shows bounded doc growth; re-running is idempotent. Real wall-clock bounded growth is a dated follow-up, not a snapshot criterion.
- **Depends on:** M1.5, M2.2, M2.3, M5.1 (cron can be scaffolded before 5.1 lands, but ships the full pass only after).

### M4.3 — Verify `on_pre_compress` end-to-end — M — blocked externally
- **What:** `on_pre_compress` is **already implemented** in the plugin (searches last user messages, remembers `[Pre-compression context]`, returns `""` per the ByteRover pattern; HANDOFF.md Phase E documents it). The real gap is Hermes-core wiring — the hook may never fire.
- **Why:** preserves context across Hermes compression events; the implementation is done, the wiring is unverified.
- **Acceptance:** with Hermes core fix #7192 merged, a compression event demonstrably saves searchable context; mocked-client tests pass.
- **Depends on:** Hermes core fix #7192 (external; do not start until confirmed merged).

---

## Phase 5 — Observation consolidation

*Why here: the highest-value Hindsight steal, and the real answer to hidden prompt debt — turning raw turns into durable, evidence-backed knowledge. Runs in the nightly cron (M4.2).*

### M5.1 — Consolidation engine — L — depends on M2.3, M4.2
- **What:** Hindsight-style observation consolidation: group related auto-memories → distilled observation doc carrying `:content/evidence` refs; staleness scoring so outdated observations lose retrieval priority.
- **Why:** Hindsight's #1 stealable feature; directly answers the debt warning; converts the raw-turn store into a knowledge store.
- **Acceptance:** N related memories → 1 observation with evidence list; retrieval of the observation surfaces its evidence; pure functions with tests; no LLM in the loop.
- **Depends on:** M2.3 (dedup foundations), M4.2 (cron to run it).

### M5.2 — Memory tiers / compaction — M — depends on M5.1
- **What:** replace the placeholder `compact` fn with tiered handling: hot (recent/high-importance, fully indexed), warm (consolidated observations), cold (archived/superseded); importance gates promotion. Fold in **importance decay** — `:content/importance` decays over time (half-life configurable, e.g. 30 days) so old low-value memories lose retrieval priority without deletion.
- **Why:** deferred feature gap; the context-budget-awareness pattern; a light analog of Hindsight's Mental Models → Observations → Raw Facts hierarchy.
- **Acceptance:** tier assignment rules exist as pure fns; `stats` exposes per-tier counts; search/lookup respect tiers (cold not returned by default); importance decay: a doc's score drops over simulated time and is excluded once below threshold (pure-fn test).
- **Depends on:** M5.1.

---

## Phase 6 — Temporal layer

*Why here: Hindsight's "cheap, high leverage" steal — dual temporal tracking (when the event happened vs when the agent learned it). An agent that changes over time needs both.*

### M6.1 — Dual temporal tracking — M — no deps (additive schema)
- **What:** `:content/event-time` (when the event happened, from source metadata when known) + `:content/learned-time` (when datom stored it — default to now); capture on ingest/remember; search/context gain optional filters on either.
- **Why:** "when did I learn X" and "when did X happen" are different questions; Hindsight validated both matter for recall quality.
- **Acceptance:** both timestamps persisted for new docs; query filters by each independently; migration for existing docs defaults learned-time = created-at; tests.

---

## Phase 7 — Entity resolution

*Why here: makes the graph (datom's unique edge) dramatically more useful by resolving names across docs.*

### M7.1 — Fuzzy name matching + entity resolution — M — depends on M5.1
- **What:** normalize names (case/whitespace/aliases) across docs into an alias table; graph edges resolve through entities; duplicate entities merged.
- **Why:** Hindsight steal #3; "structured not fuzzy" is the emerging consensus and datom's thesis — entity resolution is what makes the structure trustworthy.
- **Acceptance:** docs mentioning "Vec.io" and "Vectorize" resolve to one entity; `graph-expand` returns the merged neighborhood; merge is reversible/auditable (old ids retained as aliases); tests.
- **Depends on:** M2.3 (dedup's normalized-text hash provides candidate pairs) + graph module. M5.1's evidence grouping is a *secondary* signal, not a prerequisite — Hindsight resolves entities at retain time.

---

## Phase 8 — Opinion network

*Why last: Hindsight's most complex layer, least urgent, and the most speculative for a no-LLM store. Only start after 1–7 are proven in daily use.*

### M8.1 — Confidence-scored opinions — L — depends on M2.4, M6.1, M7.1
- **What:** opinion-typed docs (subjective beliefs, e.g. "Wes prefers X") with `:content/confidence`; confidence bumps on corroboration, drops on contradiction; retrieval can filter by minimum confidence; rule-based (no LLM).
- **Why:** Hindsight's Opinion network is the differentiator at the top of its stack; for datom it's the capstone, not the foundation.
- **Acceptance:** remember with `:content/type` opinion + confidence; corroborating memory raises score, contradicting lowers it (reusing M2.4); retrieval filter test.
- **Depends on:** M2.4 (contradiction machinery), M6.1 (temporal), M7.1 (entities as subjects).

---

## Non-goals

Explicitly NOT doing (unless a future session overturns the research):

- **Switching to Hindsight / PostgreSQL + pgvector.** Deployment mismatch (PG cluster vs embedded LMDB), LLM-per-op cost/latency/external dependency, and we're already wired end-to-end.
- **Vector-only redesign.** Hybrid fulltext + vector + Datalog is the thesis; the embedding-truncation confound hurting vector-only systems (SECI paper) doesn't apply here.
- **Multi-process LMDB.** LMDB is single-process by design; MCP + JSON API share one JVM (already the architecture). No sharding, no multi-writer.
- **LLM in the memory write/read path.** No mem0/Zep-style per-op LLM extraction — datom stays zero marginal cost and offline-capable. Consolidation is rule-based.
- **New ContentSource adapters this cycle** (transcript VTT/SRT, logseq interop). Parked until M5.1 lands; the logseq research doc (`doc/logseq-interop-research.md`) is shelved, not deleted.
- **Embedding model upgrade to BGE-M3** (1024 dims / 8K tokens). Full re-index cost; revisit only if recall quality demands it.
- **UI / dashboard.** MCP + JSON API is the interface; observability is via structured logs, not a web UI.

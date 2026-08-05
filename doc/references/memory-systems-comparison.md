# Memory Systems Comparison — Round 1 (Hindsight, Frameworks, Community)

**Date:** 2026-08-05
**Scope:** One comparison round across (1) Hindsight (Vectorize.io), (2) built-in memory of 9 agent frameworks, (3) community sentiment (Reddit/HN). Decision requested: *is Hindsight good on its own, or better to build on datom — and why?*

---

## Part 1 — Hindsight: The "ambitious datom"

**What it is:** Open-source agent memory layer by Vectorize.io (MIT, ~19K⭐). Not a framework — a dedicated memory server. Paper: *"Hindsight is 20/20: Building Agent Memory that Retains, Recalls, and Reflects"* (arXiv:2512.12818).

**Architecture:** PostgreSQL 14+ with pgvector. Python server + SDKs. Requires an external LLM for fact extraction, entity resolution, and reasoning. MCP-first.

**Core loop — Retain → Recall → Reflect:**
- **Retain**: LLM decomposes conversations into structured facts (world vs experience), entity resolution (fuzzy name matching), knowledge graph (entity/temporal/semantic/causal edges), dual temporal tracking (when event happened *and* when agent learned it), async observation consolidation with evidence tracking.
- **Recall**: TEMPR — 4 parallel strategies (semantic vector + BM25 keyword + graph traversal + temporal), cross-encoder reranker, token-budget-aware results.
- **Reflect**: agentic loop (up to 10 iterations), hierarchical retrieval (Mental Models → Observations → Raw Facts), disposition traits (skepticism/literalism/empathy), evolving opinion network with confidence scores.

**Four-network model:** World (objective facts) / Experience (first-person actions) / Observation (auto-consolidated summaries) / Opinion (subjective beliefs w/ confidence).

**Benchmarks:** 91.4% LongMemEval (vs 49% mem0, 39% full-context baseline); 89.61% LoCoMo.

**Weaknesses:** every op calls an LLM (cost + latency + external dependency); needs PostgreSQL (not embedded); newer/smaller ecosystem; reflect() adds LLM round-trip.

## Part 2 — Framework Bake-Off (9 systems)

| Framework | Memory Mechanism | In-Context | Retrieved |
|---|---|---|---|
| **OpenClaw** | MEMORY.md + USER.md + daily notes + dreaming sweep | MEMORY.md/USER.md frozen at start | memory/*.md keyword-indexed |
| **Claude Code** | CLAUDE.md hierarchy + auto memory (25KB cap) | all CLAUDE.md + first 200 lines auto | none |
| **Codex** | AGENTS.md + cloud Memories + Chronicle | AGENTS.md + account memories | cloud-retrieved |
| **Cline/Roo** | 6-file Memory Bank (manual) | all 6 files | none |
| **Aider** | repo-map (AST) + notes | repo-map per edit | none |
| **gptme** | git-repo brain (journal/tasks/knowledge) | journal + tasks + git status | knowledge/ on-demand |
| **Goose** | none | conversation only | MCP tools only |
| **Hermes+datom** | MEMORY.md/USER.md + datom (Datalog+vector+fulltext hybrid) | MEMORY.md frozen + (planned) live top-N | MCP tools: search/context/graph-expand/answer |

**4 shared patterns:** (1) two-tier architecture (curated core in context + retrieved rest); (2) auto-capture with human override; (3) structured organization over flat dumps; (4) context budget awareness.

**datom's unique edge:** only system with true hybrid retrieval (vector + fulltext + Datalog relational) in one store; only one with graph traversal (`graph-expand`); MCP-native (any agent can use it); LMDB embedded; importance + decay architecture possible.

**datom's gap:** `system_prompt_block()` is a hardcoded stub — the theoretical advantage is unrealized until it live-queries the store. Also: no consolidation, no temporal decay, no importance implemented yet.

## Part 3 — Community Sentiment (Reddit/HN)

**Theme 1 — The forgetting problem is #1 frustration.** "I am sick and tired of the forgetting problem." — r/openclaw.

**Theme 3 — Structured > vector (emerging consensus).** "Most agent memory is structured not fuzzy... Prefix-semantic naming replaces vector similarity entirely for these workloads. No embedding model. No GPU. No cloud call." — HN (this *is* the datom thesis).

**Theme 5 — Vendor skepticism.** mem0/Zep/Letta route every read/write through an LLM: "200-500ms latency per operation, token costs on your memory layer, a runtime dependency you don't control." — HN.

**Theme 7 — Nobody trusts benchmarks.** Mem0's own results show a *full-context baseline* beating them. SECI paper: embedding truncation at 256 tokens loses ~90% of session content — a confound that hurts vector-only systems specifically. Datom's hybrid fulltext+Datalog doesn't suffer this.

**Theme 6 — The consensus pattern:** core memory in context (~500-1000 tokens) + retrieval for the rest. "Instead of spending efforts on conversation RAG, maintain a global user profile of 500-1000 tokens." — Memobase/HN.

**Theme 4 — Lifecycle warning:** "If a memory cannot be inspected, corrected, expired, or tied back to why it was saved, it will eventually become hidden prompt debt." Plus: agent-written memories without review "forever alter behavior in ways you don't want." → our sync_turn needs TTL/dedup/contradiction handling before it becomes debt.

**Adoption reality (ranked):** file-based (CLAUDE.md/MEMORY.md) >> lossless-claw > mem0 > Zep > Letta > local vector DBs > custom solutions.

## Part 4 — The Decision: Hindsight standalone, or build on datom?

**Verdict: build on datom, steal from Hindsight. Hindsight is a roadmap, not a replacement.**

### Why not switch to Hindsight:

1. **Deployment model mismatch.** Hindsight is a server: PostgreSQL + pgvector + worker + control plane, 1.5-2GB RAM, multi-container. Datom is embedded LMDB — zero infra, runs in one process we already ship as a systemd unit on lattice. Switching = trading a working zero-config embedded store for a PG cluster.
2. **LLM dependency is the dealbreaker.** Hindsight routes every retain/recall/reflect call through an LLM. That's ongoing API cost, 200-500ms latency per memory op, and an external runtime dependency — exactly what the community complains about in mem0/Zep/Letta. Datom is zero marginal cost, fully offline-capable. Wes's stack is self-hosted by principle.
3. **The core is the same.** Hindsight's differentiators (consolidation, temporal, opinion) are *additive layers* on top of hybrid retrieval — which datom already has. Nothing in Hindsight's architecture requires starting over; the features are portable.
4. **Already wired.** datom is deployed, tested (16 Clojure tests/91 assertions, 38 Python tests), MCP + JSON API + Hermes plugin working end-to-end, integrated with mcp-injector. Rebuilding on Hindsight = throwing that away for a PG dependency.

### Why Hindsight is still the single most valuable reference:

1. **It validates the direction.** 91.4% LongMemEval with the hybrid+graph+temporal approach confirms datom's core design.
2. **Retain→Recall→Reflect is a feature roadmap for datom.** The order to steal:
   - **Observation consolidation** (dedup + evidence tracking + staleness) — highest value; directly answers the community's "hidden prompt debt" warning.
   - **Temporal layer** (dual tracking: when event happened vs when learned) — cheap, high leverage for an agent that changes over time.
   - **Entity resolution** (fuzzy name matching for the graph) — makes graph-expand far more useful.
   - **Opinion network with confidence** (later; most complex, least urgent).
3. **MCP-first** — same integration philosophy; datom is already there.

### What the comparison round changes about our plan:

1. **The 4-move plan stands** (stub MEMORY.md, live system_prompt_block, bootstrap_recall, nightly consolidation) — the ecosystem consensus validates it precisely.
2. **Add a lifecycle layer early** — TTL, dedup, contradiction handling on sync_turn auto-memories, *before* the passive store grows into prompt debt. This is the community's #1 advanced-user warning and datom currently lacks it.
3. **Keep the hybrid-first design** — the emerging consensus ("structured not fuzzy") is literally datom's architecture; vector-only competitors are fighting a confound (embedding truncation) datom doesn't have.

---

*Sources: Hindsight GitHub/docs/arXiv:2512.12818; vectorize.io/articles; framework docs (code.claude.com, docs.cline.bot, agents.md, gptme.org, aider.chat, docs.openclaw.ai); HN via Algolia (items 47250460, 47260077, 47153987, 46511540, 48337689, 44912780, 43940654); r/openclaw, r/ClaudeCode, r/LocalLLaMA via old.reddit; Zep blog; Neo4j blog; XDA; TuringPost. Full comparison table: `agent-memory-comparison.md`.*

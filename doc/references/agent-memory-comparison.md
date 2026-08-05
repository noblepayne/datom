# Agent Framework Memory Systems: Comparison & Datom Analysis

**Date:** 2026-08-04  
**Research scope:** Built-in memory mechanisms of major coding/agent frameworks, compared with Hermes Agent + datom (Datalog + vector + fulltext, LMDB-backed).

---

## Comparison Table

| Framework | Memory Mechanism | In-Context | Retrieved | Strengths | Weaknesses |
|---|---|---|---|---|---|
| **OpenClaw** | `MEMORY.md` (curated facts), `USER.md` (preferences), `memory/YYYY-MM-DD.md` (daily notes), `DREAMS.md` (dream-diary summaries). Agent writes files; "dreaming" sweep consolidates daily → long-term. `memory_search`/`memory_get` tools index daily files. | `MEMORY.md`, `USER.md`, today's + yesterday's daily notes — injected at session start with a hard budget. | `memory/*.md` indexed and searchable via tool calls; not in prompt unless retrieved. | Simple, transparent (plain MD files you can read/edit manually); dreaming consolidation pattern; user-model layer (`USER.md`); action-sensitive memory with expiry/safe-to-act boundaries; import from Codex/Claude/Hermes. | No vector or semantic search — retrieval is keyword-based indexing only; MEMORY.md truncates silently if it exceeds budget; consolidation is LLM-dependent (dreaming can hallucinate or drop context); no graph/relational structure; no automatic importance decay. |
| **Claude Code** | `CLAUDE.md` files (hierarchical: org → user → project → local), `.claude/rules/` (scoped per-path), auto memory (`MEMORY.md` Claude writes for itself, 200-line / 25KB cap per repo). `/init` bootstraps from codebase analysis. | All CLAUDE.md files + auto memory (first 200 lines) loaded at session start. `@import` syntax pulls additional files eagerly. Rules can be path-scoped (lazy-loaded when matching files opened). | No retrieval layer — everything must fit the in-context budget. No vector search, no indexed daily notes, no RAG. | Hierarchical scoping (4 levels); path-specific rules reduce noise; AGENTS.md interop (60k+ projects); auto memory learns from corrections; subagent memory support; clean separation of user-wrote vs. Claude-wrote. | No long-term retrieval beyond context window — once something falls out of CLAUDE.md, it's gone; auto memory can bloat to 25KB and crowd active tasks; context cost analysis shows CLAUDE.md is an eager load; no semantic search; no cross-session conversation memory. |
| **OpenAI Codex** | `AGENTS.md` (repo-level instructions, hierarchical nesting), ChatGPT/Codex Memories (cloud-stored preferences), "Chronicle" (screen-reading context capture). `MEMORY.md` + `memory_summary.md` in `~/.codex/memories`. | `AGENTS.md` loaded at session start (nearest to CWD wins). Memories auto-injected per OpenAI account. Chronicle captures recent screen content. | Memories are cloud-retrieved across sessions (server-side). Chronicle maintains a buffer of recent screen captures. | Cross-platform memory (account-level, not just repo); Chronicle captures visual context that text files miss; AGENTS.md standard adopted by 60k+ repos; hierarchical nesting for monorepos. | AGENTS.md studies show 20%+ inference cost increase with no clear task success improvement (arxiv 2602.11988); Chronicle raises privacy concerns; memories are opaque (can't inspect what's stored); no semantic search or structured knowledge base; no agent-level memory ownership. |
| **Cline (Memory Bank)** | 6 hierarchical markdown files: `projectbrief.md`, `productContext.md`, `activeContext.md`, `systemPatterns.md`, `techContext.md`, `progress.md`. User triggers "initialize/update memory bank" manually. | All 6 files read at session start via custom instructions (`.clinerules/memory-bank.md`). | No retrieval — purely in-context. | Excellent documentation methodology; structured hierarchy; explicit context-window management workflow (update → new task → resume); works with any AI tool; clear separation of stable (brief) vs. volatile (active context). | Entirely manual — no auto-consolidation, no auto-capture; depends on user discipline to update; no semantic search; no importance ranking; no temporal decay; no cross-project memory. |
| **Roo Code** | Fork of Cline with same Memory Bank methodology. Same 6-file structure. Adds role-based modes (Code, Architect, Ask) with separate contexts. | Same as Cline — all files read at session start. | Same as Cline — no retrieval. | Same as Cline; role separation can help focus context on relevant aspects. | Same weaknesses as Cline; adds complexity of multi-mode context management. |
| **Aider** | Repo-map (AST-generated symbol map), `.aider.conf.yml` for config, `.aider.notes.md` for persistent notes, `--read` flag for additional context files. | Repo-map injected into every edit (symbol signatures + call graph). Notes file read at start. Files specified via `--read`. | Repo-map is generated fresh per session via Tree-sitter; not truly "retrieved" but dynamically constructed. No RAG or vector search. | AST-aware — understands code structure, not just text; repo-map scales to large codebases by showing only important symbols; clean integration with edit workflow; no overhead for simple projects. | No semantic/conceptual memory — repo-map is structural only; no cross-session learning; no user preference memory; notes file is flat text; no importance ranking; no auto-consolidation. |
| **gptme** | Git-based agent repository with: `README.md`, `ABOUT.md`, `ARCHITECTURE.md`, `gptme.toml` (config), `journal/` (daily logs YYYY-MM-DD.md), `tasks/` (YAML-frontmatter task files), `knowledge/`, `lessons/`, `people/`, `projects/`. | Recent journal entries + active tasks + git status + core files — dynamically generated via `context_cmd`. | `knowledge/`, `lessons/`, `people/`, `projects/` are on-disk but not auto-loaded; agent must explicitly read them. | Git-native (version-controlled memory, branching, diffing); rich directory structure (journal, tasks, knowledge, lessons, people); dynamic context generation; task state machine (new/active/paused/done). | Requires git repo per agent (overhead); context generation is custom code (fragile); no vector search or semantic retrieval; manual curation needed for knowledge files; relatively niche/adoption. |
| **Goose** | Minimal — relies on MCP tool ecosystem + conversation context. No dedicated memory files. | Conversation context window only. | MCP tools provide external state access (databases, APIs, etc.) but no built-in memory retrieval. | Lightweight; extensible via MCP; no memory-management overhead. | No persistent memory at all — completely stateless between sessions; depends entirely on external tools for any form of state; no learning or adaptation. |
| **Hermes Agent + datom** | **Dual system:** (1) File-based `MEMORY.md` + `USER.md` (frozen at session start); (2) datom provider: Datalog + vector + fulltext hybrid over LMDB, exposed as MCP tools (`search`, `answer`, `context`, `lookup`, `remember`, `forget`, `graph-expand`, `ingest-luds`) + JSON API. `sync_turn` auto-stores conversation turns. Session search via FTS5 (hermes-native). | MEMORY.md/USER.md injected at startup; datom `system_prompt_block()` can inject top-N high-importance memories; session_search provides FTS5 across conversation history. | datom's vector similarity + fulltext + Datalog queries on demand via MCP tool calls; `graph-expand` for relational traversal; `context` for hybrid retrieval; `answer` for direct LLM-grounded answers from store. | **Only system with true hybrid retrieval** (vector + fulltext + relational/Datalog); structured knowledge (typed entities, graph relationships); passive auto-capture via `sync_turn`; MCP-native (any tool-capable agent can use it); LMDB (fast, embedded, no server); importance ranking + decay potential; session history search (FTS5). | **Currently under-utilized:** `system_prompt_block()` is still a hardcoded stub (not live-querying top memories); no nightly consolidation cron yet; no importance decay implemented; bootstrap recall not wired; MEMORY.md and datom are redundant (file memory should be stubbed); requires Clojure JVM (not zero-dependency); single-process LMDB constraint (MCP + API must share JVM). |

---

## Key URLs

| Source | URL |
|---|---|
| OpenClaw memory docs | https://docs.openclaw.ai/concepts/memory |
| OpenClaw memory (GitHub) | https://github.com/openclaw/openclaw/blob/main/docs/concepts/memory.md |
| Claude Code memory docs | https://code.claude.com/docs/en/memory |
| AGENTS.md standard | https://agents.md/ |
| AGENTS.md efficacy study | https://arxiv.org/abs/2602.11988 |
| Cline Memory Bank docs | https://docs.cline.bot/best-practices/memory-bank |
| Aider repo-map docs | https://aider.chat/docs/repomap.html |
| gptme agents docs | https://gptme.org/docs/agents.html |
| Codex Chronicle coverage | https://9to5mac.com/2026/04/20/codex-for-mac-gains-chronicle/ |
| Hermes/Claude Code/OpenClaw architecture comparison paper | https://arxiv.org/abs/2604.14228 |
| Hermes memory providers (local skill) | `hermes-memory-providers` skill, references/datom-integration.md |

---

## Analysis: Shared Patterns & Datom's Position

**What the best memory systems share:**

1. **Two-tier architecture:** The strongest systems (OpenClaw, Claude Code, Hermes+datom) all separate "always-in-context" curated facts from "retrieved on demand" detailed notes. OpenClaw calls these MEMORY.md vs. daily notes; Claude Code has CLAUDE.md vs. auto memory; Hermes has MEMORY.md vs. datom's retrieval store. This mirrors the "core identity + working context + long-term retrieval" pattern that cognitive science research recommends.

2. **Auto-capture with human override:** OpenClaw's dreaming sweep, Claude Code's auto memory, datom's `sync_turn`, and gptme's journal system all automatically record context. The best ones let users correct or override (OpenClaw's supersede-in-place, Claude Code's manual CLAUDE.md edits, datom's `remember`/`forget` tools).

3. **Structured organization over flat dumps:** Cline's 6-file hierarchy, OpenClaw's typed files (USER/MEMORY/daily/dreams), gptme's directory taxonomy (journal/tasks/knowledge/lessons/people), and datom's typed entities all recognize that raw dumps become noise. Structure enables selective loading and relevance scoring.

4. **Context budget awareness:** Every serious system grapples with the context window limit. OpenClaw truncates MEMORY.md silently; Claude Code caps auto memory at 200 lines/25KB; Hermes freezes at startup; Cline manually triggers compact. This is the central engineering challenge.

**Where datom beats the field:**

- **Hybrid retrieval is unique.** No other framework in this comparison offers vector similarity + fulltext search + Datalog relational queries in a single store. OpenClaw's `memory_search` is keyword-only. Claude Code has no retrieval layer at all. Cline/Roo have no retrieval. Aider's repo-map is structural, not semantic. This makes datom the only system where you can ask "find memories related to X" AND "find all entities connected to Y" AND "search for text containing Z" in one query.

- **Graph/relational structure.** `graph-expand` enables traversing entity relationships — e.g., "show me everything connected to the NixOS config migration." No other agent framework has this. gptme's `people/` and `projects/` directories are manual approximations.

- **MCP-native tooling.** datom's tools (`search`, `answer`, `context`, `lookup`, `remember`, `forget`, `graph-expand`, `ingest-luds`) are accessible to any MCP-capable agent, not just Hermes. Claude Code, Codex, and others support MCP — they could all use datom's store as a shared memory layer.

- **Importance + decay architecture** (planned). The skill doc identifies this as a next step, but the data model already supports it via importance tags. No other framework has built-in importance ranking or temporal decay.

**Where datom loses to the field:**

- **Maturity of integration.** OpenClaw's dreaming consolidation is shipping and working. Claude Code's auto memory is production. datom's `system_prompt_block()` is still a hardcoded stub — the most critical piece (injecting top memories at session start) isn't live yet. The dual-memory redundancy (file + datom) adds confusion without the planned migration (stub MEMORY.md → bootstrap pointer).

- **Simplicity.** Cline's Memory Bank is 6 markdown files with a README. Anyone can understand it in 5 minutes. datom requires a Clojure JVM, LMDB, MCP server, mcp-injector config, and Hermes plugin wiring. The barrier to adoption is orders of magnitude higher. For most coding tasks, Cline's flat files may be "good enough."

- **Context injection strategy.** Claude Code's approach of loading everything hierarchical (org → user → project → local) with path-scoped lazy rules is elegant and well-tested. datom's approach of "inject top-N memories + retrieve the rest" is theoretically superior but not yet operational. Until `system_prompt_block()` actually queries the store and `bootstrap_recall` runs at session start, the theoretical advantage is unrealized.

- **Cross-tool standard.** AGENTS.md is now a Linux Foundation standard read by 60k+ repos and 30+ agents. datom is one user's custom setup. Even if datom is technically superior, it serves one deployment. The network effect of a shared standard (even a dumb one) often beats bespoke excellence.

---

*Research compiled from official docs, GitHub sources, arxiv papers, and local Hermes/datom skill documentation.*

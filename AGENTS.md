# AGENTS.md — datom

## Workflow Conventions

### Tooling
- **ALWAYS use clojure-dev tools** (`clojure-dev_read_file`, `clojure-dev_file_write`, `clojure-dev_clojure_edit`, etc.) — never use generic `read`/`edit`/`write` tools for Clojure files.
- **Format** with `cljfmt` after every change: `cljfmt fix <file>`
- **Lint** with `clj-kondo` after every change: `clj-kondo --lint <file>`
- **Both must pass** before any commit.

### Process
- **PLAN → ACT → VERIFY** for every task. Plan first, execute, verify result.
- **READ → EDIT → VERIFY** for every file change. Read the file first, understand context, edit, verify.
- **Snapshot commits**: one commit per logical change. Each commit must leave tests green.
- **Branch**: `feat/mvp` — all MVP work here.
- **Sub-agents**: use `task` for complex multi-step work, but always review their output. Pass these conventions to them.
- **Major features**: use a sub-agent to review before committing.

### Testing
- **Test-first mindset** — think about what the test should assert before writing the implementation (loose TDD).
- **Mostly integration / e2e tests** — not exhaustive unit tests. Cover the roundtrip.
- **"Not toooo many"** — judicious coverage, not death by testing.
- **Green always** — never commit a failing test. Fix existing failures first.

### Debugging & Design
- **Socratic questioning** — ask "why does this work this way?", "what's the simplest path?", "what am I assuming?"
- **Scientific debugging** — form a hypothesis, predict an outcome, test it, learn.
- **Inspirations**: Rich Hickey (simplicity, composability), Hillel Wayne (formal methods thinking, clarity), Eric Normand (functional design, making implicit state explicit).

### Code Style
- **No comments in code** unless the intent cannot be expressed in the code itself.
- **Data in, data out** — pure functions where possible.
- **Imperative shell, functional core** — side effects at the boundary.
- **Composable primitives** — sys-threading pattern.

## Quick Reference

### MCP Server

```bash
setsid clojure -M:mcp 9090 > /dev/shm/datom.log 2>&1 &
ss -tlnp | grep 9090
fuser -k 9090/tcp
```

Port: **9090**. Log: `/dev/shm/datom.log`

### nREPL

```bash
setsid clojure -M:repl > /tmp/datom-nrepl.log 2>&1 &
ss -tlnp | grep 7888
fuser -k 7888/tcp
```

Port: **7888**

### Tests

```bash
rm -rf /tmp/datom-db /tmp/datom-search /tmp/datom-test-db /tmp/datom-test-search
clojure -M:test -d test
```

### Clean DB

```bash
rm -rf /tmp/datom-db /tmp/datom-search
```

LMDB is single-process: only one JVM can hold the path at a time.

## Architecture

- `datom.mcp` — MCP server (9 tools, port 9090)
- `datom.api` — JSON HTTP API (5 routes, port 9091)
- `datom.core` — orchestrator (9 public primitives)
- `datom.store` — schema, LMDB connections, CRUD
- `datom.chunk` — paragraph splitting with overlap (pure)
- `datom.index` — fulltext + vector + RRF + incremental `index-docs!`
- `datom.graph` — link extraction, neighbors, dependents (uses `:content/depends` canonical)
- `datom.query` — search, answer, context
- `datom.ingest` — ContentSource protocol + generic ingest (returns `{:ids ...}` summary)
- `datom.ingest.luds` — LUDS markdown adapter (reference impl)

### Index lifecycle

Three operations:
- `init-search!` — open persisted indices, load embedding model. Server startup only.
- `index-docs!` — add specific doc IDs to indices. Incremental, O(m). After each ingest.
- `index!` — full rebuild from all docs. CLI one-shot / reset.

Server path: `store-init → init-search!` (once). Then per-ingest: `ingest/ingest` (Datalog only) → `index-docs!` (incremental).

## Conventions

- **Namespaced keywords** for schema (`:content/id`, `:content/body`, etc.)
- **`catch Throwable`** in ensure-conn! — Datalevin throws `AssertionError`
- **Datalevin via Nix uberjar** — local path in deps.edn. G2 in plan: Maven coord as canonical.
- **ContentSource protocol** — ingest adapters implement `source-id`, `source-type`, `source-items`
- **`:content/depends`** — canonical graph edge attribute. No source-specific keys in graph module.
- **Source metadata** goes in `:content/meta` (e.g., `:datom.ingest.luds/lud`)
- **MCP protocol** `2025-11-25` — current stable Streamable HTTP with sessions

## MCP Tools

| Tool | Description |
|------|-------------|
| search | Hybrid fulltext + vector with RRF |
| answer | Human-readable search results |
| context | Search + graph neighbor expansion |
| lookup | Pull document by ID |
| stats | System statistics |
| graph-expand | 1-hop neighborhood with titles |
| ingest-luds | Ingest LUDS markdown from directory |
| remember | Store a new document |
| forget | Remove a document by ID |

Planned: `update`

## Workflow

1. Start MCP server: `setsid clojure -M:mcp 9090 > /dev/shm/datom.log 2>&1 &`
2. Ingest test data: `datom_ingest-luds path=test/fixtures/luds`
3. Search: `datom_search query:"bech32"` / `datom_context query:"channel"` / `datom_stats`

## MVP Plan (Phases A-G)

See `doc/HANDOFF.md` for full plan. Summary:

| Phase | What | Status |
|-------|------|--------|
| A | Core fixes | Done |
| B | MCP compliance | Done |
| C | Write tools (remember/forget) | Done |
| D | JSON API on 9091 | Done |
| E | Hermes plugin | Done |
| F | Integration tests | Done (F1-F6) |
| G | Polish (titles, shutdown hook) | Done |

## Hermes Integration

datom provides a **JSON HTTP API** on port 9091 (Phase D). The Hermes Python plugin (Phase E) in `plugins/memory/datom/` implements `MemoryProvider` ABC, calling `httpx.post()` against the JSON API. No MCP client in Python.

Key hooks: `prefetch(query)` → `POST /api/search {query, top:5, expand:1}`, formatted as distilled facts; `sync_turn(user, assistant)` → `POST /api/remember` (non-blocking daemon thread); `on_pre_compress(messages)` saves relevant context before compression.

Test command: `nix-shell -p python3Packages.pytest --run "python3 -m pytest plugins/memory/datom/test_provider.py -v"`
35 tests (32 pass without httpx, 3 skip when httpx unavailable).

## Data Sources

- **Test fixtures**: `test/fixtures/luds/` (6 toy specs + README for quick testing)
- **LUDS specs**: Real data cloned to `/tmp/luds` (23 specs from `lnurl/luds`)

## Planned (post-MVP)

- Transcript adapter — podcasts (VTT/SRT), YouTube captions
- Logseq interop — file-based `.md` adapter, block hierarchy, page refs
- Memory tiers / compaction
- Upgrade embedding model (BGE-M3: 1024 dims, 8K tokens)

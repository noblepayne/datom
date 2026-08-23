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
# Clojure (16 tests, 91 assertions)
clojure -M:test -d test
# Python (38 tests)
nix develop --command python3 -m pytest plugins/memory/datom/test_provider.py -v
```

### Clean DB

```bash
rm -rf /tmp/datom-db /tmp/datom-search
```

LMDB is single-process: only one JVM can hold the path at a time.

## Architecture

- `datom.config` — env var reading (`get-env` wraps `System/getenv` for testability)
- `datom.mcp` — MCP server (9 tools, port 9090)
- `datom.api` — JSON HTTP API (5 routes, port 9091)
- `datom.core` — orchestrator (9 public primitives, forget now cleans search indices)
- `datom.store` — schema, LMDB connections, CRUD, `close!`
- `datom.chunk` — paragraph splitting with overlap (pure)
- `datom.index` — fulltext + vector + RRF + incremental `index-docs!`
- `datom.graph` — link extraction, neighbors, dependents (uses `:content/depends` canonical)
- `datom.query` — search, answer, context
- `datom.ingest` — ContentSource protocol + generic ingest (returns `{:ids ...}` summary)
- `datom.ingest.luds` — LUDS markdown adapter (reference impl)

### Config flow

```
NixOS module options → env vars (DATOM_DB_DIR, DATOM_MCP_PORT, etc.)
  → datom.config/get-env (Clojure fn, with-redefs-able)
    → datom.store/store (merges into opts, test opts override)
```

### Index lifecycle

Three operations:
- `init-search!` — open persisted indices, load embedding model. Server startup only.
- `index-docs!` — add specific doc IDs to indices. Incremental, O(m). After each ingest.
- `index!` — full rebuild from all docs. CLI one-shot / reset.

Server path: `store-init → init-search!` (once). Then per-ingest: `ingest/ingest` (Datalog only) → `index-docs!` (incremental).

## Conventions

- **Namespaced keywords** for schema (`:content/id`, `:content/body`, etc.)
- **`catch Throwable`** in ensure-conn! — Datalevin throws `AssertionError`
- **Datalevin via Maven** — `datalevin/datalevin {:mvn/version "0.10.18"}` on Clojars. clj-nix pins via deps-lock.json.
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
| G | Polish (titles, shutdown hook, README, Maven coord) | Done |
| H | Review findings (4 reviews, ~40 issues) resolved | All accepted items done |

## Hermes Integration

datom provides a **JSON HTTP API** on port 9091 (Phase D). The Hermes Python plugin (Phase E) in `plugins/memory/datom/` implements `MemoryProvider`, calling `httpx.post()` against the JSON API. No MCP client in Python.

Key hooks: `prefetch(query)` → `POST /api/search {query, top:5, expand:1}`, formatted as distilled facts; `sync_turn(user, assistant)` → `POST /api/remember` (non-blocking daemon thread); `on_pre_compress(messages)` saves relevant context before compression.

Test command:
```bash
nix develop --command python3 -m pytest plugins/memory/datom/test_provider.py -v
```
38 tests.

## Data Sources

- **Test fixtures**: `test/fixtures/luds/` (6 toy specs + README for quick testing)
- **LUDS specs**: Real data cloned to `/tmp/luds` (23 specs from `lnurl/luds`)

## Post-MVP: Deferred Items

These came from 4 code reviews (June 2026). Addressed the critical/high items; these remain for post-MVP.

### Security
- **Body size limit on API** — `slurp` has no limit → OOM vector. Add `:max-body` to http-kit config: `{:port port :max-body 10485760}`. Trivial fix.
- **Path traversal in ingest-luds** — Currently accepts any path. Must validate against allowed base directory. Needs design: what's the allowed base?

### Correctness
- **Chunk children inherit `:content/depends`** — When a parent doc has `:content/depends`, its chunk children inherit it in the LUDS extract-links phase. This over-counts links. Needs careful review of `extract-links` in `datom.graph`.
- **`update` write tool** — Transact updated attrs, re-index. Planned MCP tool, not yet implemented.

### Protocol / Safety
- **MCP session-id validation** — Currently unused/unchecked. Should validate on every request. Needs design (stored session IDs, TTL).
- **LMDB lock file recovery** — Crash → stale `lock.mdb` → infinite restart loop on NixOS. Needs init script or systemd `ExecStartPre` to clean stale locks.

### Observability
- **Structured logging** — Currently `println` for startup banner only. Add proper logging (mulog or similar).

### Concurrent access
- **Concurrent access tests** — All tests run single-threaded. Need tests that exercise concurrent ingest/search/forget.

### Hermes Plugin
- **ABC inheritance** — `DatomMemoryProvider` should inherit from Hermes' `MemoryProvider` ABC. Currently duck-typed. Hermes dependency for the ABC import.
- **`on_pre_compress` callback** — Relies on Hermes core fix #7192. Currently no-op returns `""`.

### Feature Gaps
- **Transcript adapter** — Podcasts (VTT/SRT), YouTube captions. New ContentSource.
- **Logseq interop** — File-based `.md` adapter, block hierarchy, page refs.
- **Memory tiers / compaction** — `compact` fn is a placeholder. Tiered storage (hot/warm/cold).
- **Upgrade embedding model** — BGE-M3: 1024 dims, 8K tokens. Requires re-index.
# AGENTS.md — datom

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

- `datom.mcp` — MCP server (7 tools) + JSON HTTP API (planned, port 9091)
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
| graph-expand | 1-hop neighborhood |
| ingest-luds | Ingest LUDS markdown from directory |

Planned: `remember`, `forget`, `update`

## Workflow

1. Start MCP server: `setsid clojure -M:mcp 9090 > /dev/shm/datom.log 2>&1 &`
2. Ingest test data: `datom_ingest-luds path=test/fixtures/luds`
3. Search: `datom_search query:"bech32"` / `datom_context query:"channel"` / `datom_stats`

## MVP Plan (Phases A-G)

See `doc/HANDOFF.md` for full plan. Summary:

| Phase | What | Key items |
|-------|------|-----------|
| A | Core fixes | Deduplicate chunking, map args, fix redundant init-search!, validate bounds |
| B | MCP compliance | Handle initialized notification, fix errors, TOCTOU race, logging |
| C | Write tools | remember, forget |
| D | JSON API | Thin http-kit server on 9091 for Hermes plugin |
| E | Hermes plugin | Python MemoryProvider in plugins/memory/datom/ |
| F | Tests | Incremental, alongside each phase |
| G | Polish | README, portable deps, graph-expand titles, shutdown hook |

## Hermes Integration

datom provides a **JSON HTTP API** on port 9091 (Phase D). The Hermes Python plugin (Phase E) in `plugins/memory/datom/` implements `MemoryProvider` ABC, calling `httpx.post()` against the JSON API. No MCP client in Python.

Key hooks: `prefetch(query)` → `POST /api/search`, `sync_turn(user, assistant)` → `POST /api/remember`.

## Data Sources

- **Test fixtures**: `test/fixtures/luds/` (6 toy specs + README for quick testing)
- **LUDS specs**: Real data cloned to `/tmp/luds` (23 specs from `lnurl/luds`)

## Planned (post-MVP)

- Transcript adapter — podcasts (VTT/SRT), YouTube captions
- Logseq interop — file-based `.md` adapter, block hierarchy, page refs
- Memory tiers / compaction
- Upgrade embedding model (BGE-M3: 1024 dims, 8K tokens)
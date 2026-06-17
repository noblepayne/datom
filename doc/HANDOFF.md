# datom — Composable Agent Memory

datom is a composable agent memory system built on Datalevin. Hybrid fulltext + vector search (RRF fusion), paragraph chunking, graph relationships, and an MCP server — all through clean, composable Clojure primitives.

## Current State

**MVP is nearly complete (Phases A-D, F, G done).** 9 tests, 46 assertions, 0 failures. Only Phase E (Hermes plugin) remains.

### What's built

- Fulltext search (TF-IDF via Datalevin `new-search-engine`)
- Vector search (HNSW via Datalevin `new-vector-index`, 384-dim default embedding, cosine metric)
- RRF fusion (k=60) combining both rankings
- Paragraph-level chunking (max-chars 2000, overlap 200)
- Graph extraction (markdown link parsing, `:content/depends` canonical, 1-hop neighbors/dependents with titles)
- ContentSource protocol for pluggable ingest adapters (LUDS markdown reference impl)
- 9 public primitives: `store-init`, `ingest`, `search`, `answer`, `context`, `graph-expand`, `lookup`, `stats`, `remember`, `forget`
- MCP server with 9 tools: search, answer, context, lookup, stats, graph-expand, ingest-luds, remember, forget
- JSON HTTP API on port 9091: 5 routes (search, answer, remember, forget, stats)
- CLI entry point (`-main`) for MCP server
- Separate `init-search!` (server startup — load persisted indices) and `index-docs!` (incremental — add only new docs)
- Shutdown hooks for LMDB cleanup
- `store/close!` for graceful connection teardown

### Module map

```
datom.mcp (MCP server, port 9090)     datom.api (JSON HTTP API, port 9091)
  └── datom.core (orchestrator)
        ├── datom/store.clj    schema, LMDB connections, CRUD, close!
        ├── datom/chunk.clj    paragraph splitting with overlap (pure, no deps)
        ├── datom/index.clj    fulltext + vector + RRF fusion
        ├── datom/graph.clj    link extraction, neighbors, dependents
        ├── datom/query.clj    search, answer, context primitives
        ├── datom/ingest.clj   ContentSource protocol + generic ingest
        └── datom/ingest/luds.clj  LUDS markdown adapter
```

## Architecture

### Layer diagram

```
┌──────────────────────────────────────────────────────┐
│                     datom.core                        │
│  (store, ingest, search, context, graph, lookup,     │
│   stats, compact)                                     │
├──────────────────────────────────────────────────────┤
│  datom.query    │  datom.graph   │  datom.ingest     │
│  (search,       │  (neighbors,   │  (ContentSource   │
│   answer,       │   dependents,  │   protocol,       │
│   context)      │   extract-links)│  ingest)          │
├──────────────────────────────────────────────────────┤
│  datom.index (fulltext + vector + RRF + index-docs!) │
│  datom.chunk (paragraph splitting + overlap)         │
├──────────────────────────────────────────────────────┤
│  datom.store (schema, connections, CRUD)             │
├──────────────────────────────────────────────────────┤
│  Datalevin (datalog + LMDB + search + vector)        │
└──────────────────────────────────────────────────────┘
```

### Naming conventions

- Schema: `:content/` namespace (`:content/id`, `:content/body`, etc.)
- Source metadata: `:datom.ingest.<source>/` in `:content/meta`
- Canonical graph edges: `:content/depends` (vector of string IDs)
- Internal keys: `::namespace/key` in sys map

### Index lifecycle (IMPORTANT)

Three operations, two patterns:

| Function | Purpose | Calls init-search? | When to use |
|----------|---------|-------------------|-------------|
| `init-search!` | Open persisted indices, load embedding model | N/A | Server startup once |
| `index-docs!` | Add specific docs to indices | No (asserts) | After each ingest |
| `index!` | Full rebuild from all docs | Yes (internally) | CLI one-shot, reset |

Server path: `store-init → init-search!` (once). Then per-ingest: `ingest/ingest` (Datalog only) → `index-docs!` (incremental).

### MCP protocol version

Using **`2025-11-25`** — current stable Streamable HTTP with session-based handshake.

## MVP Plan

### Phase A — Core library fixes

| # | Item | File | Description |
|---|------|------|-------------|
| A1 | Deduplicate chunking | `ingest.clj` | Call `chunk/chunk-doc` instead of reimplementing paragraph split |
| A2 | Map-based args downstream | `core.clj` `query.clj` | Replace `apply mapcat identity opts` with map args |
| A3 | Stop redundant `init-search!` | `core.clj` | `ingest` calls `index-docs!` only, not `init-search!` |
| A4 | Validate chunk bounds | `chunk.clj` | `(assert (pos? max-chars))`, clamp overlap |
| A5 | Combine `index!` loops | `index.clj` | One `doseq` instead of two |
| A6 | Extract author-filter fn | `query.clj` | From `search`, separate concern |

### Phase B — MCP server compliance

| # | Item | File | Description |
|---|------|------|-------------|
| B1 | Keep `2025-11-25` | `mcp.clj` | Current stable. No change. |
| B2 | Handle `notifications/initialized` | `mcp.clj` | Return nil/ack |
| B3 | Fix error responses | `mcp.clj` | JSON-RPC `:error` with codes, not `isError` |
| B4 | Fix `init-system!` race | `mcp.clj` | `swap!` with `or`, TOCTOU fix |
| B5 | Log stack traces | `mcp.clj` | Replace `.getMessage` with printStackTrace |
| B6 | Remove `listChanged` cap | `mcp.clj` | Or implement notifications |

### Phase C — Write tools for agents

| # | Item | File | Description |
|---|------|------|-------------|
| C1 | `remember` | `core.clj` + `mcp.clj` | Accept `{:id title body type tags importance}`, transact + index-docs! |
| C2 | `forget` | `core.clj` + `mcp.clj` | Retract entity + remove from indices |
| C3 | `update` | (Phase 2) | Transact updated attrs, re-index |
| C4 | `list-sources` | (Phase 2) | Available ContentSources |

### Phase D — JSON HTTP API (for Hermes plugin)

Thin Ring/http-kit server on port 9091 sharing the same `datom.core` functions.

| # | Route | Maps to | Returns |
|---|-------|---------|---------|
| D1 | `GET /api/stats` | `datom/stats` | Stats map |
| D2 | `POST /api/search` | `datom/search` | `{:results [...]}` |
| D3 | `POST /api/answer` | `datom/answer` | `{:answer "..."}` |
| D4 | `POST /api/remember` | `datom/remember` | `{:id "..."}` |
| D5 | `POST /api/forget` | `datom/forget` | `{:deleted true}` |

Zero business logic duplication — MCP tools and JSON handlers both call `datom.core`.

### Phase E — Hermes Python plugin

```
plugins/memory/datom/
├── __init__.py       # DatomMemoryProvider + register()
├── plugin.yaml       # name: datom, hooks: [sync_turn]
└── README.md         # Setup instructions
```

Mappings:
- `initialize()` — verify datom server is listening at `http://localhost:9091`
- `get_tool_schemas()` — schemas for `search`, `remember`, `forget`, `lookup`, `stats`
- `handle_tool_call(name, args)` → `httpx.post("http://localhost:9091/api/" + name, json=args)`
- `prefetch(query)` → `POST /api/search {query, top:5, expand:1}` — return context text
- `sync_turn(user, assistant)` → `POST /api/remember {body: "...", type: "conversation"}`
- `shutdown()` — no-op

Implementation detail: the Hermes `MemoryProvider` ABC expects:
- `MemoryProvider.name` (property)
- `MemoryProvider.is_available()` (no network calls)
- `MemoryProvider.initialize(session_id, **kwargs)` (receives `hermes_home`)
- `MemoryProvider.get_tool_schemas()` → list of tool JSON schemas
- `MemoryProvider.handle_tool_call(name, args)` → response
- `MemoryProvider.get_config_schema()` → config field descriptors
- `MemoryProvider.save_config(values, hermes_home)` → write config
- Optional hooks: `prefetch`, `sync_turn`, `on_session_end`, `shutdown`

### Phase F — Tests

Add as we go, don't defer all to the end. Key tests:

| # | Test | When |
|---|------|------|
| F1 | Roundtrip: transact → lookup → assert body | Phase A |
| F2 | Chunk boundaries: overlap=0, overflow, empty | Phase A |
| F3 | Ingest → search → assert expected results | Phase A |
| F4 | Linked docs → neighbors → correct 1-hop | Phase A |
| F5 | `remember` → `lookup` via MCP | Phase C |
| F6 | JSON API POST → response via http-kit | Phase D |
| F7 | Randomized temp LMDB dirs, cleanup in finally | Phase A |

### Phase G — DX polish

| # | Item | Description |
|---|------|-------------|
| G1 | README.md at repo root | Port from DEMO.md |
| G2 | Document Nix dep; Maven coords as canonical | Portable builds |
| G3 | `graph-expand` returns `[{:id :title}]` | Currently returns just IDs |
| G4 | Shutdown hook for LMDB lock cleanup | `(.addShutdownHook ...)` |
| G5 | Document JSON API + Hermes integration | In README |

### Execution order

```
Phase A (core fixes) ─────────────────────┐
                                          ├──→ F1-F4 (tests as we go)
Phase B (MCP compliance) ────────────────┤
                                          │
Phase C (write tools: remember/forget) ──┤
                                          │
Phase D (JSON API on 9091) ──────────────┤
                                          │
Phase E (Hermes Python plugin) ──────────┤
                                          │
Phase G (polish: README, docs, hooks) ───┘

Phase F (tests) — incremental, alongside each phase
```

## Key Decisions

- **MCP protocol version `2025-11-25`** — current stable Streamable HTTP with sessions. Not rolling back to `2024-11-05` (old HTTP+SSE transport) and not chasing `2026-07-28` RC (stateless, deletes sessions).
- **MCP for agent tools (Claude Desktop, Cline) + JSON HTTP API for Hermes plugin** — not MCP for Hermes. Hermes plugin uses `httpx.post()` against a plain JSON endpoint; no MCP client code in Python.
- **`remember`/`forget` write tools** needed before Hermes plugin can be built (sync_turn needs write path).
- **`:content/depends` canonical** — graph module reads this generically. No source-specific key leak.
- **Incremental indexing** — `index-docs!` adds only new document IDs. No full rebuild per ingest.
- **Randomized temp dirs per test** — LMDB is single-process, test dirs must not collide.

## Critical Context

### nREPL

```
# Start (must use setsid so it survives session timeout)
setsid clojure -M:repl > /tmp/datom-nrepl.log 2>&1 &

# Verify
ss -tlnp | grep 7888

# Kill
fuser -k 7888/tcp
```

Port: **7888**

### MCP Server

```
# Start
setsid clojure -M:mcp 9090 > /dev/shm/datom.log 2>&1 &

# Kill
fuser -k 9090/tcp

# Verify
ss -tlnp | grep 9090
```

### JSON API Server

```
# Start
setsid clojure -M:api 9091 > /dev/shm/datom-api.log 2>&1 &

# Kill
fuser -k 9091/tcp
```

Port: **9091**

### Tests

```bash
clojure -M:test -d test
```

Requires clean LMDB dirs if schema changed:
```bash
rm -rf /tmp/datom-db /tmp/datom-search /tmp/datom-test-db /tmp/datom-test-search
```

### LMDB

- **Single-process**: only one JVM can hold a path at a time.
- **Paths**: `/tmp/datom-db` (datalog) and `/tmp/datom-search` (KV indices). Also `/tmp/datom-test-*` for tests.
- **Clean slate**: `rm -rf /tmp/datom-db /tmp/datom-search`
- **Crash recovery**: stale `lock.mdb` files block reconnection. Manual cleanup needed.

### Dependencies

- **Datalevin**: Nix uberjar at `/nix/store/5ssvvfs6rzkdkwjj5i81b1mnfcd9mqhx-dtlv-0.10.18-uberjar.jar` (this-machine-specific path). G2 adds Maven coordinate as canonical fallback.
- **MCP toolkit**: git dep `com.noblepayne/mcp-toolkit` via `:mcp` alias.
- **Test deps**: `http-kit`, `cheshire`, `mcp-toolkit` in `:test` alias.

## Design Principles

- **Data in, data out** — pure functions, no side effects in core logic
- **Imperative shell, functional core** — `datom.core` and `datom.mcp` orchestrate side effects; `datom.chunk`, `datom.graph` are pure
- **Composable primitives** — sys-threading pattern, `core/ingest` returns `sys`
- **Source-agnostic** — same schema for docs, RSS, YouTube, podcasts, agent sessions
- **Adapter pattern** — ContentSource protocol, `reify`-based adapters
- **Thin transport** — MCP and JSON API are thin wrappers over the same core functions
- **Incremental indexing** — never rebuild indices from scratch in a running server

## Known Issues (pre-MVP)

- Phase E (Hermes Python plugin) not yet implemented
- G1 (README.md at repo root) not yet done
- G2 (Maven coord for Datalevin) not yet done
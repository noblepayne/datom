# datom — Composable Agent Memory

datom is a composable agent memory system built on Datalevin. Hybrid fulltext + vector search (RRF fusion), paragraph chunking, graph relationships, and an MCP server — all through clean, composable Clojure primitives.

## Current State

**MVP complete (Phases A-H).** 16 Clojure tests, 91 assertions, 38 Python tests, 0 failures.

### What's built

- Fulltext search (TF-IDF via Datalevin `new-search-engine`)
- Vector search (HNSW via Datalevin `new-vector-index`, 384-dim default embedding, cosine metric)
- RRF fusion (k=60) combining both rankings
- Paragraph-level chunking (max-chars 2000, overlap 200)
- Graph extraction (markdown link parsing, `:content/depends` canonical, 1-hop neighbors/dependents with titles)
- ContentSource protocol for pluggable ingest adapters (LUDS markdown reference impl)
- 9 public primitives: `store-init`, `ingest`, `search`, `answer`, `context`, `graph-expand`, `lookup`, `stats`, `remember`, `forget`
- MCP server with 9 tools: search, answer, context, lookup, stats, graph-expand, ingest-luds, remember, forget
- JSON HTTP API on port 9091: 6 routes (search, answer, remember, forget, stats, lookup)
- CLI entry point (`-main`) for MCP server
- `datom.config` — env var reading via `get-env` (wraps `System/getenv`, with-redefs-able)
- `forget` removes from both fulltext and vector indices (`dl/remove-doc` + `dl/remove-vec`)
- Nix flake with clj-nix package, Hermes plugin, NixOS module (inline in flake.nix), devShell
- Graceful shutdown hooks for LMDB cleanup
- `store/close!` for connection teardown

### Module map

```
datom.mcp (MCP server, port 9090)     datom.api (JSON HTTP API, port 9091)
  └── datom.core (orchestrator)
        ├── datom/config.clj    env var reading, validation
        ├── datom/store.clj     schema, LMDB connections, CRUD, close!
        ├── datom/chunk.clj     paragraph splitting with overlap (pure, no deps)
        ├── datom/index.clj     fulltext + vector + RRF fusion
        ├── datom/graph.clj     link extraction, neighbors, dependents
        ├── datom/query.clj     search, answer, context primitives
        ├── datom/ingest.clj    ContentSource protocol + generic ingest
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

### Config flow

```
NixOS module options → env vars (DATOM_DB_DIR, DATOM_MCP_PORT, etc.)
  → datom.config/get-env (Clojure fn, with-redefs-able)
    → datom.store/store (merges into opts, test opts override)
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
| B4 | Fix `init-system!` race | `mcp.clj` | `defonce system (delay ...)` — single evaluation |
| B5 | Log stack traces | `mcp.clj` | `.printStackTrace` instead of `.getMessage` |
| B6 | Advertise `{:tools {}}` | `mcp.clj` | Capabilities in initialize response |

### Phase C — Write tools for agents

| # | Item | File | Description |
|---|------|------|-------------|
| C1 | `remember` | `core.clj` + `mcp.clj` | Accept `{:id title body type tags importance}`, transact + index-docs! |
| C2 | `forget` | `core.clj` + `mcp.clj` | Retract entity + remove from indices, search index cleanup |
| C3 | `update` | (Phase 2) | Transact updated attrs, re-index |
| C4 | `list-sources` | (Phase 2) | Available ContentSources |

### Phase D — JSON HTTP API (for Hermes plugin)

Thin http-kit server on port 9091 sharing the same `datom.core` functions.

| # | Route | Maps to | Returns |
|---|-------|---------|---------|
| D1 | `GET /api/stats` | `datom/stats` | Stats map |
| D2 | `POST /api/search` | `datom/search` | `{:results [...]}` |
| D3 | `POST /api/answer` | `datom/answer` | `{:answer "..."}` |
| D4 | `POST /api/remember` | `datom/remember` | `{:id "..."}` |
| D5 | `POST /api/forget` | `datom/forget` | `{:deleted true}` |
| D6 | `POST /api/lookup` | `datom/lookup` | Document map |

Zero business logic duplication — MCP tools and JSON handlers both call `datom.core`.

### Phase E — Hermes Python plugin

```
plugins/memory/datom/
├── __init__.py       # DatomMemoryProvider + register(ctx)
├── plugin.yaml       # name: datom, pip_dependencies: [httpx] (no unimplemented hooks)
└── test_provider.py  # 38 unit tests (mock httpx client)
```

Mappings:
- `initialize(session_id, hermes_home=)` — create httpx.Client, load config from `$HERMES_HOME/datom.json`, verify server at `http://localhost:9091`
- `is_available()` — config checks only (env var, config file, default). No network calls.
- `get_tool_schemas()` — 5 tools: `datom_search`, `datom_remember`, `datom_forget`, `datom_lookup`, `datom_stats`
- `handle_tool_call(name, args)` → `httpx.post(base_url + "/api/" + name, json=args)`
- `prefetch(query, session_id=)` → `POST /api/search {query, top:5, expand:1}` — distilled facts, ~200-500 token budget
- `sync_turn(user, assistant, session_id=, messages=)` → `POST /api/remember` — non-blocking daemon thread
- `on_pre_compress(messages)` — search + save context before compression, returns "" (ByteRover pattern)
- `system_prompt_block()` — static provider info for system prompt
- `shutdown()` — join daemon threads, close httpx client

Design decisions:
- **All 5 tools** exposed — agent gets full memory control
- **prefetch format**: title + 150-char snippet, max 5 items, distilled facts (Mem0/Zep consensus)
- **sync_turn**: stores raw turn (user + assistant), no LLM extraction yet
- **on_pre_compress**: saves relevant context to store, returns "" (trust retrieval)
- **is_available**: no network calls per ABC contract
- **Client injection**: constructor param enables unit tests (mock) and integration tests (real)
- **Threading**: daemon threads + locks for non-blocking sync_turn and on_pre_compress

Test command:
```bash
nix develop --command python3 -m pytest plugins/memory/datom/test_provider.py -v
```

### Phase F — Tests

| # | Test | Status |
|---|------|--------|
| F1 | Roundtrip: transact → lookup → assert body | Done |
| F2 | Chunk boundaries: overlap=0, overflow, empty | Done |
| F3 | Ingest → search → assert expected results | Done |
| F4 | Linked docs → neighbors → correct 1-hop | Done |
| F5 | `remember` → `lookup` via MCP | Done |
| F6 | JSON API POST → response via http-kit | Done |

### Phase G — DX polish

| # | Item | Description | Status |
|---|------|-------------|--------|
| G1 | README.md at repo root | Project overview, quick start, architecture, dev setup | Done |
| G2 | Maven coord for Datalevin | `datalevin/datalevin {:mvn/version "0.10.18"}` replaces Nix uberjar path | Done |
| G3 | `graph-expand` returns `[{:id :title}]` | Currently returns just IDs | Done |
| G4 | Shutdown hook for LMDB lock cleanup | `(.addShutdownHook ...)` | Done |
| G5 | Document JSON API + Hermes integration | In README + AGENTS.md | Done |

### Phase H — Review findings

4 code reviews (June 2026). ~40 issues identified. All critical/high items addressed:

| # | Item | File | Status |
|---|------|------|--------|
| H1 | `datom.config` env var wrapper | `config.clj` | Done |
| H2 | Remove hardcoded `/tmp` paths | `store.clj` | Done |
| H3 | `init-system!` race → `defonce system (delay ...)` | `mcp.clj` | Done |
| H4 | Advertise `{:tools {}}` in capabilities | `mcp.clj` | Done |
| H5 | `stats` nil-conn guard | `core.clj` | Done |
| H6 | `forget` search index removal | `core.clj` | Done |
| H7 | Clean deps.edn (remove tools.cli, move nrepl) | `deps.edn` | Done |
| H8 | `/api/lookup` route | `api.clj` | Done |
| H9 | Plugin: `source`→`type`, remove fake hook | `__init__.py`, `plugin.yaml` | Done |
| H10 | Inline NixOS module in flake.nix | `flake.nix` | Done |
| H11 | Fix hermes-plugin lib dep | `flake.nix` | Done |

## Key Decisions

- **MCP protocol version `2025-11-25`** — current stable Streamable HTTP with sessions.
- **MCP for agent tools + JSON HTTP API for Hermes plugin** — not MCP for Hermes.
- **`get-env` wraps `System/getenv`** — enables test mocking via `with-redefs`.
- **`defonce system (delay ...)`** — single evaluation, no TOCTOU race on LMDB connections.
- **Inline NixOS module** — flake can reference `self.packages` instead of fragile `default = null`.
- **`:content/depends` canonical** — graph module reads this generically.
- **Incremental indexing** — `index-docs!` adds only new document IDs.
- **Randomized temp dirs per test** — LMDB is single-process, test dirs must not collide.

## Critical Context

### nREPL

```
setsid clojure -M:repl > /tmp/datom-nrepl.log 2>&1 &
ss -tlnp | grep 7888
fuser -k 7888/tcp
```

Port: **7888**

### MCP Server

```
setsid clojure -M:mcp 9090 > /dev/shm/datom.log 2>&1 &
ss -tlnp | grep 9090
fuser -k 9090/tcp
```

Port: **9090**

### JSON API Server

```
setsid clojure -M:api 9091 > /dev/shm/datom-api.log 2>&1 &
ss -tlnp | grep 9091
```

Port: **9091**

### Tests

```bash
# Clojure (16 tests, 91 assertions)
rm -rf /tmp/datom-*
clojure -M:test -d test

# Python (38 tests)
nix develop --command python3 -m pytest plugins/memory/datom/test_provider.py -v
```

### LMDB

- **Single-process**: only one JVM can hold a path at a time.
- **Paths**: Default `/tmp/datom-db` (datalog) and `/tmp/datom-search` (KV indices). Override via `DATOM_DB_DIR`/`DATOM_SEARCH_DIR`.
- **Clean slate**: `rm -rf /tmp/datom-db /tmp/datom-search`
- **Crash recovery**: stale `lock.mdb` files block reconnection. Manual cleanup needed.

### Dependencies

- **Datalevin**: `datalevin/datalevin {:mvn/version "0.10.18"}` on Clojars.
- **MCP toolkit**: git dep `com.noblepayne/mcp-toolkit` via `:mcp` alias.
- **Test deps**: `http-kit`, `cheshire`, `mcp-toolkit` in `:test` alias.

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
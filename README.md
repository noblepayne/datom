# datom

Composable agent memory — hybrid fulltext + vector search with graph neighbor expansion.

A Clojure memory server with a JSON HTTP API (port 9091) and an MCP server (port 9090). Ships with a [Hermes Agent](https://hermes-agent.nousresearch.com) memory provider plugin.

## Quick Start

```bash
# Start the server
clojure -M:mcp 9090

# Ingest test data
datom_ingest-luds path=test/fixtures/luds

# Search
datom_search query:"bech32"

# Context-aware search with graph expansion
datom_context query:"channel"
```

## Architecture

```
┌─────────────┐   MCP 9090    ┌──────────────┐
│  MCP Tools  │◄─────────────►│  datom.core  │
│  (9 tools)  │               │  (orchestrator)│
└─────────────┘               └──────┬───────┘
                                     │
┌─────────────┐   HTTP 9091          │
│ JSON API    │◄─────────────────────┤
│ (5 routes)  │                      │
└─────────────┘              ┌───────┴────────┐
                             │  datom.store   │
                             │  (Datalevin)   │
                             └────────────────┘
```

### Components

| Module | Purpose |
|--------|---------|
| `datom.core` | Orchestrator — remember, forget, search, answer, context, graph-expand, lookup, stats |
| `datom.store` | Schema, LMDB connections, CRUD |
| `datom.index` | Fulltext + vector indices, incremental index-docs!, full rebuild index! |
| `datom.query` | Hybrid search with RRF, filter-by-author |
| `datom.chunk` | Paragraph splitting with overlap |
| `datom.graph` | Link extraction, neighbors, dependents |
| `datom.ingest` | ContentSource protocol + LUDS adapter |
| `datom.mcp` | MCP server (Streamable HTTP, protocol 2025-11-25) |
| `datom.api` | JSON HTTP API (http-kit) |

## Hermes Plugin

```bash
# plugins/memory/datom/ — activated via memory.provider config
```

Provides 5 tools (`datom_search`, `datom_remember`, `datom_forget`, `datom_lookup`, `datom_stats`) plus `prefetch` (context injection) and `sync_turn` (conversation storage) hooks.

## Development

```bash
# Run Clojure tests
clojure -M:test -d test

# Run Python plugin tests
nix-shell -p python3Packages.pytest --run "python3 -m pytest plugins/memory/datom/test_provider.py -v"

# Start nREPL
clojure -M:repl
```

## Status

MVP phases A–G complete. See [`doc/HANDOFF.md`](doc/HANDOFF.md) for full plan.
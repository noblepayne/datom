# datom

Composable agent memory — hybrid fulltext + vector search with graph neighbor expansion.

A Clojure memory server with a JSON HTTP API (port 9091) and an MCP server (port 9090). Ships with a [Hermes Agent](https://hermes-agent.nousresearch.com) memory provider plugin.

## Nix

```bash
# Dev shell — all deps provided (JDK, Clojure, Python, clj-kondo, etc.)
nix develop

# Inside the dev shell:
test-clojure          # Run Clojure tests (16 tests, 91 assertions)
test-python           # Run Python plugin tests (35 tests)
test-all              # Run both
run-server [port]     # Start datom MCP server (default: 9090)
lock                  # Regenerate clj-nix deps-lock.json
build                 # nix build .

# Build the server
nix build .

# Build the Hermes plugin
nix build .#hermes-plugin

# Or without entering dev shell:
nix develop . --command test-clojure
nix develop . --command test-python
```

### NixOS module

```nix
{ config, pkgs, lib, datom, ... }: {
  imports = [ datom.nixosModules.default ];

  services.datom = {
    enable = true;
    apiPort = 9091;    # Hermes plugin connects here
    openFirewall = false;
  };

  # Wire into Hermes (loose coupling):
  services.hermes-agent = {
    enable = true;
    configFile = pkgs.writeText "hermes-config.yaml" (builtins.toJSON {
      # ... your Hermes config ...
      memory.provider = "datom";
    });
  };

  systemd.services.hermes-agent = {
    after = [ "datom.service" ];
    environment.HERMES_BUNDLED_PLUGINS =
      "${config.services.datom.hermesPlugin}/plugins";
  };
}
```

## Quick Start (without Nix)

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
# Primary (Nix): enter dev shell with all tools
nix develop

# Inside dev shell:
test-clojure          # 16 Clojure tests, 91 assertions
test-python           # 35 Python tests (+3 skip without httpx)
test-all              # both at once
run-server 9090       # start MCP server
lock                  # regenerate deps-lock.json
build                 # nix build .

# Or without Nix:
clojure -M:test -d test                                             # Clojure tests
nix-shell -p python3Packages.pytest --run "python3 -m pytest ..."   # Python tests
clojure -M:repl                                                      # nREPL on 7888
```

## Status

MVP phases A–G complete. See [`doc/HANDOFF.md`](doc/HANDOFF.md) for full plan.
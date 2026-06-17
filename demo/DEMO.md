# datom — Composable Agent Memory

## Overview

datom is a composable agent memory system built on Datalevin. It provides hybrid fulltext + vector search, document chunking, and relationship graphs — all through clean, composable primitives.

## Architecture

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
│  datom.index (fulltext + vector + RRF)               │
│  datom.chunk (paragraph splitting + overlap)         │
├──────────────────────────────────────────────────────┤
│  datom.store (schema, connections, CRUD)             │
├──────────────────────────────────────────────────────┤
│  Datalevin (datalog + LMDB + search + vector)        │
└──────────────────────────────────────────────────────┘
```

## Setup

```clojure
;; Start nREPL in project directory
clojure -M:repl

;; Or run from command line
clojure -M -m datom.core "search query"
```

## Quick Start

```clojure
(require '[datom.core :as datom])
(require '[datom.ingest.luds :as luds])

;; Initialize
(def sys (datom/store-init))

;; Ingest LUDS specs
(datom/ingest sys (luds/dir-source "/dev/shm/luds"))

;; Search
(datom/search sys "bech32 encoding")

;; Search with options
(datom/search sys "lightning" :top 3 :expand 1)

;; Human-readable results
(datom/answer sys "web-based auth")

;; Full context (search + graph)
(datom/context sys "channel Management")

;; Graph exploration
(datom/graph-expand sys "lud-6")

;; System stats
(datom/stats sys)
```

## API Reference

### Core Primitives

| Function | Description |
|----------|-------------|
| `(store-init)` | Initialize storage stack |
| `(ingest sys source)` | Ingest from ContentSource, chunk, index |
| `(search sys query)` | Hybrid fulltext + vector search |
| `(answer sys query)` | Search + human-readable string |
| `(context sys query)` | Search + graph expansion |
| `(graph-expand sys id)` | Full 1-hop neighborhood |
| `(lookup sys id)` | Pull document by id |
| `(stats sys)` | System statistics |
| `(compact sys)` | Consolidate low-importance chunks (Phase 2) |

### ContentSource Protocol

Any source that implements `ContentSource` can be ingested:

```clojure
(defprotocol ContentSource
  (source-id [source])      ;; unique ID for dedup
  (source-type [source])    ;; "doc", "rss", "youtube", "podcast", "session"
  (source-items [source]))  ;; seq of {:content/id :content/title :content/body :content/meta}
```

### Search Options

| Option | Default | Description |
|--------|---------|-------------|
| `:top` | 5 | Number of results |
| `:author` | nil | Filter by author |
| `:expand` | 0 | Expand top N results with graph neighbors |
| `:raw` | false | Include rank scores in results |

## Schema

```clojure
{content/id         ;; string, unique — "lud-6", "rss-feed-abc-123"
 content/type       ;; string — "doc", "rss", "youtube", "podcast", "session"
 content/title      ;; string, fulltext indexed
 content/body       ;; string, fulltext indexed
 content/meta       ;; map — source-specific metadata
 content/ts         ;; instant — ingest timestamp
 content/parent     ;; string — parent doc id (for chunks)
 content/chunk      ;; boolean — is this a chunk?
 content/tags       ;; vector of strings
 content/importance ;; number 0.0-1.0}
```

## Demo Queries

### 1. Basic Fulltext + Vector Search

```clojure
(datom/search sys "bech32 encoding")
;; → [{:id "lud-17", :title "LUD-17", :meta {:datom.ingest.luds/lud 17}}]
```

### 2. Author Filter

```clojure
(datom/search sys "auth" :author "fiatjaf")
;; → [{:id "lud-17", :title "LUD-17", ...}]
```

### 3. Graph Expansion

```clojure
(datom/search sys "channel Management" :expand 1)
;; Returns top result + all 1-hop neighbors
```

### 4. Full Context

```clojure
(datom/context sys "web-based auth")
;; → {:results [...], :neighbors {"lud-6" ["lud-1" "lud-2"]}}
```

### 5. System Stats

```clojure
(datom/stats sys)
;; → {:docs 23, :chunks 0, :total 23, :sources {"doc" 23}}
```

## Design Principles

- **Data in, data out** — pure functions, no side effects in core logic
- **Composable primitives** — 7 functions that compose, not a monolithic API
- **Source-agnostic** — same schema for docs, RSS, YouTube, podcasts, agent sessions
- **Adapter pattern** — feed-following is separate from memory management
- **Thin transport** — MCP, CLI, HTTP are adapters over the same core functions

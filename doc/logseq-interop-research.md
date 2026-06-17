# Logseq Interoperability Research Report

**Date:** 2026-06-07  
**Purpose:** Evaluate Logseq as a data source/sink for datom (Clojure search/knowledge system)

---

## Executive Summary

Logseq offers **two distinct data architectures** — a file-based markdown/org version and a newer SQLite-backed DB version. Both present concrete interop opportunities with datom, but through different pathways:

- **File-based version**: Direct markdown ingestion into datom (easiest path — Logseq files are just markdown with indented blocks)
- **DB version**: DataScript/Datalog-based, which shares the same conceptual model as datom's Datalevin backend (strongest long-term integration)
- **Plugin API**: JavaScript/TypeScript SDK for live integration (requires running Logseq instance)
- **MCP servers**: Several community-built bridges already exist for AI assistants

**Recommended first experiment:** Ingest a Logseq markdown graph directly into datom via a new `datom.ingest.logseq` adapter.

---

## 1. Data Model: Old vs New

### File-Based Version (Legacy/Stable)

Logseq stores everything as **markdown files** (or org-mode files) on disk. The key structural concepts:

| Concept | File-Based Representation |
|---------|---------------------------|
| **Page** | One `.md` file per page (e.g., `pages/My Topic.md`) |
| **Journal** | Date-named files in `journals/` folder (e.g., `journals/2026_06_07.md`) |
| **Block** | A bullet point (line starting with `- `) — the atomic unit |
| **Nesting** | Indentation creates parent-child relationships between blocks |
| **Page Reference** | `[[Page Name]]` wiki-links |
| **Block Reference** | `((block-uuid))` — links to specific blocks |
| **Properties** | Inline key-value pairs at top of page: `property:: value` |
| **Tags** | `#tag-name` syntax |
| **Backlinks** | Automatic — Logseq tracks all `[[references]]` to build the graph |

**File structure example:**
```
my-graph/
├── journals/
│   ├── 2026_06_07.md
│   └── 2026_06_06.md
├── pages/
│   ├── My Project.md
│   ├── Research Ideas.md
│   └── daily notes.md
├── assets/
└── logseq/
    └── config.edn
```

**Markdown format example:**
```markdown
- This is a block of text
  - This is a child block (indented)
  - Another child block
    - Grandchild block
- TODO Task item
- [[Page Reference]]
- Here's a ((block-ref))
- key:: value
  property:: definition
#tag-name
```

**Key insight for datom interop:** The markdown files are human-readable and parseable. Each block is a bullet point; nesting is whitespace-based. Properties are `key:: value` pairs. This is a straightforward parsing problem.

### DB Version (New/SQLite-backed)

The DB version uses **SQLite** as the backing store with a **DataScript** (Datalog) schema in the browser. This is architecturally much closer to datom's Datalevin approach.

| Concept | DB Version Representation |
|---------|---------------------------|
| **Node** | Unified entity — pages AND blocks are the same type |
| **Properties** | Typed, first-class entities with validation |
| **Tags** | Classes with inheritance, properties, hierarchical organization |
| **Storage** | SQLite database (not files) |
| **Query** | DataScript/Datalog queries |
| **Schema** | Namespaced keywords with `:db/type`, `:db/cardinality`, etc. |

**Key DataScript attributes (from source):**
```clojure
:block/uuid      ; Unique block identifier (:db.unique/identity)
:block/parent    ; Parent block reference (:db.type/ref)
:block/page      ; Page block belongs to (:db.type/ref)
:block/refs      ; Referenced blocks (:db.type/ref, cardinality many)
:block/tags      ; Block tags/classes (:db.type/ref, cardinality many)
:block/order     ; Fractional index for ordering
:block/name      ; Lowercase page name
:block/title     ; Display title
:block/journal-day ; Journal date
:file/path       ; File system path (:db.unique/identity)
```

**Key difference:** The DB version unifies pages and blocks into a single "node" entity type. Properties are typed (string, number, checkbox, datetime, node, etc.) and validated via Malli schemas.

**Migration path:** Logseq provides a `graph-parser` library that converts file-based graphs to DB graphs. The exporter handles timestamp injection, template processing, and asset management.

---

## 2. APIs and Access Methods

### Plugin API (JavaScript/TypeScript)

Logseq exposes a comprehensive plugin API via the `@logseq/libs` SDK. This runs **inside the Logseq Electron app** in a sandboxed iframe.

**Available namespaces:**

| Namespace | Purpose | Key Functions |
|-----------|---------|---------------|
| `logseq.Editor` | Block/page manipulation | `insertBlock`, `getBlock`, `getCurrentPage`, `createPage`, `deletePage` |
| `logseq.DB` | Database queries | `q`, `datascriptQuery`, `customQuery` |
| `logseq.App` | Application-level | `getAppInfo`, `getUserConfigs`, `registerPluginSlashCommand` |
| `logseq.FileStorage` | File system access | Read/write files in the graph |

**Critical limitation:** The plugin API requires Logseq's GUI to be running. There is no headless/server mode. However, there is a local HTTP API server that wraps plugin APIs.

### Local HTTP API

Logseq can expose a local HTTP server that proxies plugin API calls. This enables external tools to:
- Create/read/update pages
- Query the database
- Insert/update blocks

**Limitation:** Still requires Logseq desktop app running.

### nbb-logseq (ClojureScript Scripting)

The most interesting integration point for datom:

- **`nbb-logseq`**: A custom version of [nbb](https://github.com/babashka/nbb) (ClojureScript on Node.js) with DataScript support
- Can **query any Logseq graph** from the command line
- For DB graphs, can also **write** (not just read)
- Example: Scripts in `deps/db/script/` show querying, validating, and creating graphs
- Example: `deps/outliner/script/transact.cljs` — modify nodes from commandline

**This is the strongest interop path for a Clojure/ClojureScript system like datom.**

### File System Access

The simplest approach: Logseq graphs are just directories of markdown files. Any tool that can read the filesystem can access the data.

### Export Formats

| Format | Description | Use Case |
|--------|-------------|----------|
| **SQLite DB** | Raw database file | Direct database access (DB version) |
| **EDN File** | Editable data format | Clojure-native data sharing |
| **Standard Markdown** | Plain markdown export | Interoperability |
| **Debug Transit** | Debug format | Troubleshooting |

---

## 3. File Format Details (Markdown Version)

### Block Structure

Every block in Logseq is a bullet point. The hierarchy is defined by indentation (typically 2 spaces per level):

```markdown
- Root block
  - Child block level 1
    - Child block level 2
      - Child block level 3
  - Another child
```

### Properties

Properties appear as `key:: value` pairs, typically at the top of a page or as child blocks:

```markdown
- title:: My Page Title
- author:: wes
- tags:: #research #clojure
- date:: 2026-06-07
```

**Supported property types (file version):**
- String (default)
- Number
- Date
- Checkbox (`true`/`false`)

### References

- **Page references**: `[[Page Name]]` — creates bidirectional link
- **Block references**: `((uuid-here))` — embeds/references specific block
- **External links**: `[text](url)` — standard markdown links
- **Embeds**: `{{embed [[Page Name]]}}` or `{{embed ((block-ref))}}`

### Tags

Tags are inline with `#tag-name` syntax. They create implicit pages.

### Journal Format

Journal entries go in `journals/YYYY_MM_DD.md` with daily notes as bullet lists.

---

## 4. New DB Version Technology

### Underlying Stack

| Layer | Technology |
|-------|-----------|
| **Storage** | SQLite (file-based, single `.sqlite` file) |
| **In-memory** | DataScript (Datalog database in ClojureScript) |
| **Query** | Datalog (DataScript queries) |
| **Validation** | Malli schemas |
| **Sync** | RTC (Real-Time Collaboration) protocol |
| **Frontend** | ClojureScript (re-frame/reagent) |

### How It Differs from File Version

| Aspect | File Version | DB Version |
|--------|-------------|------------|
| **Storage** | Markdown files on disk | SQLite database file |
| **Entity model** | Pages ≠ Blocks | Unified "node" type |
| **Properties** | `key:: value` text | Typed, validated entities |
| **Tags** | `#tag` inline | Classes with inheritance |
| **Queries** | Advanced Datalog queries | Same Datalog engine |
| **Backlinks** | File-based tracking | Database relationships |
| **Performance** | File I/O per page | Single SQLite file |

### DataScript Schema (DB Version)

The DB version uses a DataScript schema with namespaced keywords — very similar to datom's schema approach:

```clojure
;; Logseq DB version schema (simplified)
{:block/uuid      {:db/unique :db.unique/identity}
 :block/parent    {:db/valueType :db.type/ref :db/index true}
 :block/page      {:db/valueType :db.type/ref :db/index true}
 :block/refs      {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
 :block/tags      {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
 :block/order     {:db/index true}
 :block/name      {:db/index true}
 :block/title     {:db/index true}}

;; datom schema (for comparison)
{:content/id         {:db/unique :db.unique/identity}
 :content/type       {:db/index true}
 :content/title      {:db/fulltext true}
 :content/body       {:db/fulltext true}
 :content/meta       {}
 :content/ts         {:db/index true}
 :content/parent     {}
 :content/chunk      {:db/index true}
 :content/tags       {}
 :content/importance {}}
```

**Key observation:** Both systems use Datalevin/DataScript with namespaced keywords. The schema shapes are different but conceptually aligned. A translation layer is feasible.

---

## 5. Concrete Interop Possibilities

### Path A: File-Based Markdown Ingestion (Easiest)

**Direction:** Logseq → datom

**Approach:** Create a `datom.ingest.logseq` adapter that:
1. Reads `.md` files from a Logseq graph directory
2. Parses blocks (bullet points with indentation)
3. Extracts properties (`key:: value`)
4. Extracts page references (`[[...]]`) and block references (`((...))`)
5. Creates datom content items with appropriate metadata

**Implementation sketch:**
```clojure
(ns datom.ingest.logseq
  (:require [datom.ingest :as ingest]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(defn- parse-logseq-block [line indent-level]
  ;; Parse a single bullet line into a block map
  (let [cleaned (str/replace line #"^[\s-]+" "")]
    {:text cleaned
     :indent indent-level}))

(defn- parse-logseq-file [^java.io.File f]
  ;; Parse a Logseq markdown file into blocks
  (let [lines (str/split-lines (slurp f))
        blocks (mapv parse-logseq-block lines)]
    {:file (.getName f)
     :blocks blocks
     :page-name (first (str/split (.getName f) #"\.md$"))}))

(defn dir-source [dir]
  (reify ingest/ContentSource
    (source-id [_] (str "logseq:" dir))
    (source-type [_] "logseq")
    (source-items [_]
      ;; Read all .md files, parse into content items
      ...)))
```

**What gets extracted:**
- Page content as `:content/body`
- Block hierarchy via `:content/parent`
- Properties as `:content/meta`
- References for link graph
- Tags as `:content/tags`

### Path B: EDN Export Import

**Direction:** Logseq → datom

Logseq can export to EDN format, which is native Clojure data. This could be:
1. Export Logseq graph as EDN
2. Parse EDN into datom content items
3. Ingest with full structure preserved

### Path C: nbb-logseq Scripting Bridge

**Direction:** Bidirectional

Use `nbb-logseq` to:
1. Query Logseq graph from command line
2. Transform results into datom format
3. Ingest into datom

This works because both systems speak Datalog.

### Path D: Direct SQLite Access (DB Version)

**Direction:** Logseq → datom

For DB version graphs:
1. Open the SQLite file directly (it's just a file)
2. Read the DataScript entities
3. Transform and ingest into datom

**Caveat:** The SQLite schema is internal and may change between versions.

### Path E: Plugin API Bridge

**Direction:** Bidirectional (requires running Logseq)

A datom plugin could:
1. Listen for graph changes via plugin hooks
2. Sync new/modified blocks into datom
3. Query datom and write results back to Logseq

### Path F: MCP Server Integration

**Direction:** Bidirectional

Several MCP servers already exist:
- [mcp-logseq](https://github.com/ergut/mcp-logseq) — Python-based, reads/writes via HTTP API
- [logseq-api-mcp](https://github.com/gustavo-meilus/logseq-api-mcp) — Uses Logseq API
- [graphthulhu](https://www.reddit.com/r/logseq/comments/1qpt7m1/) — Full graph exposure

datom could expose its search as an MCP tool, and Logseq MCP servers could feed data into datom.

---

## 6. Existing Bridges and Integrations

| Tool | Type | Direction | Notes |
|------|------|-----------|-------|
| [nbb-logseq](https://github.com/logseq/nbb-logseq) | CLI/Scripting | Bidirectional | ClojureScript, DataScript support |
| [logseq-query](https://github.com/cldwalker/logseq-query) | CLI | Logseq → | Datalog queries from command line |
| [mcp-logseq](https://github.com/ergut/mcp-logseq) | MCP Server | Bidirectional | Python, HTTP API bridge |
| [logseq-api-mcp](https://github.com/gustavo-meilus/logseq-api-mcp) | MCP Server | Bidirectional | Uses Logseq API |
| [graph-parser](https://github.com/logseq/logseq/tree/master/deps/graph-parser) | Library | Bidirectional | Logseq's own import/export |
| [logseq-integrate-any-api](https://github.com/eefahd/logseq-integrate-any-api) | Plugin | External → Logseq | Call APIs, insert responses |

---

## 7. Recommended Approach for First Integration Experiment

### Phase 1: Markdown Ingestion (Week 1)

**Goal:** Ingest a real Logseq graph into datom and verify search works.

1. **Create `datom.ingest.logseq` adapter** following the `luds` pattern:
   - Parse markdown files from `pages/` and `journals/`
   - Extract block hierarchy from indentation
   - Parse `key:: value` properties
   - Extract `[[page references]]` and `((block refs))`
   - Map to datom's `:content/*` schema

2. **Test with a small graph** (10-20 pages):
   ```clojure
   (require '[datom.ingest.logseq :as logseq])
   (def sys (datom/store-init))
   (datom/ingest sys (logseq/dir-source "/path/to/logseq-graph"))
   (datom/search sys "clojure")
   ```

3. **Verify:**
   - Pages chunk correctly
   - Properties preserved in metadata
   - Reference graph captured
   - Search returns relevant results

### Phase 2: Property Mapping (Week 2)

**Goal:** Map Logseq properties to datom metadata.

- `title::` → `:content/title`
- `tags::` → `:content/tags`
- Custom properties → `:content/meta` map
- Block references → graph edges

### Phase 3: Bidirectional Sync (Future)

**Goal:** Write datom results back to Logseq.

Options:
- Write to Logseq's markdown files directly (simplest)
- Use nbb-logseq for DB version graphs
- Build a Logseq plugin that pulls from datom

---

## 8. Key Technical Considerations

### Schema Mapping

| Logseq Concept | datom Concept | Notes |
|----------------|---------------|-------|
| Page | `:content/type "logseq-page"` | File name = page name |
| Block | `:content/id "logseq-block-<uuid>"` | Indentation = hierarchy |
| Property | `:content/meta` | key-value pairs |
| Reference | Graph edges | `[[page]]` → link |
| Tag | `:content/tags` | `#tag` → set entry |

### Chunking Strategy

Logseq blocks are typically short (1-3 sentences). Options:
- **One block = one chunk** (preserves block structure)
- **One page = one chunk** (simpler, loses block granularity)
- **Hierarchical chunks** (parent block + children as one chunk)

**Recommendation:** Start with one page = one chunk, then refine based on search quality.

### Performance

Logseq graphs can be large (10k+ pages). The file-based approach scales linearly with file count. The DB version would require SQLite access patterns.

### Conflict Resolution

If both systems write to the same data:
- File-based: Last-write-wins on markdown files
- DB version: Requires conflict resolution strategy

**Recommendation:** Start with one-way sync (Logseq → datom) to avoid conflicts.

---

## 9. Sources

- [Logseq GitHub Repository](https://github.com/logseq/logseq)
- [DB Graph System Documentation](https://deepwiki.com/logseq/docs/3.2-db-graph-system)
- [Extension and Integration](https://deepwiki.com/logseq/logseq/6-extensions-and-integration)
- [Plugin System](https://deepwiki.com/logseq/logseq/6.1-plugin-system)
- [Plugin API Docs](https://plugins-doc.logseq.com/)
- [@logseq/libs SDK](https://logseq.github.io/plugins/)
- [nbb-logseq](https://github.com/logseq/nbb-logseq)
- [logseq-query](https://github.com/cldwalker/logseq-query)
- [mcp-logseq](https://github.com/ergut/mcp-logseq)
- [Logseq DB Version Docs](https://github.com/logseq/docs/blob/master/db-version.md)
- [Database Schema and Validation](https://deepwiki.com/logseq/logseq/4.2-database-schema-and-validation)
- [Graph Import and Export](https://deepwiki.com/logseq/logseq/6.2-graph-import-and-export)
- [Community Discussion: Feeding Data to Logseq](https://discuss.logseq.com/t/how-to-feed-data-to-logseq-from-external-application/14430)
- [Community Discussion: MCP Server for Logseq](https://discuss.logseq.com/t/mcp-server-for-logseq/32004)

---

## 10. Conclusion

Logseq and datom share conceptual DNA — both are Clojure/ClojureScript systems built on Datalog databases with namespaced keyword schemas. The interoperability story is strong:

1. **Immediate wins**: File-based markdown ingestion is straightforward — Logseq files are well-structured and human-readable
2. **Medium-term**: EDN export and nbb-logseq scripting provide Clojure-native integration paths
3. **Long-term**: DB version's DataScript schema aligns closely with datom's Datalevin schema — a translation layer is natural

The recommended first experiment — a `datom.ingest.logseq` adapter — is low-risk, high-value, and validates the integration thesis before committing to deeper work.

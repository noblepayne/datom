# Logseq DB Version — Deep Dive Report

**Date**: June 7, 2026  
**Status**: DB version is actively shipping (stable release). The old file-based version has been split off to `logseq/og` as a separate project.

---

## Executive Summary

Logseq has fundamentally rearchitected from a file-based outliner to a database-first knowledge graph. The "DB version" is now the primary product — not experimental. The old Markdown file-based version lives in a separate repo (`logseq/og`) and is no longer the main development focus.

**One-line thesis**: Logseq DB is a SQLite-backed, Datalog-queryable knowledge graph with typed properties, RTC sync, and a plugin API — but it trades filesystem transparency for structured data power.

---

## 1. MCP (Model Context Protocol) Server

### Status: Community-built, not official

**There is NO official Logseq MCP server.** However, multiple community implementations exist:

| Project | Author | Language | Status |
|---------|--------|----------|--------|
| [jimsynz/logseq-mcp-server](https://github.com/jimsynz/logseq-mcp-server) | James Hartley | Rust (Cargo) | Most complete — 13 MCP tools |
| [eugeneyvt/logseq-mcp-server](https://github.com/eugeneyvt/logseq-mcp-server) | Eugene | — | Alternative implementation |
| logseq-mcp (AI tool) | Various | Python 3.11+ | Listed on mcp.aibase.com |

**How it works**: The community MCP servers bridge to Logseq's **built-in HTTP API server** (not the SQLite DB directly). You enable "HTTP APIs Server" in Logseq Settings → Features, set an auth token, and the MCP server talks to `http://localhost:12315`.

**Available MCP tools** (via jimsynz implementation):
- Page management: `list_pages`, `get_page`, `get_page_content`, `create_page`, `get_current_page`
- Block operations: `get_block`, `create_block`, `update_block`, `get_current_block`
- Search & query: `search`, `datascript_query`
- App info: `get_current_graph`, `get_user_configs`, `get_state_from_store`

**Key limitation**: The MCP server requires Logseq desktop app to be running with the HTTP API enabled. It cannot connect to the SQLite file directly.

**Sources**:
- https://github.com/jimsynz/logseq-mcp-server
- https://discuss.logseq.com/t/whats-new-with-logseq-db-may-16th-2026/35020

---

## 2. Markdown Files on Disk — The "Markdown Mirror"

### Status: SHIPPED (May 2026) — opt-in per graph

**Short answer**: DB graphs live in SQLite, but there's now a **Markdown Mirror** feature.

**How it works**:
- When enabled on a graph, Logseq writes a **plain markdown projection** of your blocks to a folder on disk
- Each block gets a stable ID embedded in a comment for cross-reference
- Path collisions are resolved automatically
- Renaming a page cleans up its old mirror file
- **One-way sync**: DB → Markdown (the two-way version is in active development on a feature branch, NOT shipped yet)

**What you can do with the mirror**:
- Run `grep`, `ripgrep`, or any markdown tool
- Back up with `git`
- Open in another editor (Obsidian, VS Code, etc.)

**What you can't do (yet)**:
- Edit the markdown files and have changes flow back into the DB (two-way sync is WIP)
- The mirror is a read-only projection, not a live filesystem interface

**Opt-in**: Per-graph, so you can enable it for a single project.

**The old file-based version**: Lives in `logseq/og` on GitHub. It's a separate codebase now — the DB version and file version are **not** the same app with a toggle. They're diverging products.

**Sources**:
- https://discuss.logseq.com/t/whats-new-with-logseq-db-may-16th-2026/35020
- https://github.com/logseq/docs/blob/master/db-version-changes.md

---

## 3. Data Store Architecture

### Status: SQLite + DataScript/Datalog

**Primary store**: SQLite (`db.sqlite`)

**Graph directory structure**:
```
~/logseq/graphs/GRAPH-NAME/
├── db.sqlite      # All graph data (blocks, pages, properties, configs)
└── assets/        # Images, PDFs, etc.
```

**Query layer**: DataScript (Datalog queries)

- The DB version uses **Datalog** for advanced queries (not SQL directly)
- Simple queries are created via `/Query` command
- Advanced queries use Datalog syntax in code blocks
- The MCP server exposes a `datascript_query` tool

**Schema highlights**:
- Blocks and pages are unified as **nodes** (no more `(())` block refs — everything uses `[[]]`)
- Properties are typed (string, number, boolean, date, node references, assets)
- All nodes have `created-at` and `updated-at` timestamps
- Tags are first-class (not just `#tag` inline syntax)
- Classes (like `#Task`, `#Journal`) have hierarchy support

**Key attribute changes from file version**:
- `:block/content` → `:block/title`
- `:block/original-name` → `:block/title`
- `:block/journal?` → `[?p :blocks/tags :logseq.class/Journal]`
- `:block/left` → `:block/order`
- `:block/path-refs` → `(has-ref ?b ?ref)`

**SQLite optimizations enabled**:
- WAL (Write-Ahead Logging) mode
- PRAGMA settings tuned for performance
- WAL for all SQLite databases (as of Nov 2024)

**Sources**:
- https://github.com/logseq/docs/blob/master/db-version-changes.md
- https://github.com/logseq/docs/blob/master/db-version.md

---

## 4. Plugin API

### Status: SHIPPED — enhanced for DB graphs

**The plugin API exists and is actively maintained.** Key changes for DB graphs:

**Core APIs available**:
- `logseq.Editor` — block/page CRUD
- `logseq.DB` — database queries
- `logseq.App` — app state
- `logseq.Git`, `logseq.UI`, `logseq.Assets`, `logseq.FileStorage`

**DB-specific enhancements**:
- Property-related calls work with DB graphs (properties are namespaced to `:plugin.property._api`)
- Properties can now be numbers and booleans (not just strings)
- `DB.onChanged` event for real-time database change notifications
- Custom block renderer — plugins can render their own block UI inside the editor
- Plain JS objects over Electron IPC (no more manual serialization)

**Plugin marketplace**:
- Search matches descriptions (3+ chars)
- Plugin thumbnails for visual scanning
- "Uninstall unused plugins" flow
- Security: Only "no effect" plugins work from web; effect plugins require certification

**Key documentation**:
- Plugin API docs: https://plugins-doc.logseq.com/
- DB plugin skill: https://github.com/kerim/logseq-db-plugin-api-skill

**Differences from file version plugins**:
- Tag/class detection is multi-layered (DB has proper classes, not just inline tags)
- Property dereferencing requires understanding typed values
- Advanced Datalog queries available via the API
- Some file-version plugins won't work without adaptation

**Sources**:
- https://discuss.logseq.com/t/whats-new-with-logseq-db-may-16th-2026/35020
- https://github.com/logseq/docs/blob/master/db-version-changes.md

---

## 5. Direct Database Access from Outside Logseq

### Status: Limited — SQLite file exists but is not designed for external editing

**The SQLite file is at**: `~/logseq/graphs/GRAPH-NAME/db.sqlite`

**Can you read it?** Yes — it's a standard SQLite file. You can open it with any SQLite client.

**Should you write to it directly?** **No.** Logseq owns the DB and expects to be the only writer. Direct writes risk corrupting the graph.

**Official guidance**: The team discourages direct DB manipulation. The recommended paths for external access are:

1. **Logseq CLI** (`logseq qmd query`) — run Datalog queries from the command line
2. **HTTP API** (localhost:12315) — REST-like API when desktop app is running
3. **MCP server** — for AI assistant integration
4. **Markdown Mirror** — read-only filesystem projection

**What about the CLI?** The CLI is the closest thing to "direct access":
```bash
logseq qmd query        # Run Datalog queries from terminal
logseq graph list       # List graphs
logseq graph export     # Export graph data
logseq graph import     # Import graph data
```

The CLI ships with desktop releases and can run headlessly.

**Self-hosted sync**: There's a community project (`bcspragu/logseq-sync`) and an official self-hosting guide, but these are for sync — not for programmatic DB access.

**Sources**:
- https://discuss.logseq.com/t/whats-new-with-logseq-db-may-16th-2026/35020
- https://medium.com/@4shutosh/how-to-self-host-logseq-db-graph-sync-d62d589f06a4

---

## 6. Export/Import Formats

### Status: SHIPPED — multiple formats supported

**Export formats**:
- **EDN** (Extensible Data Notation) — native Clojure format, preserves full graph structure
- **JSON** — for interoperability
- **Markdown** — via the Markdown Mirror feature (one-way, DB→files)
- **Graph export via CLI**: `logseq graph export`

**Import formats**:
- **EDN** — full graph import with all properties and structure
- **JSON** — with property and tag handling
- **Markdown/Org-mode** — import existing file-based notes (from file graphs or other tools)
- **File graph import** — migrate from the old file-based Logseq version

**Key import capabilities**:
- Import multiple file graphs with `--continue` flag to see all errors
- Handles classes, properties, and tags during import
- Journal references are remapped during import
- Namespace hierarchies are preserved

**What's NOT supported**:
- Direct import from Obsidian vault (you'd need to convert to Markdown first)
- CSV/Excel import (would need a plugin or intermediate conversion)

**Sources**:
- https://discuss.logseq.com/t/logseq-db-changelog/30013
- https://github.com/logseq/docs/blob/master/db-version-changes.md

---

## 7. Programmatic Access (REST API, WebSocket, etc.)

### Status: SHIPPED — HTTP API + WebSocket for sync

**HTTP API (built-in)**:
- Runs on `http://localhost:12315` when enabled in Settings → Features
- Token-based authentication
- Endpoints for pages, blocks, search, Datascript queries
- Auto-start option with app launch

**WebSocket API**:
- Used for real-time sync (RTC — Real-Time Collaboration)
- Handles multi-device editing with conflict resolution
- Stale sessions restart cleanly; in-flight uploads clear on reconnect

**CLI (scriptable)**:
```bash
logseq qmd query "your-datalog-query"
logseq sync asset download
logseq graph create --enable-sync
logseq graph list --output json
```

**No formal REST API documentation**: The HTTP API is documented via the MCP server README and community resources, but there's no official OpenAPI/Swagger spec.

**Agent/automation friendly**: The CLI is explicitly designed for driving Logseq from scripts, cron jobs, CI, or AI agents without the GUI.

**Sources**:
- https://github.com/jimsynz/logseq-mcp-server
- https://discuss.logseq.com/t/whats-new-with-logseq-db-may-16th-2026/35020

---

## 8. Syncing Architecture

### Status: SHIPPED — official sync + self-hosted option

**Official Logseq Sync**:
- Paid feature (requires account)
- Uses AWS Cognito for auth (JWT tokens)
- E2E encrypted (passphrase-encrypted keys)
- Assets are synced separately from graph data
- WebSocket-based real-time sync
- Multi-device editing with conflict resolution (latest write wins)

**Self-hosted sync** (beta as of early 2026):
- Requires: AWS Cognito pool (free tier covers 50k MAU), your own server
- Server: Node.js adapter running on PM2
- Uses S3-compatible storage for encrypted blobs
- Official guide: https://medium.com/@4shutosh/how-to-self-host-logseq-db-graph-sync-d62d589f06a4

**Community self-hosted sync** (`bcspragu/logseq-sync`):
- Pre-alpha, open-source Go implementation
- Mostly implemented API surface
- Requires modified Logseq client
- Status: Stalled on official buy-in

**Third-party sync options** (for file graphs only, NOT DB graphs):
- Syncthing, iCloud, Dropbox — these sync markdown files, not SQLite DBs
- **Not applicable to DB graphs** — the SQLite file can't be safely synced via file sync tools

**What sync does NOT support**:
- Real-time collaboration (multi-user editing the same page simultaneously) — this is planned but not shipped
- Offline-first with automatic merge (it's last-write-wins, not CRDT-based merging)

**Sources**:
- https://discuss.logseq.com/t/whats-new-with-logseq-db-may-16th-2026/35020
- https://github.com/bcspragu/logseq-sync
- https://medium.com/@4shutosh/how-to-self-host-logseq-db-graph-sync-d62d589f06a4

---

## Summary: What's Real vs What's Planned

| Feature | Status | Notes |
|---------|--------|-------|
| SQLite storage | **SHIPPED** | Primary data store for DB graphs |
| DataScript queries | **SHIPPED** | Datalog syntax, available via UI and CLI |
| Markdown Mirror | **SHIPPED** | One-way (DB→files), two-way is WIP |
| HTTP API | **SHIPPED** | localhost:12315, token auth |
| CLI (`logseq qmd`) | **SHIPPED** | Full-featured, ships with desktop |
| Plugin API (DB) | **SHIPPED** | Enhanced with typed properties, DB.onChanged |
| Export (EDN/JSON/MD) | **SHIPPED** | Multiple formats |
| Import (EDN/JSON/MD) | **SHIPPED** | From file graphs and other tools |
| Official Sync | **SHIPPED** | Paid, E2E encrypted, WebSocket |
| Self-hosted sync | **BETA** | Requires Cognito + your server |
| MCP server | **COMMUNITY** | Not official, works via HTTP API |
| Real-time collab | **PLANNED** | Not shipped yet |
| Two-way Markdown Mirror | **WIP** | On feature branch |
| Whiteboards | **REMOVED** | Will be a plugin |
| Org-mode support | **REMOVED** | Markdown only |

---

## Key Takeaways

1. **The DB version is the future** — file-based Logseq is a separate project (`logseq/og`) and not the primary development focus
2. **No direct SQLite editing** — use the CLI, HTTP API, or MCP server instead
3. **Markdown Mirror is the bridge** — but it's one-way for now; two-way is in development
4. **MCP integration exists** — but it's community-built, not official, and requires the desktop app running
5. **Sync has a self-hosted path** — but it's beta and requires AWS Cognito setup
6. **The plugin API is actively evolving** — DB-specific APIs are documented but moving fast

---

*Report compiled from: Logseq docs (GitHub), discuss.logseq.com, community MCP servers, Medium guides, and LobeHub skills marketplace.*

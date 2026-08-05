# SPEC-01 — First Swing: Lock Recovery + Memory Lifecycle (TTL/Expiry)

**Date:** 2026-08-05
**Status:** DRAFT — pending adversarial review
**Scope:** M1.2 (LMDB lock recovery) + M2.2 (TTL & expiry on auto-memories), per ROADMAP.md audited sequence (`M1.2 → M2.2 → M1.9 → M3.1`).
**Repo:** datom, branch `feat/mvp`. Conventions: cljfmt fix + clj-kondo --lint before every commit; tests green always; one snapshot commit per logical change; no comments unless necessary; namespaced keywords.

---

## Research Findings

**What exists / what we verified against source (2026-08-05):**

| Topic | Finding | Source |
|---|---|---|
| Service crash-loop risk | `Restart="on-failure"`, `RestartSec="10s"`, `ProtectSystem="strict"`, `StateDirectory="datom"`, `ReadWritePaths=[dataDir]` | flake.nix:167-185 |
| Graceful shutdown | `close!` closes LMDB + datalog conns; normal restarts are clean. Stale locks only after SIGKILL/OOM-kill | store.clj `close!` |
| Lock file | LMDB `lock.mdb` beside `data.mdb` in `dataDir`; search indices at `${dataDir}/search` also LMDB (`dl/open-kv`) — **exact lock filename for datalevin 0.10.18 must be confirmed during implementation** (strace or datalevin source; likely `lock.mdb`) | store.clj:28 |
| `remember` signature | Accepts `:id :title :body :type :tags :importance`; defaults importance 0; sets `:content/ts (Instant/now)` | core.clj:114-126 |
| `sync_turn` | POSTs `/api/remember` with `body` = "User: …\n\nAssistant: …", `type: "conversation"`, **no ttl, no importance** → every auto-memory is importance 0, no expiry | plugin `__init__.py:198-215` |
| `forget` | Removes doc + chunk children from datalog + both search indices (`remove-doc`, `remove-vec`) — **reusable for expiry** | core.clj:130-147 |
| Schema | `:content/ttl`, `:content/expires-at` do NOT exist; `:content/importance` exists but unused by sync_turn | store.clj:6-17 |
| Search/query | `POST /api/search` requires `:query`; no query-less top-N path (M3.1 concern, NOT this swing) | api.clj:25 |
| Stats | `GET /api/stats` exists | api.clj:22 |

**Design decisions already made (from roadmap/audit):**
- Expiry reuses `forget`'s index cleanup (audit verified).
- `type=conversation` auto-memories get a conservative default TTL (30 days).
- **Backfill required:** existing `type=conversation` docs (no ttl) must get a default TTL on the first expiry pass — otherwise the accrued pile stays eternal.
- Lock cleanup runs **in-code at startup** (Option A). `ProtectSystem=strict` / `ReadWritePaths` is NOT a constraint at MVP stage (Wes: "not essential, we can open up perms if we want") — but Option A needs no permission changes at all: the datom user can write `dataDir`, and cleanup happens before LMDB opens, so no lock is held. No systemd `ExecStartPre`, no NixOS rebuild. Simpler wins.
- M1.2 is insurance against a rare tail event (SIGKILL/OOM), not ongoing degradation — keep the fix minimal.

---

## Core Principles

1. **Memory must expire.** An uninspectable, uncorrectable, nonexpiring agent-written memory is hidden prompt debt. The #1 community warning (r/ClaudeCode: "if a memory cannot be inspected, corrected, expired, or tied back to why it was saved, it will eventually become hidden prompt debt").
2. **Stop the bleeding before the feature.** The store grows every session (sync_turn); the tourniquet comes before the demo feature (M3.1).
3. **Reuse, don't rebuild.** Expiry = `forget` with a predicate. No new index machinery.
4. **Server-side defaults, not plugin changes.** `sync_turn` stays dumb; `remember` enforces type-based TTL defaults.
5. **Backfill is part of the fix.** "Stop the bleeding" must apply to the accrued pile too.

---

## M1.2 — LMDB lock recovery (S, ~30-45 min)

### Problem
SIGKILL/OOM-kill leaves a stale LMDB `lock.mdb`; `Restart=on-failure` + `RestartSec=10s` may then loop forever on a headless box. Normal restarts are clean (`close!`), so this is a rare tail risk — but the premise is **partially unverified**: lock.mdb persists after *every* clean close (verified 2026-08-05), so existence ≠ staleness; and POSIX fcntl locks die with the process, so reopen after SIGKILL is *expected to succeed*. **M1.2 may collapse to a verification + doc note.**

### Implementation
**Step 0 (hard gate — do this FIRST): SIGKILL test on lattice.** `systemctl kill -s KILL datom` → `systemctl start datom` → verify `ss -tlnp | grep 9090` + `/api/stats`. Expected: reopen succeeds (fcntl locks die with the process; lock file re-initialized on open). If it succeeds, **M1.2 is DONE** — add a doc note + an ops runbook line; no code. If the loop is real, the fix is more likely the *search* dir or txlog recovery than lock.mdb deletion — diagnose before writing cleanup code.

**If Step 0 fails (loop is real): Option A — in-code startup cleanup.** At `store` init (store.clj `ensure-conn!` / `store`), before opening LMDB: if `lock.mdb` exists in `dataDir` (and `${dataDir}/search`), remove it. Simplest — no NixOS change, runs as datom user (has write access), idempotent (no lock is held yet at startup). `ProtectSystem=strict` is a non-constraint (Wes 2026-08-05); `ExecStartPre` runs as datom too (User= applies to it), so either path has write access.

### Acceptance
1. `kill -9` the datom service → restart succeeds without manual intervention.
2. Cleanup step (if any code landed) is idempotent and safe when no lock file exists.
3. Graceful restarts still work (no regression).
4. Both `${dataDir}` and `${dataDir}/search` lock dirs covered in the kill -9 test (both confirmed to hold lock.mdb).

### Test plan
- SIGKILL test on lattice (manual, one-time) — **the gate**.
- If code lands: unit test that cleanup is a no-op when no lock file exists.

---

## M2.2 — TTL & expiry on auto-memories (S→M, ~2-3 hr)

### Problem
`sync_turn` writes every conversation turn as `type=conversation` with no expiry. Store grows unbounded; retrieval noise + index bloat + hidden prompt debt.

### Implementation

**1. Schema (store.clj):** add
```clojure
:content/expires-at {:db/index true}
```
Stored as `java.time.Instant` (nil = never expires). **WARNING (verified empirically 2026-08-05, datalevin 0.10.18): do NOT set `:db.valueType :db.type/instant` — datalevin stores `java.util.Date` for that type and `ClassCastException: Instant cannot be cast to java.util.Date` throws on the very first `remember` with a TTL. Keep `{:db/index true}` exactly, mirroring `:content/ts`, which round-trips `Instant` correctly without a valueType.**

**Schema evolution on existing DBs:** `ensure-conn!` for an existing dir takes the `conn-from-db (dl/db dir)` path (store.clj:21) which does **not** merge the schema map. Two viable paths: rely on datalevin's auto-registration of unknown attrs on first transact (how `:content/ts` behaves), or call `dl/update-schema conn {:content/expires-at {:db/index true}}` in `store` init. Verify one line at implementation; both work in 0.10.18.

**2. `remember` (core.clj:114-126):** accept optional `:ttl` (seconds). When provided (or when a type-based default applies), set `:content/expires-at = (+ now ttl)`.
- **Type-based default:** `type=conversation` → `DEFAULT_CONVERSATION_TTL = 30 days` (configurable via env `DATOM_CONVERSATION_TTL_DAYS`, default 30). Other types: no default (explicit ttl only).

**3. `expire!` (new, core.clj):** find docs where `:content/expires-at` < now, call `forget` on each (reuses datalog + index cleanup). Returns `{:expired n}`.
- Pure-ish: takes `sys`, returns count. Testable with synthetic timestamps.

**4. Backfill (in `expire!` or a companion pass):** for `type=conversation` docs with NO `:content/expires-at`, set `:content/expires-at = (+ :content/ts DEFAULT_CONVERSATION_TTL)` on the first pass. Older-than-30d turns expire immediately; recent ones get their month. Idempotent (only touches docs missing expires-at). **Edge cases (verified review 2026-08-05):**
  - The expiry query only matches docs *with* `:content/expires-at`; the backfill query must use a `not`-clause: `[?e :content/type "conversation"] (not [?e :content/expires-at _])`.
  - **Guard missing `:content/ts`** (ingest-path docs): `(+ nil ttl)` throws. Skip docs without ts, or default to `(Instant/now)`.
  - **Precedence:** explicit `:ttl` overrides the type default; `ttl ≤ 0` → clamp to nil (never expires) — pick one and document it.

**5. Expiry trigger — startup + route + minimal in-process pass.** Run `expire!` once at server startup (after `store-init`/`init-search!`), expose `POST /api/expire` for the future M4.2 nightly cron, **and** add a minimal low-frequency in-process pass (every 6–12h, one atom-guarded `expire!`) so a long-running server actually stops the bleeding between restarts. **Acknowledge explicitly:** startup + route alone does NOT stop the bleeding on a server that stays up for weeks — this was flagged in review. No plugin change (sync_turn stays dumb); keep it one atom-guarded fn, no timer machinery.

**6. Stats (api.clj `GET /api/stats`):** include `:expired` from a **persisted last-pass result** — `expire!` returning `{:expired n}` is transient; store the last pass in an atom in `sys` (e.g. `::last-expiry {:at … :expired n}`), set at startup and on each `/api/expire`, and `stats` reports that. Note: `store/lookup`'s pull (store.clj:43-45) doesn't include the new attr, so no cheshire Instant-encoding exposure today — keep it that way.

**7. API route plumbing:** `POST /api/expire` is body-less. **`read-body` in api.clj currently does `json/parse-string (slurp (:body request))` — empty body → `slurp` → `""` → parse throws (500).** Fix `read-body` to be nil-safe: `(let [s (slurp (:body request))] (when (seq s) (json/parse-string s true)))` (benefits all routes), and document the route as body-less.

### Acceptance
1. Store a memory with `ttl: 1` (second) → run `expire!` → `lookup` returns nil, search excludes it, `stats` shows expired count ≥ 1.
2. `sync_turn` auto-memories (type=conversation) get the 30-day default — no plugin change needed.
3. Backfill: a pre-existing conversation doc without expires-at gets `ts + 30d`; one older than 30d expires on the first pass.
4. `POST /api/expire` works; idempotent (re-running is a no-op).
5. cljfmt + clj-kondo pass; new tests green; existing 16/91 + 38 still green.
6. Empty `expire!` returns `{:expired 0}` (and stats reflect it).
7. Env-var TTL override works (`DATOM_CONVERSATION_TTL_DAYS`, via `config/get-env` with-redefs per repo convention).
8. **Doc-sync in the same commit:** README.md + AGENTS.md tool tables updated for `:content/expires-at` and `/api/expire` (ROADMAP usage convention).

### Test plan
- New Clojure tests: remember-with-ttl, expire-removes-from-all-indices, backfill, type-default, stats reporting. Use synthetic `:content/ts` + `:content/expires-at` (Instant manipulation).
- Existing Python plugin tests unchanged (no plugin change).

---

## Out of scope (this swing)
- M1.9 eval harness (next swing; needs fixture corpus design, 3-4h)
- M3.1 live system_prompt_block (has 3 hidden deps — needs top-N route, importance/type ranking, Hermes contract check)
- M2.3 dedup, M2.4 contradiction, M2.1 update tool
- Background expiry thread / timers (deferred to M4.2 cron)
- Any plugin changes (sync_turn stays dumb; server-side defaults do the work)

## Open questions — RESOLVED by empirical review (2026-08-05, datalevin 0.10.18)
1. **Lock filename + behavior** — `lock.mdb` confirmed (datalevin/kv.clj:1783). lock.mdb **persists after clean close** → existence ≠ staleness; POSIX locks die with process → SIGKILL reopen expected to succeed. M1.2 gated on the SIGKILL test.
2. **Schema evolution** — additive attrs auto-register on first transact (matches `:content/ts`); `dl/update-schema` also available. Do NOT add `:db.valueType :db.type/instant` (crashes).
3. **Instant comparison in datalog** — `[(< ?exp ?now)]` with a bound Instant param works correctly (no filter needed).
4. **`POST /api/expire` semantics** — returns `{:expired n}`; 200 always; **body-less route requires nil-safe `read-body` fix** (empty body currently throws).

## Sizing (realistic, post-review)
- **M1.2:** 30–45 min if SIGKILL test confirms self-healing (likely). 2–4 h only if the loop is real (diagnosis + potential NixOS rebuild).
- **M2.2:** **5–8 h** (not 2–3 h) — valueType trap, auto-registration verification, nil-safe read-body, stats atom, backfill edge cases, ~7 new tests. Budget accordingly.

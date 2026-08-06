"""Datom memory plugin — composable agent memory with hybrid search.

Provides persistent recall across sessions via datom's JSON API (port 9091).
Hybrid fulltext + vector search with graph neighbor expansion.

Config in $HERMES_HOME/datom.json (profile-scoped):
    {"datom_url": "http://localhost:9091"}
"""

from __future__ import annotations

import json
import logging
import os
import threading
from pathlib import Path
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Tool schemas (OpenAI function-calling format)
# ---------------------------------------------------------------------------

TOOL_SCHEMAS = [
    {
        "name": "datom_search",
        "description": "Search agent memory — hybrid fulltext + vector recall with optional graph expansion.",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Search query"},
                "top": {"type": "number", "description": "Max results (default 5)"},
                "expand": {"type": "number", "description": "Graph hops for neighbor expansion (default 0)"},
            },
            "required": ["query"],
        },
    },
    {
        "name": "datom_remember",
        "description": "Store a document in agent memory.",
        "parameters": {
            "type": "object",
            "properties": {
                "body": {"type": "string", "description": "Document content"},
                "title": {"type": "string", "description": "Optional title"},
                "type": {"type": "string", "description": "Document type (default note)"},
            },
            "required": ["body"],
        },
    },
    {
        "name": "datom_forget",
        "description": "Remove a document from memory by ID.",
        "parameters": {
            "type": "object",
            "properties": {
                "id": {"type": "string", "description": "Document ID to remove"},
            },
            "required": ["id"],
        },
    },
    {
        "name": "datom_lookup",
        "description": "Retrieve a document by ID.",
        "parameters": {
            "type": "object",
            "properties": {
                "id": {"type": "string", "description": "Document ID"},
            },
            "required": ["id"],
        },
    },
    {
        "name": "datom_stats",
        "description": "Show memory system statistics.",
        "parameters": {"type": "object", "properties": {}},
    },
]

# ---------------------------------------------------------------------------
# Context formatting (pure function — easy to test)
# ---------------------------------------------------------------------------


def _format_context(results: list) -> str:
    """Format search results as distilled context.

    Budget: 3-5 items, ~200-500 tokens total.
    Each item: title + one-line snippet (150 chars max).
    """
    if not results:
        return ""

    lines = []
    for r in results[:5]:
        title = r.get("title") or r.get("id", "untitled")
        body = r.get("body", "")
        snippet = body[:150].replace("\n", " ").strip()
        if len(body) > 150:
            snippet += "..."
        lines.append(f"- {title}: {snippet}")

    return "Relevant context from memory:\n" + "\n".join(lines)


# ---------------------------------------------------------------------------
# MemoryProvider implementation
# ---------------------------------------------------------------------------


class DatomMemoryProvider:
    """Datom memory — composable agent memory with hybrid search.

    Uses datom's JSON API on port 9091. Thin HTTP client; all logic
    lives in datom.core on the Clojure side.
    """

    def __init__(self, client: Any = None):
        self._base_url = "http://localhost:9091"
        self._client = client  # Injectable for testing
        self._sync_thread: Optional[threading.Thread] = None
        self._compress_thread: Optional[threading.Thread] = None
        self._sync_lock = threading.Lock()
        self._compress_lock = threading.Lock()

    @property
    def name(self) -> str:
        return "datom"

    def is_available(self) -> bool:
        """Check config — no network calls."""
        # 1. Env var
        if os.environ.get("DATOM_URL"):
            return True

        # 2. Config file in hermes_home
        hermes_home = os.environ.get("HERMES_HOME", os.path.expanduser("~/.hermes"))
        config_path = Path(hermes_home) / "datom.json"
        if config_path.exists():
            return True

        # 3. Default — assume available, let initialize() verify
        return True

    def initialize(self, session_id: str, **kwargs) -> None:
        """Initialize HTTP client and verify server is reachable."""
        import httpx

        hermes_home = kwargs.get("hermes_home", "")

        # Load saved config
        if hermes_home:
            config_path = Path(hermes_home) / "datom.json"
            if config_path.exists():
                try:
                    config = json.loads(config_path.read_text())
                    self._base_url = config.get("datom_url", self._base_url)
                except Exception as e:
                    logger.warning("Failed to load datom config: %s", e)

        # Env var override
        env_url = os.environ.get("DATOM_URL")
        if env_url:
            self._base_url = env_url

        # Create client
        if self._client is None:
            self._client = httpx.Client(timeout=10)

        # Verify server (logged, not fatal)
        try:
            resp = self._client.get(f"{self._base_url}/api/stats")
            resp.raise_for_status()
        except Exception as e:
            logger.warning("datom server unreachable at %s: %s", self._base_url, e)

    def get_tool_schemas(self) -> List[Dict[str, Any]]:
        return TOOL_SCHEMAS

    def handle_tool_call(self, tool_name: str, args: Dict[str, Any], **kwargs) -> str:
        endpoint = tool_name.removeprefix("datom_")
        result = self._post(f"/api/{endpoint}", args)
        return json.dumps(result)

    def system_prompt_block(self) -> str:
        return (
            "# Datom Memory\n"
            "Active. Use datom_search for recall, datom_remember to store.\n"
            "Tools: datom_search, datom_remember, datom_forget, datom_lookup, datom_stats"
        )

    def prefetch(self, query: str, *, session_id: str = "") -> str:
        """Recall relevant context — distilled facts, 200-500 token budget."""
        results = self._post("/api/search", {"query": query, "top": 5, "expand": 1})
        return _format_context(results.get("results", []))

    def sync_turn(
        self,
        user_content: str,
        assistant_content: str,
        *,
        session_id: str = "",
        messages: Optional[List[Dict[str, Any]]] = None,
    ) -> None:
        """Persist turn — non-blocking daemon thread."""

        def _sync():
            self._post(
                "/api/remember",
                {
                    "body": f"User: {user_content}\n\nAssistant: {assistant_content}",
                    "type": "conversation",
                },
            )

        with self._sync_lock:
            self._join_thread(self._sync_thread)
            self._sync_thread = threading.Thread(target=_sync, daemon=True)
            self._sync_thread.start()

    def on_pre_compress(self, messages: List[Dict[str, Any]]) -> str:
        """Extract insights before compression discards messages.

        Saves relevant context from prior sessions to the memory store
        so it survives compression. Returns empty string — trust retrieval
        to surface it later (ByteRover pattern).
        """
        if not messages:
            return ""

        def _flush():
            try:
                user_msgs = [
                    m.get("content", "")
                    for m in messages
                    if m.get("role") == "user" and isinstance(m.get("content"), str)
                ]
                if not user_msgs:
                    return

                query = " ".join(user_msgs[-3:])[-500:]
                results = self._post("/api/search", {"query": query, "top": 3})
                context = _format_context(results.get("results", []))

                if context:
                    self._post(
                        "/api/remember",
                        {
                            "body": f"[Pre-compression context]\n{context}",
                            "type": "meta",
                            "title": "Context before compression",
                        },
                    )
            except Exception as e:
                logger.warning("datom pre-compression flush failed: %s", e)

        with self._compress_lock:
            self._join_thread(self._compress_thread)
            self._compress_thread = threading.Thread(target=_flush, daemon=True)
            self._compress_thread.start()
        return ""

    def on_session_end(self, messages: List[Dict[str, Any]]) -> None:
        """Persist end-of-session summary before the gateway tears down.

        Called only at session boundaries (explicit exit, /reset, gateway
        expiry/shutdown), NOT after every turn. Flushes pending syncs, then
        stores a distilled summary so in-flight context survives a restart.
        Never raises — _post swallows errors and the manager try/excepts.
        """
        if not messages:
            return

        def _flush():
            try:
                parts = [
                    str(m.get("content", "")).strip()
                    for m in messages
                    if m.get("role") in ("user", "assistant")
                    and isinstance(m.get("content"), str)
                    and m.get("content", "").strip()
                ]
                if not parts:
                    return
                summary = "\n\n".join(parts[-6:])
                self._post(
                    "/api/remember",
                    {
                        "body": f"[End of session]\n{summary}",
                        "type": "conversation",
                        "title": "Session summary",
                    },
                )
            except Exception as e:
                logger.warning("datom session-end flush failed: %s", e)

        with self._sync_lock:
            self._join_thread(self._sync_thread)
        with self._compress_lock:
            self._join_thread(self._compress_thread)
        with self._sync_lock:
            self._sync_thread = threading.Thread(target=_flush, daemon=True)
            self._sync_thread.start()
            self._join_thread(self._sync_thread, timeout=10.0)

    def shutdown(self) -> None:
        with self._sync_lock:
            self._join_thread(self._sync_thread)
        with self._compress_lock:
            self._join_thread(self._compress_thread)
        if self._client:
            self._client.close()

    def get_config_schema(self) -> List[Dict[str, Any]]:
        return [
            {
                "key": "datom_url",
                "description": "Datom JSON API server URL",
                "default": "http://localhost:9091",
            },
        ]

    def save_config(self, values: Dict[str, Any], hermes_home: str) -> None:
        config_path = Path(hermes_home) / "datom.json"
        config_path.write_text(json.dumps(values, indent=2))

    # --- Private ---

    def _post(self, path: str, body: dict) -> dict:
        if not self._client:
            return {}
        try:
            resp = self._client.post(f"{self._base_url}{path}", json=body)
            resp.raise_for_status()
            return resp.json()
        except Exception as e:
            logger.warning("datom %s failed: %s", path, e)
            return {}

    @staticmethod
    def _join_thread(thread: Optional[threading.Thread], timeout: float = 5.0) -> None:
        if thread and thread.is_alive():
            thread.join(timeout=timeout)


# ---------------------------------------------------------------------------
# Plugin entry point
# ---------------------------------------------------------------------------


def register(ctx) -> None:
    """Called by the memory plugin discovery system."""
    ctx.register_memory_provider(DatomMemoryProvider())

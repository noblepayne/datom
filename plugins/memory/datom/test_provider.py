"""Unit tests for DatomMemoryProvider.

Uses a mock HTTP client — no running server required.
Run with: python -m pytest plugins/memory/datom/test_provider.py -v
"""

from __future__ import annotations

import json
import os
import tempfile
import time
from pathlib import Path
from typing import Any, Dict
from unittest.mock import MagicMock

import pytest

from plugins.memory.datom import (
    DatomMemoryProvider,
    _format_context,
    TOOL_SCHEMAS,
)


# ---------------------------------------------------------------------------
# Mock HTTP client
# ---------------------------------------------------------------------------


class MockResponse:
    """Minimal httpx.Response stand-in."""

    def __init__(self, data: dict, status_code: int = 200):
        self._data = data
        self.status_code = status_code

    def json(self) -> dict:
        return self._data

    def raise_for_status(self):
        if self.status_code >= 400:
            raise Exception(f"HTTP {self.status_code}")


class MockClient:
    """Injected into DatomMemoryProvider to avoid real HTTP."""

    def __init__(self, routes: Dict[str, dict] | None = None):
        self._routes = routes or {}
        self._calls: list = []

    def post(self, url: str, json: dict = None) -> MockResponse:
        self._calls.append(("POST", url, json))
        for pattern, data in self._routes.items():
            if pattern in url:
                return MockResponse(data)
        return MockResponse({"error": "not found"}, 404)

    def get(self, url: str) -> MockResponse:
        self._calls.append(("GET", url, None))
        if "stats" in url:
            return MockResponse({"doc-count": 42, "indexed": 40})
        return MockResponse({}, 200)

    def close(self):
        pass


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture
def mock_client():
    return MockClient(
        {
            "/api/search": {
                "results": [
                    {"id": "lud-0", "title": "LUD-00", "body": "First LNURL spec for basic protocol flow."},
                    {"id": "lud-1", "title": "LUD-01", "body": "LNURL-pay flow with recursive callback."},
                ]
            },
            "/api/remember": {"ok": True, "id": "new-doc-1"},
            "/api/forget": {"ok": True},
            "/api/lookup": {"id": "lud-0", "title": "LUD-00", "body": "First LNURL spec."},
            "/api/stats": {"doc-count": 42, "indexed": 40},
        }
    )


@pytest.fixture
def provider(mock_client):
    p = DatomMemoryProvider(client=mock_client)
    return p


# ---------------------------------------------------------------------------
# Name
# ---------------------------------------------------------------------------


def test_name():
    p = DatomMemoryProvider()
    assert p.name == "datom"


# ---------------------------------------------------------------------------
# is_available
# ---------------------------------------------------------------------------


def test_is_available_default():
    """Always available — let initialize() verify."""
    p = DatomMemoryProvider()
    env = os.environ.copy()
    env.pop("DATOM_URL", None)
    with tempfile.TemporaryDirectory() as td:
        with tempfile.TemporaryDirectory() as hermes_home:
            env["HERMES_HOME"] = hermes_home
            with pytest.MonkeyPatch.context() as m:
                for k, v in env.items():
                    m.setenv(k, v)
                assert p.is_available() is True


def test_is_available_with_env_var():
    with pytest.MonkeyPatch.context() as m:
        m.setenv("DATOM_URL", "http://localhost:9091")
        p = DatomMemoryProvider()
        assert p.is_available() is True


def test_is_available_with_config_file():
    with tempfile.TemporaryDirectory() as hermes_home:
        config_path = Path(hermes_home) / "datom.json"
        config_path.write_text('{"datom_url": "http://localhost:9091"}')
        with pytest.MonkeyPatch.context() as m:
            m.setenv("HERMES_HOME", hermes_home)
            m.delenv("DATOM_URL", raising=False)
            p = DatomMemoryProvider()
            assert p.is_available() is True


# ---------------------------------------------------------------------------
# Tool schemas
# ---------------------------------------------------------------------------


def test_tool_schemas():
    schemas = TOOL_SCHEMAS
    assert len(schemas) == 5
    names = [s["name"] for s in schemas]
    assert "datom_search" in names
    assert "datom_remember" in names
    assert "datom_forget" in names
    assert "datom_lookup" in names
    assert "datom_stats" in names


def test_get_tool_schemas(provider):
    assert provider.get_tool_schemas() == TOOL_SCHEMAS


# ---------------------------------------------------------------------------
# handle_tool_call
# ---------------------------------------------------------------------------


def test_handle_tool_call_search(provider):
    result = json.loads(provider.handle_tool_call("datom_search", {"query": "LNURL"}))
    assert "results" in result
    assert len(result["results"]) == 2


def test_handle_tool_call_remember(provider):
    result = json.loads(provider.handle_tool_call("datom_remember", {"body": "test"}))
    assert result["ok"] is True


def test_handle_tool_call_forget(provider):
    result = json.loads(provider.handle_tool_call("datom_forget", {"id": "lud-0"}))
    assert result["ok"] is True


def test_handle_tool_call_stats(provider):
    result = json.loads(provider.handle_tool_call("datom_stats", {}))
    assert result["doc-count"] == 42


# ---------------------------------------------------------------------------
# system_prompt_block
# ---------------------------------------------------------------------------


def test_system_prompt_block(provider):
    block = provider.system_prompt_block()
    assert "Datom Memory" in block
    assert "datom_search" in block
    assert "datom_remember" in block


# ---------------------------------------------------------------------------
# prefetch
# ---------------------------------------------------------------------------


def test_prefetch_formats_results(provider):
    text = provider.prefetch("summary")
    assert "Relevant context from memory:" in text
    assert "LUD-00" in text
    assert "LUD-01" in text


def test_prefetch_includes_snippets(provider):
    text = provider.prefetch("summary")
    assert "First LNURL spec" in text
    assert "LNURL-pay flow" in text


def test_prefetch_empty():
    client = MockClient({"/api/search": {"results": []}})
    p = DatomMemoryProvider(client=client)
    assert p.prefetch("nonexistent") == ""


def test_prefetch_max_5_items():
    results = [{"id": f"doc-{i}", "title": f"Doc {i}", "body": f"Body {i}"} for i in range(10)]
    client = MockClient({"/api/search": {"results": results}})
    p = DatomMemoryProvider(client=client)
    text = p.prefetch("anything")
    # Should only format 5
    assert text.count("- Doc") == 5


def test_prefetch_truncates_long_body():
    results = [{"id": "long", "title": "Long", "body": "x" * 300}]
    client = MockClient({"/api/search": {"results": results}})
    p = DatomMemoryProvider(client=client)
    text = p.prefetch("anything")
    assert "..." in text
    assert len(text) < 500


# ---------------------------------------------------------------------------
# _format_context (pure function)
# ---------------------------------------------------------------------------


def test_format_context_empty():
    assert _format_context([]) == ""
    assert _format_context(None) == ""


def test_format_context_single():
    text = _format_context([{"id": "x", "title": "X", "body": "hello"}])
    assert text == "Relevant context from memory:\n- X: hello"


def test_format_context_no_title():
    text = _format_context([{"id": "abc", "body": "content"}])
    assert "- abc: content" in text


def test_format_context_strips_newlines():
    text = _format_context([{"id": "x", "title": "X", "body": "line1\nline2\nline3"}])
    # Body portion should have newlines replaced with spaces
    body_line = [l for l in text.split("\n") if l.startswith("- X:")][0]
    assert "line1 line2 line3" in body_line


# ---------------------------------------------------------------------------
# sync_turn
# ---------------------------------------------------------------------------


def test_sync_turn_stores_conversation(provider):
    provider.sync_turn("what is LNURL?", "LNURL is...")
    # Wait for daemon thread
    time.sleep(0.1)
    # Check the mock received the call
    remember_calls = [c for c in provider._client._calls if "/api/remember" in c[1]]
    assert len(remember_calls) >= 1
    body = remember_calls[0][2]["body"]
    assert "User: what is LNURL?" in body
    assert "Assistant: LNURL is..." in body


def test_sync_turn_non_blocking(provider):
    """sync_turn should return immediately (daemon thread)."""
    start = time.time()
    provider.sync_turn("test", "response")
    elapsed = time.time() - start
    assert elapsed < 0.1  # Should be near-instant


def test_sync_turn_type_conversation(provider):
    provider.sync_turn("a", "b")
    time.sleep(0.1)
    remember_calls = [c for c in provider._client._calls if "/api/remember" in c[1]]
    assert remember_calls[0][2]["type"] == "conversation"


# ---------------------------------------------------------------------------
# on_pre_compress
# ---------------------------------------------------------------------------


def test_on_pre_compress_no_messages():
    client = MockClient()
    p = DatomMemoryProvider(client=client)
    assert p.on_pre_compress([]) == ""


def test_on_pre_compress_returns_empty():
    """Returns empty string — trust retrieval, don't inject into compression prompt."""
    client = MockClient({"/api/search": {"results": []}})
    p = DatomMemoryProvider(client=client)
    messages = [
        {"role": "user", "content": "what is LNURL?"},
        {"role": "assistant", "content": "LNURL is..."},
    ]
    result = p.on_pre_compress(messages)
    assert result == ""


def test_on_pre_compress_saves_context():
    """Should search and save relevant context before compression."""
    client = MockClient(
        {
            "/api/search": {"results": [{"id": "x", "title": "X", "body": "relevant"}]},
            "/api/remember": {"ok": True},
        }
    )
    p = DatomMemoryProvider(client=client)
    messages = [{"role": "user", "content": "tell me about LNURL"}]
    p.on_pre_compress(messages)
    time.sleep(0.2)
    remember_calls = [c for c in client._calls if "/api/remember" in c[1]]
    assert len(remember_calls) >= 1
    assert "Pre-compression context" in remember_calls[0][2]["body"]


def test_on_pre_compress_skips_non_user_messages():
    client = MockClient()
    p = DatomMemoryProvider(client=client)
    messages = [
        {"role": "assistant", "content": "hello"},
        {"role": "system", "content": "you are helpful"},
    ]
    p.on_pre_compress(messages)
    time.sleep(0.1)
    search_calls = [c for c in client._calls if "/api/search" in c[1]]
    assert len(search_calls) == 0  # No user messages → no search


# ---------------------------------------------------------------------------
# initialize (requires httpx)
# ---------------------------------------------------------------------------


def test_initialize_creates_client():
    httpx = pytest.importorskip("httpx")
    p = DatomMemoryProvider()
    assert p._client is None
    p.initialize("test-session")
    assert p._client is not None
    p.shutdown()


def test_initialize_with_config(tmp_path):
    httpx = pytest.importorskip("httpx")
    config_path = tmp_path / "datom.json"
    config_path.write_text('{"datom_url": "http://custom:8080"}')
    p = DatomMemoryProvider()
    p.initialize("test-session", hermes_home=str(tmp_path))
    assert p._base_url == "http://custom:8080"
    p.shutdown()


def test_initialize_env_var_override():
    httpx = pytest.importorskip("httpx")
    with pytest.MonkeyPatch.context() as m:
        m.setenv("DATOM_URL", "http://env-host:3000")
        p = DatomMemoryProvider()
        p.initialize("test-session")
        assert p._base_url == "http://env-host:3000"
        p.shutdown()


# ---------------------------------------------------------------------------
# shutdown
# ---------------------------------------------------------------------------


def test_shutdown_closes_client():
    client = MockClient()
    p = DatomMemoryProvider(client=client)
    p.shutdown()
    # No assertion needed — just shouldn't crash


def test_shutdown_waits_for_sync():
    client = MockClient({"/api/remember": {"ok": True}})
    p = DatomMemoryProvider(client=client)
    p.sync_turn("a", "b")
    p.shutdown()  # Should wait for thread


# ---------------------------------------------------------------------------
# config
# ---------------------------------------------------------------------------


def test_get_config_schema(provider):
    schema = provider.get_config_schema()
    assert len(schema) == 1
    assert schema[0]["key"] == "datom_url"
    assert schema[0]["default"] == "http://localhost:9091"


def test_save_config(tmp_path):
    p = DatomMemoryProvider()
    p.save_config({"datom_url": "http://custom:8080"}, str(tmp_path))
    config = json.loads((tmp_path / "datom.json").read_text())
    assert config["datom_url"] == "http://custom:8080"


# ---------------------------------------------------------------------------
# register
# ---------------------------------------------------------------------------


def test_register():
    from plugins.memory.datom import register

    ctx = MagicMock()
    register(ctx)
    ctx.register_memory_provider.assert_called_once()
    provider = ctx.register_memory_provider.call_args[0][0]
    assert isinstance(provider, DatomMemoryProvider)
    assert provider.name == "datom"

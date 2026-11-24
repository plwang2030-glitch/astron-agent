"""Unit tests for the Volcengine (Ark / Doubao Speech) clients."""

import base64
import json
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator, Dict, List

import pytest
from plugin.aitools.api.schemas.types import SuccessResponse
from plugin.aitools.common.clients import volcengine_client
from plugin.aitools.common.exceptions.exceptions import ServiceException
from plugin.aitools.platform_account_config import (
    VolcengineArkConfig,
    VolcengineSpeechConfig,
)

ARK = VolcengineArkConfig(
    base_url="https://ark.example/api/v3",
    api_key="ark-key",
    chat_model="chat-m",
    vision_model="vision-m",
    image_model="image-m",
)


def _fake_http_client(content: Any, calls: List[Dict[str, Any]]) -> Any:
    class FakeHttpClient:
        def __init__(self, method: str, url: str, span: Any = None, **kwargs: Any):
            calls.append({"method": method, "url": url, **kwargs})

        @asynccontextmanager
        async def start(self) -> AsyncIterator["FakeHttpClient"]:
            yield self

        @asynccontextmanager
        async def request(self) -> AsyncIterator[SuccessResponse]:
            yield SuccessResponse(data={"content": content})

    return FakeHttpClient


def test_seedream_size_scales_small_sizes_up_to_minimum() -> None:
    width, height = map(int, volcengine_client.seedream_size(1024, 1024).split("x"))
    assert width == height
    assert width * height >= volcengine_client.SEEDREAM_MIN_PIXELS


def test_seedream_size_keeps_aspect_ratio() -> None:
    width, height = map(int, volcengine_client.seedream_size(1024, 512).split("x"))
    assert width * height >= volcengine_client.SEEDREAM_MIN_PIXELS
    assert abs(width / height - 2) < 0.01


def test_seedream_size_caps_large_sizes() -> None:
    width, height = map(int, volcengine_client.seedream_size(8192, 8192).split("x"))
    assert width * height <= volcengine_client.SEEDREAM_MAX_PIXELS


def test_detect_image_mime() -> None:
    assert volcengine_client.detect_image_mime(b"\x89PNG....") == "image/png"
    assert volcengine_client.detect_image_mime(b"RIFF1234WEBPxx") == "image/webp"
    assert volcengine_client.detect_image_mime(b"\xff\xd8\xff") == "image/jpeg"


def test_parse_tts_body_concatenates_audio() -> None:
    chunk1 = base64.b64encode(b"abc").decode()
    chunk2 = base64.b64encode(b"def").decode()
    body = (
        json.dumps({"code": 0, "data": chunk1})
        + "\n"
        + json.dumps({"code": 0, "data": chunk2})
        + json.dumps({"code": 20000000, "message": "ok"})
    )
    audio, error = volcengine_client.parse_tts_body(body)
    assert audio == b"abcdef"
    assert error is None


def test_parse_tts_body_reports_error() -> None:
    audio, error = volcengine_client.parse_tts_body(
        json.dumps({"code": 45000010, "message": "Invalid X-Api-Key"})
    )
    assert audio == b""
    assert error is not None and "45000010" in error


def test_speech_headers_prefer_api_key() -> None:
    new_console = VolcengineSpeechConfig("key", "", "", "seed-tts-1.0", "spk")
    old_console = VolcengineSpeechConfig("", "app", "token", "seed-tts-1.0", "spk")

    headers = volcengine_client.speech_headers(new_console, "seed-tts-1.0")
    assert headers["X-Api-Key"] == "key"
    assert "X-Api-App-Id" not in headers

    headers = volcengine_client.speech_headers(old_console, "seed-tts-1.0")
    assert headers["X-Api-App-Id"] == "app"
    assert headers["X-Api-Access-Key"] == "token"
    assert headers["X-Api-Resource-Id"] == "seed-tts-1.0"


@pytest.mark.asyncio
async def test_ark_vision_chat_sends_data_url_and_returns_answer(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: List[Dict[str, Any]] = []
    content = {"choices": [{"message": {"content": "a cat"}}], "usage": {}}
    monkeypatch.setattr(
        volcengine_client, "HttpClient", _fake_http_client(content, calls)
    )

    answer = await volcengine_client.ark_vision_chat(
        ARK, "what is it?", b"\x89PNGdata", disable_thinking=True
    )

    assert answer == "a cat"
    call = calls[0]
    assert call["url"] == "https://ark.example/api/v3/chat/completions"
    assert call["headers"]["Authorization"] == "Bearer ark-key"
    parts = call["json"]["messages"][0]["content"]
    assert parts[0]["image_url"]["url"].startswith("data:image/png;base64,")
    assert call["json"]["thinking"] == {"type": "disabled"}
    assert call["json"]["model"] == "vision-m"


@pytest.mark.asyncio
async def test_ark_generate_image_decodes_b64(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: List[Dict[str, Any]] = []
    content = {"data": [{"b64_json": base64.b64encode(b"img").decode()}]}
    monkeypatch.setattr(
        volcengine_client, "HttpClient", _fake_http_client(content, calls)
    )

    image = await volcengine_client.ark_generate_image(ARK, "a cat", "2048x2048")

    assert image == b"img"
    assert calls[0]["json"]["response_format"] == "b64_json"
    assert calls[0]["json"]["size"] == "2048x2048"


@pytest.mark.asyncio
async def test_ark_generate_image_raises_on_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    content = {"error": {"code": "InvalidParameter", "message": "bad size"}}
    monkeypatch.setattr(volcengine_client, "HttpClient", _fake_http_client(content, []))

    with pytest.raises(ServiceException):
        await volcengine_client.ark_generate_image(ARK, "a cat", "10x10")

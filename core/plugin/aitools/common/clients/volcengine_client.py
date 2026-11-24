"""
ByteDance Volcengine clients for AiTools.

Ark (方舟) uses the OpenAI-compatible REST API with a Bearer API key:
  - POST {base}/chat/completions   vision understanding / OCR
  - POST {base}/images/generations Seedream text-to-image
Doubao Speech (豆包语音) TTS uses the V3 HTTP chunked API:
  - POST https://openspeech.bytedance.com/api/v3/tts/unidirectional

Requests go through the shared HttpClient so they get the same tracing and
error handling as the iFLYTEK clients. Credentials are only sent as headers,
which HttpSpanHooks does not record.
"""

import base64
import json
import math
import uuid
from typing import Any, Dict, List, Optional, Tuple

from loguru import logger as log
from plugin.aitools.common.clients.adapters import SpanLike
from plugin.aitools.common.clients.aiohttp_client import HttpClient
from plugin.aitools.common.exceptions.error.code_enums import CodeEnums
from plugin.aitools.common.exceptions.exceptions import ServiceException
from plugin.aitools.platform_account_config import (
    VolcengineArkConfig,
    VolcengineSpeechConfig,
)

VOLC_TTS_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
VOLC_TTS_SUCCESS_END_CODE = 20000000

# Seedream 5.0 rejects images below 3,686,400 pixels; 4096x4096 is the upper bound.
SEEDREAM_MIN_PIXELS = 2560 * 1440
SEEDREAM_MAX_PIXELS = 4096 * 4096
SEEDREAM_MAX_ASPECT_RATIO = 16


def detect_image_mime(data: bytes) -> str:
    if data.startswith(b"\x89PNG"):
        return "image/png"
    if data.startswith(b"GIF8"):
        return "image/gif"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    if data.startswith(b"BM"):
        return "image/bmp"
    return "image/jpeg"


def to_data_url(image_bytes: bytes) -> str:
    mime = detect_image_mime(image_bytes)
    return f"data:{mime};base64,{base64.b64encode(image_bytes).decode('utf-8')}"


def seedream_size(width: int, height: int) -> str:
    """Map a requested size to a Seedream-compatible WxH, keeping the aspect ratio."""
    width = max(int(width or 1024), 1)
    height = max(int(height or 1024), 1)
    ratio = width / height
    if ratio > SEEDREAM_MAX_ASPECT_RATIO:
        height = math.ceil(width / SEEDREAM_MAX_ASPECT_RATIO)
    elif ratio < 1 / SEEDREAM_MAX_ASPECT_RATIO:
        width = math.ceil(height / SEEDREAM_MAX_ASPECT_RATIO)
    pixels = width * height
    if pixels < SEEDREAM_MIN_PIXELS:
        scale = math.sqrt(SEEDREAM_MIN_PIXELS / pixels)
        width, height = math.ceil(width * scale), math.ceil(height * scale)
    elif pixels > SEEDREAM_MAX_PIXELS:
        scale = math.sqrt(SEEDREAM_MAX_PIXELS / pixels)
        width, height = math.floor(width * scale), math.floor(height * scale)
    return f"{width}x{height}"


def _ark_headers(config: VolcengineArkConfig) -> Dict[str, str]:
    return {
        "Authorization": f"Bearer {config.api_key}",
        "Content-Type": "application/json",
    }


async def ark_vision_chat(
    config: VolcengineArkConfig,
    prompt: str,
    image_bytes: bytes,
    span: Optional[SpanLike] = None,
    disable_thinking: bool = False,
) -> str:
    """Ask the Ark vision model about one image; returns the answer text."""
    body: Dict[str, Any] = {
        "model": config.vision_model,
        "messages": [
            {
                "role": "user",
                "content": [
                    {
                        "type": "image_url",
                        "image_url": {"url": to_data_url(image_bytes)},
                    },
                    {"type": "text", "text": prompt},
                ],
            }
        ],
    }
    if disable_thinking:
        body["thinking"] = {"type": "disabled"}

    async with HttpClient(
        method="POST",
        url=f"{config.base_url}/chat/completions",
        span=span,
        headers=_ark_headers(config),
        json=body,
    ).start() as client:
        async with client.request() as response:
            content = response.data.get("content")  # type: ignore[union-attr]

    if not isinstance(content, dict):
        raise ServiceException.from_error_code(
            CodeEnums.ServiceResponseError, extra_message="火山方舟返回格式错误"
        )
    choices = content.get("choices") or []
    answer = ""
    if choices:
        answer = (choices[0].get("message") or {}).get("content") or ""
    usage = content.get("usage") or {}
    log.info(
        f"Volcengine vision chat completed, model={config.vision_model}, "
        f"answer_len={len(answer)}, total_tokens={usage.get('total_tokens')}"
    )
    return answer


async def ark_generate_image(
    config: VolcengineArkConfig,
    prompt: str,
    size: str,
    span: Optional[SpanLike] = None,
) -> bytes:
    """Generate one image with Seedream and return its bytes (JPEG)."""
    body = {
        "model": config.image_model,
        "prompt": prompt,
        "size": size,
        "response_format": "b64_json",
        "watermark": False,
        "sequential_image_generation": "disabled",
    }
    async with HttpClient(
        method="POST",
        url=f"{config.base_url}/images/generations",
        span=span,
        headers=_ark_headers(config),
        json=body,
    ).start() as client:
        async with client.request() as response:
            content = response.data.get("content")  # type: ignore[union-attr]

    if not isinstance(content, dict):
        raise ServiceException.from_error_code(
            CodeEnums.ServiceResponseError, extra_message="火山方舟返回格式错误"
        )
    error = content.get("error")
    if error:
        raise ServiceException.from_error_code(
            CodeEnums.ServiceResponseError,
            extra_message=f"{error.get('code')}: {error.get('message')}",
        )
    data = content.get("data") or []
    image_b64 = data[0].get("b64_json") if data else None
    if not image_b64:
        raise ServiceException.from_error_code(
            CodeEnums.ServiceResponseError, extra_message="火山方舟未返回图片"
        )
    log.info(
        f"Volcengine image generation completed, model={config.image_model}, "
        f"size={data[0].get('size') or size}"
    )
    return base64.b64decode(image_b64)


def speech_headers(config: VolcengineSpeechConfig, resource_id: str) -> Dict[str, str]:
    headers = {
        "X-Api-Resource-Id": resource_id,
        "X-Api-Request-Id": str(uuid.uuid4()),
        "Content-Type": "application/json",
    }
    if config.api_key:
        headers["X-Api-Key"] = config.api_key
    else:
        headers["X-Api-App-Id"] = config.app_id
        headers["X-Api-Access-Key"] = config.access_token
    return headers


def iter_json_objects(text: str) -> List[Dict[str, Any]]:
    """Parse a chunked TTS body: JSON objects separated by newlines or nothing."""
    decoder = json.JSONDecoder()
    objects: List[Dict[str, Any]] = []
    idx = 0
    length = len(text)
    while idx < length:
        while idx < length and text[idx] in " \r\n\t":
            idx += 1
        if idx >= length:
            break
        obj, idx = decoder.raw_decode(text, idx)
        if isinstance(obj, dict):
            objects.append(obj)
    return objects


def parse_tts_body(text: str) -> Tuple[bytes, Optional[str]]:
    """Return (audio_bytes, error_message) from a V3 unidirectional TTS body."""
    audio = bytearray()
    for obj in iter_json_objects(text):
        code = obj.get("code", 0)
        if code not in (0, VOLC_TTS_SUCCESS_END_CODE):
            return bytes(audio), f"{code}: {obj.get('message', '')}"
        data = obj.get("data")
        if data:
            audio.extend(base64.b64decode(data))
    return bytes(audio), None


async def volc_tts(
    config: VolcengineSpeechConfig,
    text: str,
    speaker: str,
    speech_rate: int = 0,
    audio_format: str = "mp3",
    sample_rate: int = 24000,
    span: Optional[SpanLike] = None,
) -> bytes:
    """Synthesize text with Doubao Speech TTS and return the full audio."""
    body = {
        "user": {"uid": "aitools"},
        "req_params": {
            "text": text,
            "speaker": speaker,
            "audio_params": {
                "format": audio_format,
                "sample_rate": sample_rate,
                "speech_rate": speech_rate,
            },
        },
    }
    async with HttpClient(
        method="POST",
        url=VOLC_TTS_URL,
        span=span,
        headers=speech_headers(config, config.tts_resource_id),
        json=body,
    ).start() as client:
        async with client.request() as response:
            content = response.data.get("content")  # type: ignore[union-attr]
            raw = json.dumps(content) if isinstance(content, dict) else await content.text()  # type: ignore[union-attr]

    audio, error = parse_tts_body(raw)
    if error:
        raise ServiceException.from_error_code(
            CodeEnums.ServiceResponseError, extra_message=f"豆包语音合成失败 {error}"
        )
    log.info(
        f"Volcengine TTS completed, resource={config.tts_resource_id}, "
        f"speaker={speaker}, audio_bytes={len(audio)}"
    )
    return audio

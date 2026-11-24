"""
Smart TTS Service
"""

# pylint: disable=too-many-locals, unused-argument, wrong-import-order
import base64
import json
import os
import uuid
from typing import Any, Dict, Optional

from common.otlp.log_trace.node_trace_log import NodeTraceLog
from common.otlp.metrics.meter import Meter
from fastapi import Request
from loguru import logger as log
from plugin.aitools.api.decorators.api_service import api_service
from plugin.aitools.api.schemas.types import BaseResponse, SuccessResponse
from plugin.aitools.common.clients.adapters import SpanLike
from plugin.aitools.common.clients.volcengine_client import volc_tts
from plugin.aitools.common.clients.websockets_client import WebSocketClient
from plugin.aitools.common.exceptions.error.code_enums import CodeEnums
from plugin.aitools.common.exceptions.exceptions import ServiceException
from plugin.aitools.const.const import TTS_URL_KEY
from plugin.aitools.platform_account_config import (
    CAPABILITY_TTS,
    get_iflytek_open_platform_credentials,
    get_volcengine_speech_config,
    uses_volcengine,
)
from plugin.aitools.utils.oss_utils import upload_file
from pydantic import BaseModel


class SmartTTSInput(BaseModel):
    """Smart TTS Input"""

    text: str
    vcn: str
    speed: int = 50  # Optional, default value is 50


def gen_data(app_id: str | None, text: str, vcn: str, speed: int) -> Dict[str, Any]:
    """Generate data for Smart TTS"""
    return {
        "header": {"app_id": app_id, "status": 2},
        "parameter": {
            "tts": {
                "vcn": vcn,
                "volume": 50,
                "rhy": 0,
                "speed": speed,
                "pitch": 50,
                "bgs": 0,
                "reg": 0,
                "rdn": 0,
                "audio": {
                    "encoding": "lame",
                    "sample_rate": 24000,
                    "channels": 1,
                    "bit_depth": 16,
                    "frame_size": 0,
                },
            }
        },
        "payload": {
            "text": {
                "encoding": "utf8",
                "compress": "raw",
                "format": "plain",
                "status": 2,
                "seq": 0,
                "text": str(base64.b64encode(text.encode("utf-8")), "UTF8"),
            }
        },
    }


_VOLC_SPEAKER_PREFIXES = ("zh_", "en_", "ja_", "es_", "multi_", "ICL_", "S_", "saturn_")


def volc_speaker(vcn: str, default_speaker: str) -> str:
    """Use vcn when it is a Doubao Speech voice; iFLYTEK names fall back to the default."""
    if vcn and ("bigtts" in vcn or vcn.startswith(_VOLC_SPEAKER_PREFIXES)):
        return vcn
    if vcn:
        log.info(
            f"vcn '{vcn}' is not a Doubao Speech voice, using default {default_speaker}"
        )
    return default_speaker


def iflytek_speed_to_volc_rate(speed: int) -> int:
    """iFLYTEK speed 0..100 (50 normal) -> Doubao speech_rate -50..100 (0 normal)."""
    return max(-50, min(100, (speed - 50) * 2))


@api_service(
    method="POST",
    path="/aitools/v1/smarttts",
    query=None,
    body=SmartTTSInput,
    response=BaseResponse,
    summary="Smart TTS",
    description="Convert text to speech",
    tags=["public_cn"],
    deprecated=False,
)
async def smart_tts_service(
    body: SmartTTSInput,
    request: Request,
    span: Optional[SpanLike] = None,
    meter: Optional[Meter] = None,
    node_trace: Optional[NodeTraceLog] = None,
) -> BaseResponse:
    """Smart TTS Service"""
    if not body.text:
        raise ServiceException.from_error_code(
            CodeEnums.ServiceParamsError, extra_message="text不能为空"
        )

    if uses_volcengine(CAPABILITY_TTS):
        speech_config = get_volcengine_speech_config()
        speaker = volc_speaker(body.vcn, speech_config.tts_speaker)
        log.info(
            f"Smart TTS routed to volcengine, resource={speech_config.tts_resource_id}, "
            f"speaker={speaker}, requested_vcn={body.vcn}, text_len={len(body.text)}"
        )
        audio = await volc_tts(
            speech_config,
            body.text,
            speaker,
            speech_rate=iflytek_speed_to_volc_rate(body.speed),
            audio_format="mp3",
            sample_rate=24000,
            span=span,
        )
        if not audio:
            raise ServiceException.from_error_code(
                CodeEnums.ServiceResponseError, extra_message="音频数据为空"
            )
        voice_url = await upload_file(str(uuid.uuid4()) + ".MP3", audio, span)
        return SuccessResponse(data={"voice_url": voice_url}, sid=request.state.sid)

    url = os.getenv(TTS_URL_KEY, "")
    credentials = get_iflytek_open_platform_credentials()
    data = gen_data(credentials.app_id, body.text, body.vcn, body.speed)

    audio_data = bytearray()
    async with WebSocketClient(
        url=url,
        span=span,
        auth="ASE",
        app_id=credentials.app_id,
        api_key=credentials.api_key,
        api_secret=credentials.api_secret,
    ).start() as client:
        await client.send(json.dumps(data))

        async for msg in client.recv():
            message_dict = json.loads(msg)
            code = message_dict.get("header", {}).get("code", 0)
            message = message_dict.get("header", {}).get("message", "")

            if code != 0:
                raise ServiceException.from_error_code(
                    CodeEnums.ServiceResponseError, extra_message=message
                )

            if "payload" in message_dict:
                audio = base64.b64decode(message_dict["payload"]["audio"]["audio"])
                status = message_dict["payload"]["audio"]["status"]

                if status == 2:
                    break

                audio_data.extend(audio)

    if not audio_data:
        raise ServiceException.from_error_code(
            CodeEnums.ServiceResponseError, extra_message="音频数据为空"
        )

    voice_url = await upload_file(str(uuid.uuid4()) + ".MP3", audio_data, span)

    return SuccessResponse(data={"voice_url": voice_url}, sid=request.state.sid)

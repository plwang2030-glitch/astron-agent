import json
import os
from dataclasses import dataclass
from typing import Optional
from urllib.parse import urlparse

from loguru import logger
from plugin.aitools.common.exceptions.error.code_enums import CodeEnums
from plugin.aitools.common.exceptions.exceptions import ServiceException

IFLYTEK_OPEN_PLATFORM_CACHE_KEY = "platform_account_text:iflytek_open_platform"
PLATFORM_ACCOUNT_CATEGORY = "PLATFORM_ACCOUNT"
IFLYTEK_OPEN_PLATFORM_CODE = "IFLYTEK_OPEN_PLATFORM"

# Written by console-hub PlatformAccountService (see PlatformAccountType / AiCapability).
VOLCENGINE_CACHE_KEY = "platform_account_text:volcengine"
VOLCENGINE_CODE = "VOLCENGINE"
AI_CAPABILITY_ROUTING_CACHE_KEY = "platform_account_text:ai_capability_routing"
AI_CAPABILITY_ROUTING_CODE = "AI_CAPABILITY_ROUTING"

PROVIDER_IFLYTEK = "iflytek"
PROVIDER_VOLCENGINE = "volcengine"
_KNOWN_PROVIDERS = (PROVIDER_IFLYTEK, PROVIDER_VOLCENGINE)

# Capability keys, identical to the Java AiCapability keys.
CAPABILITY_IMAGE_GENERATION = "imageGeneration"
CAPABILITY_IMAGE_UNDERSTANDING = "imageUnderstanding"
CAPABILITY_OCR = "ocr"
CAPABILITY_TTS = "tts"

# Defaults, identical to the Java VolcengineDefaults.
VOLC_DEFAULT_ARK_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
VOLC_DEFAULT_CHAT_MODEL = "doubao-seed-2-1-pro-260915"
VOLC_DEFAULT_VISION_MODEL = "doubao-seed-2-1-pro-260915"
VOLC_DEFAULT_IMAGE_MODEL = "doubao-seedream-5-0-260128"
VOLC_DEFAULT_TTS_RESOURCE_ID = "seed-tts-1.0"
VOLC_DEFAULT_TTS_SPEAKER = "zh_female_shuangkuaisisi_moon_bigtts"

_REDIS_CLIENT = None


@dataclass(frozen=True)
class IflytekOpenPlatformCredentials:
    app_id: str
    api_key: str
    api_secret: str


@dataclass(frozen=True)
class VolcengineArkConfig:
    base_url: str
    api_key: str
    chat_model: str
    vision_model: str
    image_model: str


@dataclass(frozen=True)
class VolcengineSpeechConfig:
    """Doubao Speech credentials: api_key (new console) or app_id + access_token (old console)."""

    api_key: str
    app_id: str
    access_token: str
    tts_resource_id: str
    tts_speaker: str


def get_capability_provider(capability: str) -> str:
    """Provider selected in 「AI 能力配置」; missing, blank or unknown values mean iflytek."""
    raw_config = _load_from_redis(
        AI_CAPABILITY_ROUTING_CACHE_KEY
    ) or _load_from_database(
        AI_CAPABILITY_ROUTING_CODE, AI_CAPABILITY_ROUTING_CACHE_KEY
    )
    config = _parse_json(raw_config)
    provider = _clean(config.get(capability)).lower() if config else ""
    if not provider:
        return PROVIDER_IFLYTEK
    if provider not in _KNOWN_PROVIDERS:
        logger.warning(
            f"Unknown AI provider '{provider}' for capability {capability}, "
            "falling back to iflytek"
        )
        return PROVIDER_IFLYTEK
    return provider


def uses_volcengine(capability: str) -> bool:
    return get_capability_provider(capability) == PROVIDER_VOLCENGINE


def get_volcengine_ark_config() -> VolcengineArkConfig:
    config = _load_volcengine_config()
    api_key = _clean(config.get("arkApiKey"))
    if not _has_text(api_key):
        raise ServiceException.from_error_code(
            CodeEnums.ServiceParamsError,
            extra_message=(
                "火山引擎（字节）方舟 API Key is not configured. "
                "Please configure it in Platform Account Management first."
            ),
        )
    base_url = _clean(config.get("arkBaseUrl")) or VOLC_DEFAULT_ARK_BASE_URL
    return VolcengineArkConfig(
        base_url=base_url.rstrip("/"),
        api_key=api_key,
        chat_model=_clean(config.get("chatModel")) or VOLC_DEFAULT_CHAT_MODEL,
        vision_model=_clean(config.get("visionModel")) or VOLC_DEFAULT_VISION_MODEL,
        image_model=_clean(config.get("imageModel")) or VOLC_DEFAULT_IMAGE_MODEL,
    )


def get_volcengine_speech_config() -> VolcengineSpeechConfig:
    config = _load_volcengine_config()
    api_key = _clean(config.get("speechApiKey"))
    app_id = _clean(config.get("speechAppId"))
    access_token = _clean(config.get("speechAccessToken"))
    if not _has_text(api_key) and not (_has_text(app_id) and _has_text(access_token)):
        raise ServiceException.from_error_code(
            CodeEnums.ServiceParamsError,
            extra_message=(
                "火山引擎（字节）豆包语音 is not configured. "
                "Please configure the API Key or AppID + Access Token "
                "in Platform Account Management first."
            ),
        )
    return VolcengineSpeechConfig(
        api_key=api_key if _has_text(api_key) else "",
        app_id=app_id,
        access_token=access_token,
        tts_resource_id=_clean(config.get("ttsResourceId"))
        or VOLC_DEFAULT_TTS_RESOURCE_ID,
        tts_speaker=_clean(config.get("ttsSpeaker")) or VOLC_DEFAULT_TTS_SPEAKER,
    )


def _load_volcengine_config() -> dict:
    raw_config = _load_from_redis(VOLCENGINE_CACHE_KEY) or _load_from_database(
        VOLCENGINE_CODE, VOLCENGINE_CACHE_KEY
    )
    return _parse_json(raw_config)


def _parse_json(raw_config: Optional[str]) -> dict:
    if not raw_config:
        return {}
    try:
        value = json.loads(raw_config)
    except json.JSONDecodeError:
        return {}
    return value if isinstance(value, dict) else {}


def _has_text(value: str) -> bool:
    return (
        bool(value) and not value.lower().startswith("your-") and value.lower() != "xxx"
    )


def get_iflytek_open_platform_credentials() -> IflytekOpenPlatformCredentials:
    raw_config = _load_from_redis() or _load_from_database()
    credentials = _parse_credentials(raw_config)
    if credentials is None:
        raise ServiceException.from_error_code(
            CodeEnums.ServiceParamsError,
            extra_message=(
                "讯飞开放平台 is not configured. "
                "Please configure it in Platform Account Management first."
            ),
        )
    return credentials


def get_iflytek_open_platform_app_id(default: str = "") -> str:
    try:
        return get_iflytek_open_platform_credentials().app_id
    except ServiceException:
        return default


def _load_from_redis(cache_key: str = IFLYTEK_OPEN_PLATFORM_CACHE_KEY) -> Optional[str]:
    try:
        client = _redis_client()
        if client is None:
            return None
        value = client.get(cache_key)
        if isinstance(value, bytes):
            return value.decode("utf-8")
        return value
    except Exception as exc:  # pylint: disable=broad-exception-caught
        logger.warning(f"Failed to read platform account config from Redis: {exc}")
        return None


def _load_from_database(
    code: str = IFLYTEK_OPEN_PLATFORM_CODE,
    cache_key: str = IFLYTEK_OPEN_PLATFORM_CACHE_KEY,
) -> Optional[str]:
    try:
        import pymysql  # type: ignore[import-untyped]

        connection = pymysql.connect(
            host=os.getenv("MYSQL_HOST", "mysql"),
            port=int(os.getenv("MYSQL_PORT", "3306")),
            user=os.getenv("MYSQL_USER", "root"),
            password=os.getenv("MYSQL_PASSWORD", ""),
            database=_console_mysql_database(),
            charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor,
        )
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    SELECT `value`
                    FROM config_info
                    WHERE category = %s AND code = %s AND is_valid = 1
                    LIMIT 1
                    """,
                    (PLATFORM_ACCOUNT_CATEGORY, code),
                )
                row = cursor.fetchone()
                value = row.get("value") if row else None
                if value:
                    _store_to_redis(value, cache_key)
                return value
        finally:
            connection.close()
    except Exception as exc:  # pylint: disable=broad-exception-caught
        logger.warning(f"Failed to read platform account config from database: {exc}")
        return None


def _store_to_redis(
    value: str, cache_key: str = IFLYTEK_OPEN_PLATFORM_CACHE_KEY
) -> None:
    try:
        client = _redis_client()
        if client is not None:
            client.set(cache_key, value)
    except Exception as exc:  # pylint: disable=broad-exception-caught
        logger.warning(f"Failed to write platform account config to Redis: {exc}")


def _redis_client():  # type: ignore[no-untyped-def]
    global _REDIS_CLIENT
    if _REDIS_CLIENT is not None:
        return _REDIS_CLIENT

    redis_cluster_addr = os.getenv("REDIS_CLUSTER_ADDR", "")
    redis_addr = os.getenv("REDIS_ADDR", "")
    redis_password = os.getenv("REDIS_PASSWORD") or None

    if redis_cluster_addr:
        from rediscluster import RedisCluster

        startup_nodes = []
        for item in redis_cluster_addr.split(","):
            host, port = item.strip().split(":", 1)
            startup_nodes.append({"host": host, "port": port})
        _REDIS_CLIENT = RedisCluster(
            startup_nodes=startup_nodes,
            password=redis_password,
            decode_responses=True,
        )
        return _REDIS_CLIENT

    if redis_addr:
        import redis

        host, port = redis_addr.split(":", 1)
        _REDIS_CLIENT = redis.Redis(
            host=host,
            port=int(port),
            password=redis_password,
            db=_redis_database(),
            decode_responses=True,
        )
        return _REDIS_CLIENT

    return None


def _console_mysql_database() -> str:
    explicit = os.getenv("CONSOLE_MYSQL_DB")
    if explicit:
        return explicit

    mysql_url = os.getenv("MYSQL_URL", "")
    if mysql_url.startswith("jdbc:"):
        mysql_url = mysql_url[5:]
    parsed = urlparse(mysql_url)
    if parsed.path and parsed.path != "/":
        return parsed.path.lstrip("/")

    return "astron_console"


def _redis_database() -> int:
    value = os.getenv("REDIS_DATABASE_CONSOLE") or os.getenv("REDIS_DATABASE") or "0"
    try:
        return int(value)
    except ValueError:
        return 0


def _parse_credentials(
    raw_config: Optional[str],
) -> Optional[IflytekOpenPlatformCredentials]:
    if not raw_config:
        return None

    try:
        config = json.loads(raw_config)
    except json.JSONDecodeError:
        return None

    app_id = _clean(config.get("platformAppId"))
    api_key = _clean(config.get("platformApiKey"))
    api_secret = _clean(config.get("platformApiSecret"))

    if not app_id or not api_key or not api_secret:
        return None

    return IflytekOpenPlatformCredentials(
        app_id=app_id,
        api_key=api_key,
        api_secret=api_secret,
    )


def _clean(value: object) -> str:
    return str(value).strip() if value is not None else ""

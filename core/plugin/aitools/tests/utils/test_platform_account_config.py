from pathlib import Path
from unittest.mock import Mock

import pytest


def test_aitools_services_do_not_read_ai_credentials_from_environment() -> None:
    service_root = Path(__file__).parents[2] / "service"
    offenders = []

    for path in service_root.rglob("*.py"):
        text = path.read_text(encoding="utf-8")
        if any(
            token in text
            for token in (
                "os.getenv(AI_APP_ID_KEY",
                "os.getenv(AI_API_KEY_KEY",
                "os.getenv(AI_API_SECRET_KEY",
            )
        ):
            offenders.append(path.relative_to(service_root))

    assert offenders == []


def test_get_iflytek_credentials_reads_plain_redis_cache(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from plugin.aitools import platform_account_config

    fake_client = Mock()
    fake_client.get.return_value = (
        b'{"platformAppId":"app-id","platformApiKey":"api-key",'
        b'"platformApiSecret":"api-secret"}'
    )
    monkeypatch.setattr(platform_account_config, "_redis_client", lambda: fake_client)

    credentials = platform_account_config.get_iflytek_open_platform_credentials()

    assert credentials.app_id == "app-id"
    assert credentials.api_key == "api-key"
    assert credentials.api_secret == "api-secret"
    fake_client.get.assert_called_once_with(
        "platform_account_text:iflytek_open_platform"
    )


def test_get_iflytek_credentials_fails_clearly_when_missing(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from plugin.aitools import platform_account_config
    from plugin.aitools.common.exceptions.exceptions import ServiceException

    fake_client = Mock()
    fake_client.get.return_value = None
    monkeypatch.setattr(platform_account_config, "_redis_client", lambda: fake_client)
    monkeypatch.setattr(platform_account_config, "_load_from_database", lambda: None)

    with pytest.raises(ServiceException) as exc_info:
        platform_account_config.get_iflytek_open_platform_credentials()

    assert "讯飞开放平台 is not configured" in exc_info.value.message


def _fake_redis(values: dict) -> Mock:
    fake_client = Mock()
    fake_client.get.side_effect = lambda key: values.get(key)
    return fake_client


def test_capability_provider_defaults_to_iflytek(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from plugin.aitools import platform_account_config

    monkeypatch.setattr(
        platform_account_config, "_redis_client", lambda: _fake_redis({})
    )
    monkeypatch.setattr(
        platform_account_config, "_load_from_database", lambda *args: None
    )

    assert platform_account_config.get_capability_provider("ocr") == "iflytek"
    assert platform_account_config.uses_volcengine("ocr") is False


def test_capability_provider_reads_routing(monkeypatch: pytest.MonkeyPatch) -> None:
    from plugin.aitools import platform_account_config

    fake = _fake_redis(
        {
            "platform_account_text:ai_capability_routing": (
                '{"ocr":"VolcEngine","tts":"aliyun","imageGeneration":""}'
            )
        }
    )
    monkeypatch.setattr(platform_account_config, "_redis_client", lambda: fake)

    assert platform_account_config.uses_volcengine("ocr") is True
    assert platform_account_config.get_capability_provider("tts") == "iflytek"
    assert platform_account_config.get_capability_provider("imageGeneration") == (
        "iflytek"
    )


def test_volcengine_ark_config_applies_defaults(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from plugin.aitools import platform_account_config

    fake = _fake_redis(
        {
            "platform_account_text:volcengine": (
                '{"arkApiKey":"ark-key","arkBaseUrl":"https://ark.example/api/v3/"}'
            )
        }
    )
    monkeypatch.setattr(platform_account_config, "_redis_client", lambda: fake)

    config = platform_account_config.get_volcengine_ark_config()

    assert config.api_key == "ark-key"
    assert config.base_url == "https://ark.example/api/v3"
    assert config.vision_model == platform_account_config.VOLC_DEFAULT_VISION_MODEL
    assert config.image_model == platform_account_config.VOLC_DEFAULT_IMAGE_MODEL


def test_volcengine_ark_config_missing_key_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from plugin.aitools import platform_account_config
    from plugin.aitools.common.exceptions.exceptions import ServiceException

    monkeypatch.setattr(
        platform_account_config, "_redis_client", lambda: _fake_redis({})
    )
    monkeypatch.setattr(
        platform_account_config, "_load_from_database", lambda *args: None
    )

    with pytest.raises(ServiceException) as exc_info:
        platform_account_config.get_volcengine_ark_config()
    assert "方舟 API Key is not configured" in exc_info.value.message


def test_volcengine_speech_config_accepts_app_id_and_token(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from plugin.aitools import platform_account_config

    fake = _fake_redis(
        {
            "platform_account_text:volcengine": (
                '{"speechAppId":"app","speechAccessToken":"token"}'
            )
        }
    )
    monkeypatch.setattr(platform_account_config, "_redis_client", lambda: fake)

    config = platform_account_config.get_volcengine_speech_config()

    assert config.api_key == ""
    assert config.app_id == "app"
    assert config.access_token == "token"
    assert config.tts_resource_id == "seed-tts-1.0"

"""Tests for the Volcengine-RAG strategy, client identifier parsing and config."""

from typing import Any, Dict, List
from unittest.mock import AsyncMock, patch

import pytest

from knowledge.domain import platform_account_config
from knowledge.exceptions.exception import CustomException, ThirdPartyException
from knowledge.infra.volcengine import knowledge_client
from knowledge.infra.volcengine.knowledge_client import (
    CollectionRef,
    parse_collection_ref,
)
from knowledge.service.impl.volcengine_strategy import VolcengineRAGStrategy
from knowledge.service.rag_strategy_factory import RAGStrategyFactory

_SEARCH = "knowledge.infra.volcengine.knowledge_client.search_knowledge"
_LIST = "knowledge.infra.volcengine.knowledge_client.list_docs"


def _hit(doc_id: str, score: float, **extra: Any) -> Dict[str, Any]:
    item: Dict[str, Any] = {
        "point_id": f"{doc_id}-1",
        "content": f"content of {doc_id}",
        "score": score,
        "chunk_title": "title",
        "doc_info": {"doc_id": doc_id, "doc_name": f"{doc_id}.pdf"},
    }
    item.update(extra)
    return item


def test_factory_registers_volcengine() -> None:
    assert isinstance(
        RAGStrategyFactory.get_strategy("Volcengine-RAG"), VolcengineRAGStrategy
    )


@pytest.mark.parametrize(
    "identifier, expected",
    [
        ("kb-123abc", CollectionRef(resource_id="kb-123abc")),
        ("lumin_viking", CollectionRef(collection_name="lumin_viking", project="p0")),
        (" proj / lumin ", CollectionRef(collection_name="lumin", project="proj")),
    ],
)
def test_parse_collection_ref(identifier: str, expected: CollectionRef) -> None:
    assert parse_collection_ref(identifier, "p0") == expected


@pytest.mark.parametrize("identifier", ["", "  ", "/name", "proj/"])
def test_parse_collection_ref_rejects_invalid(identifier: str) -> None:
    with pytest.raises(CustomException):
        parse_collection_ref(identifier, "default")


@pytest.mark.asyncio
async def test_query_maps_merges_filters_and_sorts() -> None:
    responses: Dict[str, List[Dict[str, Any]]] = {
        "kb-a": [_hit("d1", 0.2), _hit("d2", 0.9)],
        "kb-b": [_hit("d3", 0.5, rerank_score=0.95, original_question="Q?")],
    }

    async def fake_search(repo_id: str, query: str, limit: int) -> List[Dict[str, Any]]:
        assert limit == 2
        return responses[repo_id]

    with patch(_SEARCH, new=AsyncMock(side_effect=fake_search)):
        result = await VolcengineRAGStrategy().query(
            "q", repo_ids=["kb-a", "kb-b"], top_k=2, threshold=0.3
        )

    assert result["count"] == 2
    first, second = result["results"]
    # rerank_score wins over the raw score when present
    assert (first["docId"], first["score"]) == ("d3", 0.95)
    assert first["context"] == "Q?\ncontent of d3"
    assert first["fileName"] == "d3.pdf"
    assert second["docId"] == "d2"


@pytest.mark.asyncio
async def test_query_does_not_drop_low_hybrid_scores() -> None:
    # Volcengine hybrid scores are often below the 0~1 thresholds callers send.
    with patch(_SEARCH, new=AsyncMock(return_value=[_hit("d1", 0.26)])):
        result = await VolcengineRAGStrategy().query(
            "q", repo_ids=["lumin_viking"], top_k=3, threshold=0.3
        )
    assert [r["docId"] for r in result["results"]] == ["d1"]


@pytest.mark.asyncio
async def test_query_respects_doc_ids() -> None:
    with patch(_SEARCH, new=AsyncMock(return_value=[_hit("d1", 0.8), _hit("d2", 0.7)])):
        result = await VolcengineRAGStrategy().query(
            "q", doc_ids=["d2"], repo_ids=["kb-a"], top_k=5
        )
    assert [r["docId"] for r in result["results"]] == ["d2"]


@pytest.mark.asyncio
async def test_list_docs_maps_fields() -> None:
    page = {
        "docs": [
            {
                "doc_id": "d1",
                "doc_name": "a.pdf",
                "doc_type": "pdf",
                "point_num": 11,
                "status": {"process_status": 0},
                "update_time": 1700000000,
            }
        ],
        "total": 10,
    }
    with patch(_LIST, new=AsyncMock(return_value=page)) as mocked:
        result = await VolcengineRAGStrategy().list_docs("lumin_viking", 0, 20)
    mocked.assert_awaited_once_with("lumin_viking", 0, 20)
    assert result["total"] == 10
    assert result["docs"][0] == {
        "docId": "d1",
        "name": "a.pdf",
        "type": "pdf",
        "size": None,
        "pointNum": 11,
        "processStatus": 0,
        "failedMsg": None,
        "createTime": None,
        "updateTime": 1700000000,
        "source": "",
    }


@pytest.mark.asyncio
async def test_write_operations_are_not_supported() -> None:
    strategy = VolcengineRAGStrategy()
    with pytest.raises(NotImplementedError):
        await strategy.split(fileUrl="x")
    with pytest.raises(NotImplementedError):
        await strategy.chunks_save("d", "g", "u", [])


def test_config_prefers_request_headers() -> None:
    platform_account_config.set_platform_account_config(
        {
            "volcengine": {
                "knowledge_api_key": " key-1 ",
                "knowledge_host": "",
                "knowledge_project": "",
            }
        }
    )
    with patch.object(platform_account_config, "_load_from_redis") as redis_loader:
        config = platform_account_config.get_volcengine_knowledge_config()
    redis_loader.assert_not_called()
    assert config == {
        "api_key": "key-1",
        "host": platform_account_config.DEFAULT_VOLCENGINE_KNOWLEDGE_HOST,
        "project": "default",
    }


def test_config_falls_back_to_volcengine_platform_account() -> None:
    platform_account_config.set_platform_account_config({})
    stored = {
        "arkApiKey": "ark",
        "knowledgeApiKey": "viking-key",
        "knowledgeHost": "api-knowledgebase.mlp.cn-shanghai.volces.com",
        "knowledgeProject": "proj",
    }
    with patch.object(
        platform_account_config, "_load_from_redis", return_value=stored
    ) as redis_loader:
        config = platform_account_config.get_volcengine_knowledge_config()
    redis_loader.assert_called_once_with(platform_account_config.VOLCENGINE_CACHE_KEY)
    assert config == {
        "api_key": "viking-key",
        "host": "api-knowledgebase.mlp.cn-shanghai.volces.com",
        "project": "proj",
    }


@pytest.mark.asyncio
async def test_missing_api_key_raises_third_party_error() -> None:
    platform_account_config.set_platform_account_config({})
    with (
        patch.object(platform_account_config, "_load_from_redis", return_value=None),
        patch.object(platform_account_config, "_load_from_database", return_value=None),
    ):
        with pytest.raises(ThirdPartyException) as exc_info:
            await knowledge_client.search_knowledge("lumin_viking", "q", 3)
    assert exc_info.value.code == 10030


@pytest.mark.asyncio
async def test_qps_limit_is_retried_then_succeeds() -> None:
    from vikingdb.exceptions import VikingException

    calls = {"n": 0}

    def flaky(*_args: Any, **_kwargs: Any) -> str:
        calls["n"] += 1
        if calls["n"] < 3:
            raise VikingException(1000029, "rid", "QPS limit")
        return "ok"

    with patch.object(knowledge_client, "QPS_RETRY_DELAYS_SECONDS", (0, 0, 0)):
        result = await knowledge_client._call_with_qps_retry(
            "search_knowledge", "kb", flaky
        )
    assert result == "ok"
    assert calls["n"] == 3


@pytest.mark.asyncio
async def test_other_errors_are_not_retried() -> None:
    from vikingdb.exceptions import VikingException

    calls = {"n": 0}

    def failing(*_args: Any, **_kwargs: Any) -> str:
        calls["n"] += 1
        raise VikingException(1000001, "rid", "invalid api key")

    with patch.object(knowledge_client, "QPS_RETRY_DELAYS_SECONDS", (0, 0, 0)):
        with pytest.raises(VikingException):
            await knowledge_client._call_with_qps_retry(
                "search_knowledge", "kb", failing
            )
    assert calls["n"] == 1

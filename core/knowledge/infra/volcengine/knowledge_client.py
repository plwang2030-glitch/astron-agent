"""
Volcengine knowledge base (VikingDB) client.

Thin wrapper over the official ``vikingdb-python-sdk`` knowledge client
(``vikingdb.knowledge.VikingKnowledge``) using API Key auth on the data plane.
The SDK is synchronous (requests), so calls run in a worker thread to keep the
FastAPI event loop free.

A knowledge base is addressed by an identifier stored as the console repo's
``coreRepoId``:

- ``kb-xxxx``                -> resource_id
- ``project/collection_name`` -> collection_name in the given project
- ``collection_name``         -> collection_name in the configured default project
"""

import asyncio
import hashlib
import threading
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple

from loguru import logger
from vikingdb import APIKey
from vikingdb.exceptions import VikingException
from vikingdb.knowledge import VikingKnowledge
from vikingdb.knowledge.collection import KnowledgeCollection
from vikingdb.knowledge.models.doc import ListDocsRequest
from vikingdb.knowledge.models.search import SearchKnowledgeRequest

from knowledge.consts.error_code import CodeEnum
from knowledge.domain.platform_account_config import get_volcengine_knowledge_config
from knowledge.exceptions.exception import CustomException, ThirdPartyException

REQUEST_TIMEOUT_SECONDS = 30
# Volcengine returns 1000029 when the knowledge base's QPS quota is exceeded (very low on the free
# tier). Multi-turn chat retrieves once per history message, so bursts are normal; back off and retry.
QPS_LIMIT_CODE = "1000029"
QPS_RETRY_DELAYS_SECONDS = (0.6, 1.2, 2.4)
# Readable hints for common Volcengine knowledge base error codes.
_ERROR_HINTS = {
    "1000001": (
        "API Key 无效：请在「平台账号管理 - 火山引擎（字节）」填写 API Key 的值"
        "（火山控制台 API Key 列表中复制的长字符串），不是名称 api-key-xxxx。"
    ),
    "1000002": "API Key 没有该知识库的权限，请检查 API Key 所属账号和项目。",
    "1000005": "找不到这个知识库，请检查知识库名称、项目或 kb- ID。",
    QPS_LIMIT_CODE: "火山知识库检索频率超过当前版本的 QPS 上限，已自动重试仍失败，请稍后再试或升级旗舰版。",
}
_CLIENTS: Dict[Tuple[str, str], VikingKnowledge] = {}
_CLIENTS_LOCK = threading.Lock()


@dataclass(frozen=True)
class CollectionRef:
    """Parsed knowledge base identifier."""

    resource_id: Optional[str] = None
    collection_name: Optional[str] = None
    project: Optional[str] = None

    def describe(self) -> str:
        if self.resource_id:
            return self.resource_id
        return f"{self.project}/{self.collection_name}"


def parse_collection_ref(identifier: str, default_project: str) -> CollectionRef:
    """Parse a stored knowledge base identifier (see module docstring)."""
    value = (identifier or "").strip()
    if not value:
        raise CustomException(
            CodeEnum.ParameterInvalid, "Volcengine knowledge base id is empty"
        )
    if value.startswith("kb-"):
        return CollectionRef(resource_id=value)
    if "/" in value:
        project, name = value.split("/", 1)
        project, name = project.strip(), name.strip()
        if not project or not name:
            raise CustomException(
                CodeEnum.ParameterInvalid,
                f"Invalid Volcengine knowledge base id: {value}",
            )
        return CollectionRef(collection_name=name, project=project)
    return CollectionRef(collection_name=value, project=default_project)


def _client(api_key: str, host: str) -> VikingKnowledge:
    cache_key = (host, hashlib.sha256(api_key.encode("utf-8")).hexdigest())
    with _CLIENTS_LOCK:
        client = _CLIENTS.get(cache_key)
        if client is None:
            client = VikingKnowledge(
                host=host,
                auth=APIKey(api_key=api_key),
                scheme="https",
                timeout=REQUEST_TIMEOUT_SECONDS,
            )
            _CLIENTS[cache_key] = client
        return client


def _collection(identifier: str) -> Tuple[KnowledgeCollection, CollectionRef]:
    config = get_volcengine_knowledge_config()
    if not config["api_key"]:
        raise ThirdPartyException(
            msg="火山引擎知识库 API Key 未配置，请在「平台账号管理 - 火山引擎」中填写",
            e=CodeEnum.VOLCENGINE_RAGError,
        )
    ref = parse_collection_ref(identifier, config["project"])
    client = _client(config["api_key"], config["host"])
    collection = client.collection(
        resource_id=ref.resource_id,
        collection_name=ref.collection_name,
        project_name=ref.project,
    )
    return collection, ref


async def _call_with_qps_retry(
    action: str, ref: str, func: Any, *args: Any, **kwargs: Any
) -> Any:
    """Run a blocking SDK call in a thread, retrying with backoff on the QPS-limit error."""
    for attempt in range(len(QPS_RETRY_DELAYS_SECONDS) + 1):
        try:
            return await asyncio.to_thread(func, *args, **kwargs)
        except VikingException as exc:
            if str(exc.code) != QPS_LIMIT_CODE or attempt == len(
                QPS_RETRY_DELAYS_SECONDS
            ):
                raise
            delay = QPS_RETRY_DELAYS_SECONDS[attempt]
            logger.info(
                f"Volcengine {action} hit QPS limit, kb={ref}, retry {attempt + 1} in {delay}s"
            )
            await asyncio.sleep(delay)
    raise RuntimeError("unreachable")


def _third_party_error(action: str, ref: str, exc: Exception) -> ThirdPartyException:
    if isinstance(exc, VikingException):
        logger.warning(
            f"Volcengine knowledge {action} failed, kb={ref}, code={exc.code}, "
            f"status={exc.status_code}, request_id={exc.request_id}, msg={exc.message}"
        )
        hint = _ERROR_HINTS.get(str(exc.code), "")
        return ThirdPartyException(
            msg=f"{hint}{action} failed: code={exc.code}, {exc.message}",
            e=CodeEnum.VOLCENGINE_RAGError,
        )
    logger.error(f"Volcengine knowledge {action} error, kb={ref}, error={exc}")
    return ThirdPartyException(
        msg=f"{action} failed: {exc}", e=CodeEnum.VOLCENGINE_RAGError
    )


async def search_knowledge(
    identifier: str, query: str, limit: int
) -> List[Dict[str, Any]]:
    """Run ``search_knowledge`` and return the raw result items as dicts."""
    collection, ref = _collection(identifier)
    request = SearchKnowledgeRequest(query=query, limit=limit)
    try:
        response = await _call_with_qps_retry(
            "search_knowledge",
            ref.describe(),
            collection.search_knowledge,
            request,
            timeout=REQUEST_TIMEOUT_SECONDS,
        )
    except Exception as exc:  # pylint: disable=broad-exception-caught
        raise _third_party_error("search_knowledge", ref.describe(), exc) from exc
    result = response.result
    items = [] if result is None else result.result_list
    logger.info(
        f"Volcengine search_knowledge ok, kb={ref.describe()}, limit={limit}, "
        f"hits={len(items)}, request_id={response.request_id}"
    )
    return [item.model_dump(exclude_none=True) for item in items]


async def list_docs(identifier: str, offset: int, limit: int) -> Dict[str, Any]:
    """List documents of a knowledge base: ``{"docs": [...], "total": int}``."""
    collection, ref = _collection(identifier)
    request = ListDocsRequest(offset=offset, limit=limit)
    try:
        response = await _call_with_qps_retry(
            "list_docs",
            ref.describe(),
            collection.list_docs,
            request,
            timeout=REQUEST_TIMEOUT_SECONDS,
        )
    except Exception as exc:  # pylint: disable=broad-exception-caught
        raise _third_party_error("list_docs", ref.describe(), exc) from exc
    result = response.result
    docs = [] if result is None else list(result.doc_list)
    total = 0 if result is None or result.total_num is None else result.total_num
    logger.info(
        f"Volcengine list_docs ok, kb={ref.describe()}, offset={offset}, "
        f"returned={len(docs)}, total={total}"
    )
    return {
        "docs": [doc.model_dump(exclude_none=True) for doc in docs],
        "total": total,
    }

"""
Volcengine RAG strategy.

Binds an existing Volcengine knowledge base (VikingDB). Documents are uploaded,
parsed and chunked on the Volcengine side; this strategy only retrieves
(``search_knowledge``) and lists documents. Split / chunk write operations are
not supported, matching how SparkDesk-RAG treats a provider-managed library.
"""

from typing import Any, Dict, List, Optional

from loguru import logger

from knowledge.infra.volcengine import knowledge_client
from knowledge.service.rag_strategy import RAGStrategy

_MAX_LIMIT = 50


def _to_result(item: Dict[str, Any]) -> Dict[str, Any]:
    """Map a ``search_knowledge`` hit to the shared chunk query result shape."""
    doc_info = item.get("doc_info") or {}
    content = item.get("content") or ""
    # FAQ hits return the answer in content; keep the question for context.
    question = item.get("original_question")
    context = f"{question}\n{content}" if question else content
    score = item.get("rerank_score")
    if score is None:
        score = item.get("score") or 0.0
    return {
        "score": score,
        "docId": doc_info.get("doc_id", ""),
        "chunkId": item.get("point_id", ""),
        "title": item.get("chunk_title") or doc_info.get("title") or "",
        "fileName": doc_info.get("doc_name", ""),
        "content": content,
        "context": context,
        "references": {},
    }


class VolcengineRAGStrategy(RAGStrategy):
    """Volcengine-RAG strategy implementation (retrieval only)."""

    async def query(
        self,
        query: str,
        doc_ids: Optional[List[str]] = None,
        repo_ids: Optional[List[str]] = None,
        top_k: Optional[int] = None,
        threshold: Optional[float] = 0,
        **kwargs: Any,
    ) -> Dict[str, Any]:
        limit = max(1, min(int(top_k or 5), _MAX_LIMIT))
        results: List[Dict[str, Any]] = []
        for repo_id in repo_ids or []:
            items = await knowledge_client.search_knowledge(repo_id, query, limit)
            results.extend(_to_result(item) for item in items)
        if doc_ids:
            allowed = set(doc_ids)
            results = [r for r in results if r["docId"] in allowed]
        # The similarity threshold is intentionally not applied: Volcengine's
        # hybrid (dense + sparse) scores are on a different scale from the 0~1
        # similarities other strategies return (relevant hits are often < 0.3),
        # and callers such as core/agent send a fixed threshold. Volcengine
        # already returns hits ranked by relevance, so results are cut by topN.
        results.sort(key=lambda r: r["score"], reverse=True)
        results = results[:limit]
        logger.info(
            f"Volcengine query done, repos={len(repo_ids or [])}, limit={limit}, "
            f"ignored_threshold={threshold}, returned={len(results)}"
        )
        return {"query": query, "count": len(results), "results": results}

    async def list_docs(
        self, repo_id: str, offset: int = 0, limit: int = 20, **kwargs: Any
    ) -> Dict[str, Any]:
        """List documents managed in the Volcengine knowledge base."""
        page = await knowledge_client.list_docs(repo_id, offset, limit)
        docs = []
        for doc in page["docs"]:
            status = doc.get("status") or {}
            docs.append(
                {
                    "docId": doc.get("doc_id", ""),
                    "name": doc.get("doc_name") or doc.get("title") or "",
                    "type": doc.get("doc_type", ""),
                    "size": doc.get("doc_size"),
                    "pointNum": doc.get("point_num"),
                    "processStatus": status.get("process_status"),
                    "failedMsg": status.get("failed_msg"),
                    "createTime": doc.get("create_time"),
                    "updateTime": doc.get("update_time"),
                    "source": doc.get("source", ""),
                }
            )
        return {"docs": docs, "total": page["total"]}

    async def split(
        self,
        fileUrl: Optional[str] = None,
        lengthRange: Optional[List[int]] = None,
        overlap: int = 16,
        resourceType: int = 0,
        separator: Optional[List[str]] = None,
        titleSplit: bool = False,
        cutOff: Optional[List[str]] = None,
        **kwargs: Any,
    ) -> List[Dict[str, Any]]:
        raise NotImplementedError(
            "Volcengine-RAG documents are managed in the Volcengine console."
        )

    async def chunks_save(
        self, docId: str, group: str, uid: str, chunks: List[Any], **kwargs: Any
    ) -> Any:
        raise NotImplementedError("Volcengine-RAG does not support chunks_save.")

    async def chunks_update(
        self,
        docId: str,
        group: str,
        uid: str,
        chunks: List[Dict[str, Any]],
        **kwargs: Any,
    ) -> Any:
        raise NotImplementedError("Volcengine-RAG does not support chunks_update.")

    async def chunks_delete(
        self, docId: str, chunkIds: List[str], **kwargs: Any
    ) -> Any:
        raise NotImplementedError("Volcengine-RAG does not support chunks_delete.")

    async def query_doc(self, docId: str, **kwargs: Any) -> List[dict]:
        raise NotImplementedError("Volcengine-RAG does not support query_doc.")

    async def query_doc_name(self, docId: str, **kwargs: Any) -> Optional[dict]:
        raise NotImplementedError("Volcengine-RAG does not support query_doc_name.")

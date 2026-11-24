import type { WorkflowNode } from '../types/domain';
import type { KnowledgeItem } from '../types/modal/add-knowledge';

/**
 * Repo type code sent to core/agent (see repo_type_map in workflow_agent_builder.py):
 * 2 CBG-RAG, 3 Ragflow-RAG, 4 Volcengine-RAG.
 */
export function knowledgeRepoType(tag?: string): number {
  if (tag === 'CBG-RAG') return 2;
  if (tag === 'Volcengine-RAG') return 4;
  return 3;
}

/** Toggle the stored repository descriptor and its corresponding engine ID together. */
export function toggleKnowledgeSelection(
  node: WorkflowNode,
  knowledge: KnowledgeItem,
  pro: boolean
): WorkflowNode {
  const externalId = knowledge.coreRepoId || knowledge.outerRepoId;
  if (!knowledge.id || !externalId) return node;
  const params = node.data.nodeParam;
  const repositories = params.repoList ?? [];
  const storedIds = pro ? params.repoIds : params.repoId;
  const ids = (
    Array.isArray(storedIds) ? storedIds : storedIds ? [storedIds] : []
  ).map(String);
  // Older saved workflows contain numeric catalog IDs even though current
  // API adapters normalize them to strings. Compare identity, not JSON type.
  const index = repositories.findIndex(
    item => String(item.id) === String(knowledge.id)
  );
  const repoList =
    index < 0
      ? [...repositories, knowledge]
      : repositories.filter((_, position) => position !== index);
  const previous = repositories[index];
  const selectedExternalId =
    previous?.coreRepoId || previous?.outerRepoId || externalId;
  const nextIds =
    index < 0
      ? ids.includes(externalId)
        ? ids
        : [...ids, externalId]
      : ids.filter(id => id !== selectedExternalId);
  return {
    ...node,
    data: {
      ...node.data,
      nodeParam: {
        ...params,
        repoList,
        ...(pro
          ? { repoIds: nextIds, repoType: knowledgeRepoType(knowledge.tag) }
          : { repoId: nextIds, ragType: knowledge.tag }),
      },
    },
  };
}

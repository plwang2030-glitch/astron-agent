import api from '@/utils/http';
import axios from 'axios';
import qs from 'qs';

// Define interfaces for getAgentList
export interface GetAgentListParams {
  pageIndex: number;
  pageSize: number;
  botStatus: number[] | null;
  sort: string;
  searchValue: string;
  version?: number;
  /** Versions to leave out, e.g. [3] hides workflows (they live under 资源管理 - 工作流). */
  excludeVersions?: number[];
}

export interface BotData {
  botId: number;
  maasId: number;
  uid: string;
  marketBotId: number;
  botName: string;
  botDesc: string;
  avatar: string;
  prompt: string;
  botType: number;
  version: number;
  supportContext: boolean;
  multiInput: Record<string, unknown>;
  botStatus: number;
  blockReason: string;
  releaseType: Array<Record<string, unknown>>;
  canOffline?: boolean;
  hotNum: string;
  isFavorite: number;
  af: string;
  createTime: string;
}

export interface GetAgentListResponse {
  pageData: BotData[];
  totalCount: number;
  pageSize: number;
  page: number;
  totalPages: number;
}

export async function enableBotFavorite(params: any) {
  const response = await api.get('/sparkbot/favorite', { params });
  return response;
}

export async function applySpark(params: any) {
  const response = await api.post(`/auth/apply`, params);
  return response;
}

export async function getRobotsAPI(params: any) {
  const response = await api.get('/sparkbot/listBots', { params });
  return response;
}

export async function createBotAPI(params: any): Promise<any> {
  const response = await api.post<any>(`/sparkbot/createBot`, params);
  return response;
}

export async function editBotAPI(params: any): Promise<any> {
  const response = await api.put<any>(`/sparkbot/updateBot`, params);
  return response;
}

export async function deleteBotAPI(id: number) {
  const response = await api.delete(`/sparkbot/deleteBot?id=${id}`);
  return response;
}

export async function getAvailableAppIdList(params: any) {
  const response = await api.get(`/sparkbot/getAvailableAppIdList`, {
    params,
  });
  return response;
}

export async function getFloatRobotAPI() {
  const response = await api.get(`/sparkbot/getFloatedBot`);
  return response;
}

export async function getFilterUser(params: any) {
  const response = await axios.get(
    '/api/v1/auth/common/fuzzy-search-domain-account',
    {
      params: params,
    }
  );
  return response.data.data;
}

export async function avatarImageGenerate(content: any) {
  const response = await api.get(`/image/gen?content=${content}`);
  return response;
}

export async function modelAuthStatus(appId: string): Promise<unknown> {
  const response = await api.get<unknown, unknown>(
    `/auth/status?appId=${appId}`
  );
  return response;
}

export async function getAutoAuthStatus(appId: string): Promise<unknown> {
  const response = await api.get<unknown, unknown>(
    `/auth/auto-auth/status?appId=${appId}`
  );
  return response;
}

export async function autoAuth(appId: any) {
  const response = await api.get(`/auth/auto-auth?appId=${appId}`);
  return response;
}

export async function getAppDetailAPI(appId: any) {
  const response = await api.get(`/common/app-detail?appId=${appId}`);
  return response;
}

export async function getModelConfigDetail(id: any, llmSource: any) {
  const response = await api.get(`/llm/inter1?id=${id}&llmSource=${llmSource}`);
  return response;
}
/** 获取智能体列表 */
export const getAgentList = async (
  params: GetAgentListParams
): Promise<GetAgentListResponse> => {
  const response = await api.post(`/my-bot/list`, {
    searchValue: params.searchValue,
    pageIndex: params.pageIndex,
    pageSize: params.pageSize,
    botStatus: params.botStatus,
    sort: params.sort,
    version: params.version,
    excludeVersions: params.excludeVersions,
  });
  return response as unknown as GetAgentListResponse;
};

export interface AgentCandidate {
  botId: number;
  name: string;
  description: string;
  avatar?: string;
  modelName?: string;
  updateTime?: string;
  /** Only published agents can be referenced; they run their published version. */
  published: boolean;
  hasKnowledge: boolean;
  skillCount: number;
  toolCount: number;
  mcpCount: number;
  /** Agents this agent routes to itself. */
  agentCount: number;
  /** Names of agents that already use this agent as a sub-agent. */
  usedBy: string[];
  selectable: boolean;
  reason?: string;
}

export interface AgentCandidateList {
  /** Agents that already use the current agent; adding them back would form a cycle. */
  usedBy: { botId: number; name: string }[];
  /** Reference levels above the current agent (0 when nobody uses it). */
  level: number;
  /** Maximum reference levels, e.g. 2 allows A → B → C. */
  maxDepth: number;
  candidates: AgentCandidate[];
}

/** 可作为子智能体的候选列表（能力页「添加智能体」） */
export const getAgentCandidates = async (
  botId?: number
): Promise<AgentCandidateList> => {
  const response = await api.get(`/bot/agent-candidates`, {
    params: botId ? { botId } : {},
  });
  return response as unknown as AgentCandidateList;
};

/** 复制bot */
export const copyBot = async (params: any) => {
  const response = await api.post(`/workflow/copy-bot`, qs.stringify(params), {
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
  });
  return response;
};

/** 删除智能体 */
export const deleteAgent = async (params: any): Promise<any> => {
  const response = await api.post(`/my-bot/delete`, qs.stringify(params), {
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
  });
  return response;
};

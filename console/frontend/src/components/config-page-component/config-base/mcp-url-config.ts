import type { AgentSkill } from '@/types/skill';
import type { AgentTool } from '@/types/plugin-store';
import type { AgentWorkflow } from '@/types/agent-workflow';
import type { AgentRef } from '@/types/agent-ref';

export const isValidMcpServerUrl = (url: string): boolean => {
  const value = url.trim();
  if (!value) {
    return true;
  }

  try {
    const parsedUrl = new URL(value);
    return parsedUrl.protocol === 'http:' || parsedUrl.protocol === 'https:';
  } catch {
    return false;
  }
};

export const normalizeMcpServerUrls = (urls?: unknown): string[] => {
  if (!urls) {
    return [];
  }

  let source = urls;
  if (typeof urls === 'string') {
    try {
      source = JSON.parse(urls);
    } catch {
      source = urls.split(',');
    }
  }

  if (!Array.isArray(source)) {
    return [];
  }

  return Array.from(
    new Set(
      source
        .map(item => (typeof item === 'string' ? item.trim() : ''))
        .filter(url => Boolean(url) && isValidMcpServerUrl(url))
    )
  );
};

export const normalizeSkills = (skills?: unknown): AgentSkill[] => {
  let source = skills;
  if (typeof skills === 'string') {
    try {
      source = JSON.parse(skills);
    } catch {
      return [];
    }
  }

  if (!Array.isArray(source)) {
    return [];
  }

  const seen = new Set<number>();
  const result: AgentSkill[] = [];
  for (const item of source) {
    const skillId = Number(item?.skillId ?? item?.id);
    if (!skillId || seen.has(skillId)) {
      continue;
    }
    seen.add(skillId);
    result.push({
      skillId,
      name: String(item?.name ?? ''),
      description: String(item?.description ?? ''),
    });
  }
  return result.slice(0, 30);
};

export const normalizeTools = (tools?: unknown): AgentTool[] => {
  let source = tools;
  if (typeof tools === 'string') {
    try {
      source = JSON.parse(tools);
    } catch {
      return [];
    }
  }

  if (!Array.isArray(source)) {
    return [];
  }

  const seen = new Set<string>();
  const result: AgentTool[] = [];
  for (const item of source) {
    const toolId = String(item?.toolId ?? '').trim();
    if (!toolId || seen.has(toolId)) {
      continue;
    }
    seen.add(toolId);
    result.push({
      toolId,
      name: String(item?.name ?? ''),
      description: String(item?.description ?? ''),
      icon: item?.icon ? String(item.icon) : undefined,
    });
  }
  return result;
};

export const normalizeWorkflows = (workflows?: unknown): AgentWorkflow[] => {
  let source = workflows;
  if (typeof workflows === 'string') {
    try {
      source = JSON.parse(workflows);
    } catch {
      return [];
    }
  }

  if (!Array.isArray(source)) {
    return [];
  }

  const seen = new Set<string>();
  const result: AgentWorkflow[] = [];
  for (const item of source) {
    const flowId = String(item?.flowId ?? '').trim();
    if (!flowId || seen.has(flowId)) {
      continue;
    }
    seen.add(flowId);
    result.push({
      flowId,
      name: String(item?.name ?? ''),
      description: String(item?.description ?? ''),
      icon: item?.icon ? String(item.icon) : undefined,
    });
  }
  return result.slice(0, 30);
};

export const MAX_AGENTS = 10;

export const normalizeAgents = (agents?: unknown): AgentRef[] => {
  let source = agents;
  if (typeof agents === 'string') {
    try {
      source = JSON.parse(agents);
    } catch {
      return [];
    }
  }

  if (!Array.isArray(source)) {
    return [];
  }

  const seen = new Set<number>();
  const result: AgentRef[] = [];
  for (const item of source) {
    const botId = Number(item?.botId);
    if (!Number.isInteger(botId) || botId <= 0 || seen.has(botId)) {
      continue;
    }
    seen.add(botId);
    result.push({
      botId,
      name: String(item?.name ?? ''),
      description: String(item?.description ?? ''),
      avatar: item?.avatar ? String(item.avatar) : undefined,
      direct: item?.direct === true ? true : undefined,
    });
  }
  return result.slice(0, MAX_AGENTS);
};

export const hasInvalidMcpServerUrls = (urls?: unknown): boolean => {
  if (!urls) {
    return false;
  }

  let source = urls;
  if (typeof urls === 'string') {
    try {
      source = JSON.parse(urls);
    } catch {
      source = urls.split(',');
    }
  }

  if (!Array.isArray(source)) {
    return false;
  }

  return source.some(item => {
    if (typeof item !== 'string') {
      return false;
    }
    const value = item.trim();
    return Boolean(value) && !isValidMcpServerUrl(value);
  });
};

/** 可选的内置工具组；目前只有 A股投研（行情、模拟账户、交易经验、策略进化） */
export const A_SHARE_TOOL_GROUP = 'a_share';

export const normalizeBuiltinTools = (value?: unknown): string[] => {
  let source = value;
  if (typeof value === 'string') {
    try {
      source = JSON.parse(value);
    } catch {
      return [];
    }
  }
  if (!Array.isArray(source)) {
    return [];
  }
  return Array.from(
    new Set(
      source.filter((item): item is string => item === A_SHARE_TOOL_GROUP)
    )
  );
};

import http, { baseURL, getLanguageCode } from '@/utils/http';
import { fetchSseWithContext } from '@/utils/sse-request';
import useSpaceStore from '@/store/space-store';

export interface BuilderSkill {
  skillId?: number | null;
  name: string;
  description?: string;
  folderName?: string;
  content?: string;
  /** draft: written by the builder, saved to the skill library on creation; library: existing skill */
  source: 'draft' | 'library';
}

export interface BuilderDraft {
  name?: string | null;
  description?: string | null;
  botType?: number | null;
  avatar?: string | null;
  prologue?: string | null;
  inputExamples: string[];
  instructions?: string | null;
  model?: { modelId: number; modelDomain?: string; modelName?: string } | null;
  skills: BuilderSkill[];
  knowledge: { id: number; name: string }[];
  tools: {
    toolId: string;
    name: string;
    description?: string;
    icon?: string;
  }[];
  /** Legacy; new drafts attach agents instead. */
  workflows: { flowId: string; name: string; description?: string }[];
  agents?: {
    botId: number;
    name: string;
    description?: string;
    avatar?: string;
  }[];
  runModes: {
    chat: boolean;
    schedule: { enabled: boolean; description?: string | null };
    wecom: { enabled: boolean };
  };
  approvals: { action: string; reason?: string | null }[];
  buildNote?: string | null;
  changes: { at: number; tool: string; summary: string }[];
}

export interface BuilderMessage {
  role: 'user' | 'assistant';
  content: string;
  attachments?: { name: string; chars: number }[];
  time?: number;
}

export interface BuildSession {
  id: string;
  title?: string;
  status: 'draft' | 'published';
  botId?: number | null;
  draft: BuilderDraft;
  messages: BuilderMessage[];
  undoDepth: number;
  replying: boolean;
  createTime?: string;
  updateTime?: string;
}

export interface BuilderAttachment {
  name: string;
  content: string;
}

export const createBuildSession = (params: {
  goal: string;
  attachments: BuilderAttachment[];
}): Promise<BuildSession> => http.post('/agent-builder/sessions', params);

export const listBuildSessions = (limit = 5): Promise<BuildSession[]> =>
  http.get('/agent-builder/sessions', { params: { limit } });

export const getBuildSession = (sessionId: string): Promise<BuildSession> =>
  http.get(`/agent-builder/sessions/${sessionId}`);

export const deleteBuildSession = (sessionId: string): Promise<void> =>
  http.delete(`/agent-builder/sessions/${sessionId}`);

export const updateBuildDraft = (
  sessionId: string,
  draft: BuilderDraft
): Promise<BuildSession> =>
  http.put(`/agent-builder/sessions/${sessionId}/draft`, { draft });

export const undoBuildDraft = (sessionId: string): Promise<BuildSession> =>
  http.post(`/agent-builder/sessions/${sessionId}/undo`);

export const publishBuildSession = (sessionId: string): Promise<number> =>
  http.post(`/agent-builder/sessions/${sessionId}/publish`);

export interface BuilderStreamHandlers {
  onStart?: (sseId: string) => void;
  onDelta?: (text: string) => void;
  onDone?: () => void;
  onError?: (message: string) => void;
}

/**
 * Consumes the builder / trial SSE stream (named events data / complete / end / error, the same
 * contract as /chat-message/bot-debug). Resolves when the stream ends; never retries.
 */
const streamAgentBuilder = (
  path: string,
  body: unknown,
  handlers: BuilderStreamHandlers,
  signal: AbortSignal
): Promise<void> => {
  let finished = false;
  const finish = (error?: string): void => {
    if (finished) return;
    finished = true;
    if (error) {
      handlers.onError?.(error);
    } else {
      handlers.onDone?.();
    }
  };
  return fetchSseWithContext(`${baseURL}${path}`, {
    getContext: () => {
      const { spaceId, spaceType, enterpriseId } = useSpaceStore.getState();
      return {
        languageCode: getLanguageCode(),
        accessToken: localStorage.getItem('accessToken'),
        spaceId,
        spaceType,
        enterpriseId,
      };
    },
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    openWhenHidden: true,
    signal,
    onmessage(event) {
      let data: Record<string, unknown> = {};
      try {
        data = event.data ? JSON.parse(event.data) : {};
      } catch {
        return;
      }
      if (event.event === 'error' || data.error) {
        finish(String(data.message || '请求失败'));
        return;
      }
      if (event.event === 'end') {
        finish();
        return;
      }
      if (event.event === 'complete') {
        return;
      }
      if (data.type === 'start') {
        handlers.onStart?.(String(data.sseId || ''));
        return;
      }
      const choices = data.choices as
        | { delta?: { content?: string } }[]
        | undefined;
      const text = choices?.[0]?.delta?.content;
      if (text) {
        handlers.onDelta?.(text);
      }
    },
    onclose() {
      finish();
    },
    onerror(err) {
      finish(err instanceof Error ? err.message : '连接失败');
      // Throwing stops fetch-event-source from retrying the POST.
      throw err;
    },
  }).catch(err => {
    if (!signal.aborted) {
      finish(err instanceof Error ? err.message : '连接失败');
    } else {
      finish();
    }
  });
};

export const streamBuilderChat = (
  sessionId: string,
  body: { text: string; attachments?: BuilderAttachment[] },
  handlers: BuilderStreamHandlers,
  signal: AbortSignal
): Promise<void> =>
  streamAgentBuilder(
    `/agent-builder/sessions/${sessionId}/chat`,
    body,
    handlers,
    signal
  );

export const streamBuilderTrial = (
  sessionId: string,
  body: { text: string; history: string[] },
  handlers: BuilderStreamHandlers,
  signal: AbortSignal
): Promise<void> =>
  streamAgentBuilder(
    `/agent-builder/sessions/${sessionId}/trial`,
    body,
    handlers,
    signal
  );

/** Text files only in v1: read on the client and sent as plain text. */
export const BUILDER_ATTACHMENT_ACCEPT = '.txt,.md,.markdown,.csv,.json';
export const BUILDER_ATTACHMENT_MAX_BYTES = 1024 * 1024;
export const BUILDER_ATTACHMENT_MAX_COUNT = 5;

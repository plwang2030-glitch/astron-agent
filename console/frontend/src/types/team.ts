/** One expert call in an expert-team turn (team lead -> expert, or expert -> its own expert). */
export interface TeamMember {
  callId: string;
  botId: number;
  /** The expert that called this one; empty for experts called by the team lead. */
  parentBotId?: number | null;
  name: string;
  query?: string;
  status: TeamStatus;
  /** Streamed / final output of the expert. */
  output: string;
  costMs?: number;
  startedAt?: number;
}

export type TeamStatus =
  | 'running'
  | 'ok'
  | 'empty'
  | 'timeout'
  | 'failed'
  | 'cancelled'
  | 'skipped';

export interface TeamFile {
  fileName: string;
  url: string;
  format?: string;
  size?: number;
}

export interface TeamState {
  members: TeamMember[];
  files: TeamFile[];
}

/** Event pushed by the backend as {"type":"team","team":{...}}. */
export interface TeamEvent {
  kind?: 'file';
  callId?: string;
  botId?: number;
  parentBotId?: number | null;
  name?: string;
  status?: TeamStatus;
  query?: string;
  delta?: string;
  content?: string;
  costMs?: number;
  fileName?: string;
  url?: string;
  format?: string;
  size?: number;
}

const MAX_OUTPUT = 8000;

export const emptyTeam = (): TeamState => ({ members: [], files: [] });

/** Applies one streamed team event; returns a new state. */
export const applyTeamEvent = (
  state: TeamState | undefined,
  event: TeamEvent
): TeamState => {
  const next: TeamState = state
    ? { members: [...state.members], files: [...state.files] }
    : emptyTeam();
  if (event.kind === 'file') {
    if (event.url && !next.files.some(f => f.url === event.url)) {
      next.files.push({
        fileName: event.fileName || '文件',
        url: event.url,
        format: event.format,
        size: event.size,
      });
    }
    return next;
  }
  if (!event.callId) return next;
  const index = next.members.findIndex(m => m.callId === event.callId);
  const existing = index >= 0 ? next.members[index] : undefined;
  const current: TeamMember = existing
    ? { ...existing }
    : {
        callId: event.callId,
        botId: event.botId ?? 0,
        parentBotId: event.parentBotId,
        name: event.name || '专家',
        status: 'running',
        output: '',
        startedAt: Date.now(),
      };
  if (event.query) current.query = event.query;
  if (event.status) current.status = event.status;
  if (event.delta) {
    current.output = (current.output + event.delta).slice(0, MAX_OUTPUT);
  }
  if (event.content !== undefined && event.status !== 'running') {
    current.output = event.content.slice(0, MAX_OUTPUT);
  }
  if (event.costMs !== undefined) current.costMs = event.costMs;
  if (index >= 0) next.members[index] = current;
  else next.members.push(current);
  return next;
};

/** Rebuilds the panel from a saved trace (history), where only final results are kept. */
export const teamFromTrace = (traceSource?: string): TeamState | undefined => {
  if (!traceSource) return undefined;
  let entries: unknown;
  try {
    // Saved traces may be several JSON arrays joined by commas.
    entries = JSON.parse(
      traceSource.trim().startsWith('[[') ? traceSource : `[${traceSource}]`
    );
  } catch {
    return undefined;
  }
  const flat: Record<string, unknown>[] = [];
  const walk = (value: unknown): void => {
    if (Array.isArray(value)) value.forEach(walk);
    else if (value && typeof value === 'object')
      flat.push(value as Record<string, unknown>);
  };
  walk(entries);
  const team = emptyTeam();
  flat.forEach((entry, i) => {
    if (entry.type === 'agent') {
      const args = (entry.arguments || {}) as { query?: string };
      team.members.push({
        callId: String(entry.callId || `trace-${i}`),
        botId: Number(entry.botId) || 0,
        parentBotId: (entry.parentBotId as number | null) ?? null,
        name:
          String(entry.name || '') ||
          String(entry.deskToolName || '').replace(/^智能体：/, '') ||
          '专家',
        query: args.query,
        status: (entry.status as TeamStatus) || 'ok',
        output: String(entry.content || ''),
        costMs:
          typeof entry.costMs === 'number'
            ? (entry.costMs as number)
            : undefined,
      });
    } else if (entry.type === 'file' && entry.url) {
      team.files.push({
        fileName: String(entry.fileName || '文件'),
        url: String(entry.url),
        format: entry.format as string | undefined,
        size: entry.size as number | undefined,
      });
    }
  });
  return team.members.length || team.files.length ? team : undefined;
};

import http from '@/utils/http';

/** 持仓 */
export interface AShareHolding {
  code: string;
  name: string;
  qty: number;
  /** 今日可卖（T+1：当日买入的不能卖） */
  sellableQty: number;
  costPerShare: number;
  price?: number;
  marketValue: number;
  pnl: number;
  pnlPct?: number;
  firstBuyDate: string;
  holdTradingDays: number;
}

/** 订单 */
export interface AShareOrder {
  orderId: number;
  side: 'BUY' | 'SELL';
  orderType: 'MARKET' | 'LIMIT';
  limitPrice?: number;
  code: string;
  name: string;
  qty: number;
  status: 'PENDING' | 'FILLED' | 'REJECTED' | 'CANCELLED';
  /** IMPORT：从实盘账户同步的持仓 */
  source: 'AGENT' | 'STRATEGY' | 'IMPORT';
  placedAt: string;
  fillDate?: string;
  fillTime?: string;
  fillPrice?: number;
  fees?: number;
  realizedPnl?: number;
  rejectReason?: string;
  reason?: string;
  expectation?: string;
  confidence?: number;
  strategyVersion?: number;
  review?: string;
}

export interface AShareOverview {
  initialCash: number;
  cash: number;
  frozenCash: number;
  marketValue: number;
  totalAssets: number;
  returnPct?: number;
  maxDrawdownPct?: number;
  realizedPnl: number;
  closedTrades: number;
  winRatePct?: number;
  totalFees: number;
  benchmark?: { name: string; from: string; returnPct?: number } | null;
  equityCurve: string[];
  positions: AShareHolding[];
  pendingOrders: AShareOrder[];
}

export interface AShareLesson {
  id: number;
  kind: 'LESSON' | 'PREFERENCE' | 'META';
  content: string;
  tags?: string;
  evidence?: string;
  support: number;
  contradict: number;
  confidence: number;
  status: 'CANDIDATE' | 'ACTIVE' | 'RETIRED';
  createTime: string;
  updateTime: string;
}

export interface ABacktestWindow {
  from: string;
  to: string;
  returnPct?: number;
  maxDrawdownPct?: number;
  trades: number;
  winRatePct?: number;
  score?: number;
  sharpe?: number;
  exposurePct?: number;
}

export interface AShareStrategyVersion {
  version: number;
  parentVersion?: number;
  status: 'ACTIVE' | 'RETIRED' | 'REJECTED' | 'TRIAL';
  createTime: string;
  description: string;
  rationale?: string;
  evaluation?: {
    adopted: boolean;
    /** 方法变更（如股票池改为规则筛选），不经闸门 */
    methodChange?: boolean;
    windowsWon?: number;
    significanceZ?: number;
    passedBacktest?: boolean;
    forward?: {
      since: string;
      days: number;
      needDays: number;
      verdict?: string;
      candidate?: ABacktestWindow;
      baseline?: ABacktestWindow;
    };
    reasons: string[];
    baseline: {
      version: number;
      outOfSample: ABacktestWindow;
      pool?: ABacktestWindow | null;
    };
    candidate: {
      version: number;
      outOfSample: ABacktestWindow;
      windows?: ABacktestWindow[];
      pool?: ABacktestWindow | null;
      regimes?: Record<string, { blocks: number; sharpe?: number }>;
    };
  } | null;
}

export interface AShareStrategy {
  current: {
    version: number;
    since?: string;
    description: string;
    rationale?: string;
    gate: string;
    params: {
      universe: string[];
      universeMode?: 'AUTO' | 'MANUAL';
      universeSize?: number;
    } & Record<string, unknown>;
    /** 当前实际交易的股票池（AUTO 为本月规则筛选结果） */
    universe?: string[];
    trials?: {
      version: number;
      description: string;
      forward?: {
        days: number;
        needDays: number;
        candidate?: ABacktestWindow;
        baseline?: ABacktestWindow;
      } | null;
    }[];
  };
  history: AShareStrategyVersion[];
}

export type AShareReviewType = 'PRE_OPEN' | 'CLOSE' | 'WEEKLY' | 'MONTHLY';

export interface AShareReport {
  id: number;
  tradeDate: string;
  triggerType: 'AUTO' | 'MANUAL';
  reviewType: AShareReviewType;
  period?: string;
  botId?: number;
  status: 'RUNNING' | 'DONE' | 'FAILED';
  content?: string;
  error?: string;
  costMs?: number;
  createTime: string;
}

export interface AShareSettings {
  /** 收盘复盘 */
  autoReview: boolean;
  preOpenReview: boolean;
  weeklyReview: boolean;
  monthlyReview: boolean;
  /** 每周自动进化搜索 + 每月自动回滚检查 */
  autoEvolve: boolean;
  autoTrade: boolean;
  /** 自选股出现信号时自动买入 */
  watchlistAutoTrade: boolean;
  /** 当前是否在连续竞价时段 */
  tradingNow: boolean;
  reviewBotId?: number | null;
  reviewBots: { botId: number; name: string; published: boolean }[];
}

export async function getAShareOverview(): Promise<AShareOverview> {
  return await http.get('/a-share/overview');
}

export async function getAShareOrders(status?: string): Promise<AShareOrder[]> {
  return await http.get('/a-share/orders', { params: { status, limit: 50 } });
}

export async function getAShareLessons(
  kind?: string,
  status?: string
): Promise<AShareLesson[]> {
  return await http.get('/a-share/lessons', { params: { kind, status } });
}

export async function retireAShareLesson(id: number): Promise<AShareLesson> {
  return await http.post('/a-share/lessons/retire', { id });
}

export async function getAShareStrategy(): Promise<AShareStrategy> {
  return await http.get('/a-share/strategy');
}

export interface AShareSearchResult {
  skipped?: string;
  tried: number;
  passed: number;
  baselineVersion: number;
  baselineOosScore?: number;
  trial?: { version: number; changes: string } | null;
  historyDays?: number;
  poolSize?: number;
  recordedLesson?: string;
  top: {
    changes: string;
    oosScore?: number;
    oosReturnPct?: number;
    oosMaxDrawdownPct?: number;
    exposurePct?: number;
    z?: number;
    windowsWon: number;
    windows?: number;
    trades: number;
    passed: boolean;
    reasons: string[];
  }[];
}

export async function searchAShareStrategy(): Promise<AShareSearchResult> {
  return await http.post('/a-share/strategy/search');
}

export async function getAShareReports(
  type?: AShareReviewType
): Promise<AShareReport[]> {
  return await http.get('/a-share/reports', { params: { type, limit: 30 } });
}

export async function runAShareReview(
  type: AShareReviewType
): Promise<AShareReport> {
  return await http.post('/a-share/review/run', { type });
}

export async function getAShareSettings(): Promise<AShareSettings> {
  return await http.get('/a-share/settings');
}

export async function saveAShareSettings(
  payload: Partial<
    Pick<
      AShareSettings,
      | 'autoReview'
      | 'preOpenReview'
      | 'weeklyReview'
      | 'monthlyReview'
      | 'autoEvolve'
      | 'autoTrade'
      | 'watchlistAutoTrade'
    >
  > & {
    reviewBotId?: number;
  }
): Promise<AShareSettings> {
  return await http.post('/a-share/settings', payload);
}

/** 规则筛选中通过的一只股票（金额单位：亿元） */
export interface AShareUniverseStock {
  code: string;
  name: string;
  avgAmount: number;
  cap: number;
  price: number;
}

export interface AShareUniverse {
  mode: 'AUTO' | 'MANUAL';
  rules: string;
  tradeSize: number;
  running: boolean;
  progress: string;
  live: string[];
  note?: string;
  snapshots: {
    month: string;
    asOf: string;
    candidates: number;
    tradeCodes: string[];
    poolSize: number;
    details: AShareUniverseStock[];
    added?: string[];
    removed?: string[];
  }[];
  /** 回测用的历史各月股票池（按当月时点数据） */
  pointInTime: { month: string; asOf: string; passed: number; top: string[] }[];
}

export async function getAShareUniverse(): Promise<AShareUniverse> {
  return await http.get('/a-share/universe');
}

export async function refreshAShareUniverse(): Promise<{
  started: boolean;
  message: string;
}> {
  return await http.post('/a-share/universe/refresh');
}

export async function switchAShareAutoUniverse(): Promise<{
  version: number;
  from: number;
  recordedLesson: string;
}> {
  return await http.post('/a-share/strategy/auto-universe');
}

export interface AShareWatchStock {
  code: string;
  name?: string;
  price?: number;
  pctChg?: number;
  /** 是否在本月规则股票池（交易池）里 */
  inUniverse: boolean;
  held: boolean;
  /** 按最新一根日线（盘中为未收盘数据）计算的策略入场信号 */
  signal?: boolean;
  rsi14?: number;
  volumeRatio?: number;
  close?: number;
  maFast?: number;
  maSlow?: number;
  ma60?: number;
  marketOk?: boolean;
  error?: string;
}

export interface AShareWatchlist {
  stocks: AShareWatchStock[];
  max: number;
  autoBuy: boolean;
  /** 当前实际效果（考虑策略自动交易开关） */
  effective: string;
  note: string;
}

export async function getAShareWatchlist(): Promise<AShareWatchlist> {
  return await http.get('/a-share/watchlist');
}

export async function addAShareWatch(
  codes: string[]
): Promise<AShareWatchlist> {
  return await http.post('/a-share/watchlist/add', { codes });
}

export async function removeAShareWatch(
  code: string
): Promise<AShareWatchlist> {
  return await http.post('/a-share/watchlist/remove', { code });
}

/** 智能体建议的动作 */
export type AShareSignalAction =
  | 'BUY'
  | 'ADD'
  | 'HOLD'
  | 'WATCH'
  | 'REDUCE'
  | 'SELL'
  | 'AVOID'
  | 'ALERT';

export interface AShareSignal {
  signalId: number;
  code: string;
  name?: string;
  action: AShareSignalAction;
  /** 评估周期（交易日）：1d / 3d / 5d / 10d */
  horizon: string;
  confidence?: number;
  entryLow?: number;
  entryHigh?: number;
  stopLoss?: number;
  targetPrice?: number;
  reason?: string;
  risk?: string;
  watch?: string;
  source: 'AGENT' | 'REVIEW';
  marketPhase?: string;
  dataQuality?: 'GOOD' | 'FAIR' | 'POOR';
  planQuality?: 'COMPLETE' | 'PARTIAL' | 'MINIMAL';
  /** 评估锚定的交易日与前复权收盘价 */
  anchorDate?: string;
  anchorPrice?: number;
  status: 'PENDING' | 'HIT' | 'MISS' | 'UNABLE' | 'SUPERSEDED';
  createTime: string;
  outcome?: {
    result: 'HIT' | 'MISS' | 'UNABLE';
    endDate?: string;
    endPrice?: number;
    returnPct?: number;
    maxAdversePct?: number;
    detail?: string;
  };
}

export interface AShareSignalGroup {
  key?: string;
  samples: number;
  hits: number;
  /** 样本是否达到可下结论的下限 */
  enough: boolean;
  hitRatePct?: number;
  avgReturnPct?: number;
  avgMaxAdversePct?: number;
  note?: string;
}

export interface AShareSignalStats {
  evaluated: number;
  unable: number;
  pending: number;
  minSample: number;
  rule: string;
  overall: AShareSignalGroup;
  byAction: AShareSignalGroup[];
  byHorizon: AShareSignalGroup[];
  byMarketPhase: AShareSignalGroup[];
  bySource: AShareSignalGroup[];
  byDataQuality: AShareSignalGroup[];
  byPlanQuality: AShareSignalGroup[];
}

export interface AShareSignalsResponse {
  stats: AShareSignalStats;
  signals: AShareSignal[];
}

export async function getAShareSignals(
  status?: string
): Promise<AShareSignalsResponse> {
  return await http.get('/a-share/signals', { params: { status } });
}

export async function evaluateAShareSignals(): Promise<{
  pending: number;
  hit: number;
  miss: number;
  unable: number;
  waiting: number;
}> {
  return await http.post('/a-share/signals/evaluate');
}

/** 某个数据块的可用情况 */
export type AShareBlockStatus =
  | 'FRESH'
  | 'STALE'
  | 'MISSING'
  | 'FETCH_FAILED'
  | 'NOT_SUPPORTED';

export interface AShareDataQuality {
  score: number;
  level: 'GOOD' | 'FAIR' | 'POOR';
  levelLabel: string;
  blocks: Record<string, AShareBlockStatus>;
  limitations: string[];
}

export interface AShareMarketOverview {
  indices: {
    name: string;
    price?: number;
    pctChg?: number;
    amountYi?: number;
  }[];
  breadth?: {
    date?: string;
    advances?: number;
    declines?: number;
    unchanged?: number;
    limitUp?: number;
    limitDown?: number;
    brokenLimitUp?: number;
  };
  industryLeaders?: { name: string; members: number; pctChg: number }[];
  industryLaggards?: { name: string; members: number; pctChg: number }[];
  hotConcepts?: { name: string; members: number; pctChg: number }[];
  marketPhase: string;
  marketPhaseLabel: string;
  rule: string;
  dataQuality: AShareDataQuality;
}

export async function getAShareMarket(): Promise<AShareMarketOverview> {
  return await http.get('/a-share/market');
}

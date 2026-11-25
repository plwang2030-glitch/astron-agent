import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Dropdown,
  Empty,
  Input,
  Popconfirm,
  Select,
  Spin,
  Switch,
  Table,
  Tabs,
  Tag,
  Tooltip,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  DownOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import MarkdownRender from '@/components/markdown-render';
import {
  AShareHolding,
  AShareLesson,
  AShareOrder,
  AShareOverview,
  AShareReport,
  AShareReviewType,
  AShareSearchResult,
  AShareSettings,
  AShareStrategy,
  AShareStrategyVersion,
  AShareMarketOverview,
  AShareSignal,
  AShareSignalGroup,
  AShareSignalsResponse,
  AShareUniverse,
  AShareWatchlist,
  addAShareWatch,
  evaluateAShareSignals,
  getAShareLessons,
  getAShareOrders,
  getAShareOverview,
  getAShareReports,
  getAShareSettings,
  getAShareStrategy,
  getAShareMarket,
  getAShareSignals,
  getAShareUniverse,
  getAShareWatchlist,
  refreshAShareUniverse,
  removeAShareWatch,
  retireAShareLesson,
  runAShareReview,
  saveAShareSettings,
  searchAShareStrategy,
  switchAShareAutoUniverse,
} from '@/services/a-share';

const money = (v?: number | null): string =>
  v === undefined || v === null
    ? '-'
    : Number(v).toLocaleString('zh-CN', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      });

/** A 股习惯：涨红跌绿 */
const pnlColor = (v?: number | null): string =>
  v === undefined || v === null || Number(v) === 0
    ? 'text-[#202124]'
    : Number(v) > 0
      ? 'text-[#d92d20]'
      : 'text-[#079455]';

const pct = (v?: number | null): string =>
  v === undefined || v === null
    ? '-'
    : `${Number(v) > 0 ? '+' : ''}${Number(v).toFixed(2)}%`;

const ORDER_STATUS: Record<
  AShareOrder['status'],
  { text: string; color: string }
> = {
  PENDING: { text: '待成交', color: 'processing' },
  FILLED: { text: '已成交', color: 'success' },
  REJECTED: { text: '未成交', color: 'error' },
  CANCELLED: { text: '已撤单', color: 'default' },
};

const LESSON_KIND: Record<AShareLesson['kind'], string> = {
  LESSON: '经验',
  PREFERENCE: '偏好',
  META: '进化记录',
};

const LESSON_STATUS: Record<
  AShareLesson['status'],
  { text: string; color: string }
> = {
  CANDIDATE: { text: '候选', color: 'gold' },
  ACTIVE: { text: '生效', color: 'green' },
  RETIRED: { text: '已停用', color: 'default' },
};

const VERSION_STATUS: Record<
  AShareStrategyVersion['status'],
  { text: string; color: string }
> = {
  ACTIVE: { text: '当前', color: 'green' },
  RETIRED: { text: '曾采纳', color: 'blue' },
  REJECTED: { text: '被拒绝', color: 'default' },
  TRIAL: { text: '前进测试中', color: 'orange' },
};

const SIGNAL_ACTION: Record<string, { text: string; color: string }> = {
  BUY: { text: '买入', color: 'red' },
  ADD: { text: '加仓', color: 'red' },
  HOLD: { text: '持有', color: 'orange' },
  WATCH: { text: '观望', color: 'default' },
  REDUCE: { text: '减仓', color: 'green' },
  SELL: { text: '卖出', color: 'green' },
  AVOID: { text: '回避', color: 'green' },
  ALERT: { text: '风险预警', color: 'volcano' },
};

const SIGNAL_STATUS: Record<string, { text: string; color: string }> = {
  PENDING: { text: '待评估', color: 'processing' },
  HIT: { text: '命中', color: 'success' },
  MISS: { text: '未命中', color: 'error' },
  UNABLE: { text: '不计入', color: 'default' },
  SUPERSEDED: { text: '已被覆盖', color: 'default' },
};

const QUALITY_LABEL: Record<string, string> = {
  GOOD: '好',
  FAIR: '一般',
  POOR: '差',
  COMPLETE: '完整',
  PARTIAL: '部分',
  MINIMAL: '简略',
};

const REVIEW_TYPE: Record<AShareReviewType, string> = {
  PRE_OPEN: '盘前计划',
  CLOSE: '收盘复盘',
  WEEKLY: '周复盘',
  MONTHLY: '月复盘',
};

const REPORT_STATUS: Record<
  AShareReport['status'],
  { text: string; color: string }
> = {
  RUNNING: { text: '复盘中', color: 'processing' },
  DONE: { text: '已完成', color: 'success' },
  FAILED: { text: '未完成', color: 'error' },
};

const Stat = ({
  label,
  value,
  className,
  hint,
}: {
  label: string;
  value: React.ReactNode;
  className?: string;
  hint?: React.ReactNode;
}): React.ReactElement => (
  <div className="rounded-lg border border-[#e5e7eb] bg-white px-5 py-4 shadow-sm">
    <div className="text-sm text-[#6b7280]">{label}</div>
    <div
      className={`mt-2 text-xl font-semibold ${className || 'text-[#202124]'}`}
    >
      {value}
    </div>
    {hint && <div className="mt-1 text-xs text-[#9ca3af]">{hint}</div>}
  </div>
);

const AShareTradingPage = (): React.ReactElement => {
  const [loading, setLoading] = useState(false);
  const [overview, setOverview] = useState<AShareOverview | null>(null);
  const [orders, setOrders] = useState<AShareOrder[]>([]);
  const [orderStatus, setOrderStatus] = useState<string | undefined>();
  const [lessons, setLessons] = useState<AShareLesson[]>([]);
  const [lessonKind, setLessonKind] = useState<string | undefined>();
  const [strategy, setStrategy] = useState<AShareStrategy | null>(null);
  const [reports, setReports] = useState<AShareReport[]>([]);
  const [reportType, setReportType] = useState<AShareReviewType | undefined>();
  const [settings, setSettings] = useState<AShareSettings | null>(null);
  const [savingSettings, setSavingSettings] = useState(false);
  const [starting, setStarting] = useState(false);
  const [searching, setSearching] = useState(false);
  const [searchResult, setSearchResult] = useState<AShareSearchResult | null>(
    null
  );
  const [error, setError] = useState<string | null>(null);
  const [universe, setUniverse] = useState<AShareUniverse | null>(null);
  const [universeMonth, setUniverseMonth] = useState<string | undefined>();
  const [switching, setSwitching] = useState(false);
  const [watchlist, setWatchlist] = useState<AShareWatchlist | null>(null);
  const [watchInput, setWatchInput] = useState('');
  const [watchSaving, setWatchSaving] = useState(false);
  const [signals, setSignals] = useState<AShareSignalsResponse | null>(null);
  const [signalStatus, setSignalStatus] = useState<string | undefined>();
  const [evaluating, setEvaluating] = useState(false);
  const [marketOverview, setMarketOverview] =
    useState<AShareMarketOverview | null>(null);

  const loadSignals = useCallback(async (status?: string) => {
    try {
      setSignals(await getAShareSignals(status));
    } catch {
      // http 已提示错误
    }
  }, []);

  const loadMarket = useCallback(async () => {
    try {
      setMarketOverview(await getAShareMarket());
    } catch {
      // http 已提示错误
    }
  }, []);

  const handleEvaluate = async (): Promise<void> => {
    setEvaluating(true);
    try {
      const r = await evaluateAShareSignals();
      message.success(
        r.hit + r.miss + r.unable > 0
          ? `已评估：命中 ${r.hit}，未命中 ${r.miss}，不计入 ${r.unable}`
          : `${r.waiting} 条还没到评估周期`
      );
      await Promise.all([loadSignals(signalStatus), loadLessons(lessonKind)]);
    } catch {
      // http 已提示错误
    } finally {
      setEvaluating(false);
    }
  };

  const loadWatchlist = useCallback(async () => {
    try {
      setWatchlist(await getAShareWatchlist());
    } catch {
      // http 已提示错误
    }
  }, []);

  const handleAddWatch = async (): Promise<void> => {
    const codes = watchInput
      .split(/[\s,，、;；]+/)
      .map(c => c.trim())
      .filter(Boolean);
    if (codes.length === 0) {
      return;
    }
    setWatchSaving(true);
    try {
      setWatchlist(await addAShareWatch(codes));
      setWatchInput('');
      message.success('已加入自选');
    } catch {
      // http 已提示错误
    } finally {
      setWatchSaving(false);
    }
  };

  const handleRemoveWatch = async (code: string): Promise<void> => {
    try {
      setWatchlist(await removeAShareWatch(code));
      message.success('已移出自选');
    } catch {
      // http 已提示错误
    }
  };

  const loadUniverse = useCallback(async () => {
    try {
      setUniverse(await getAShareUniverse());
    } catch {
      // http 已提示错误
    }
  }, []);

  const loadOrders = useCallback(async (status?: string) => {
    try {
      setOrders(await getAShareOrders(status));
    } catch {
      // http 已提示错误
    }
  }, []);

  const loadLessons = useCallback(async (kind?: string) => {
    try {
      setLessons(await getAShareLessons(kind));
    } catch {
      // http 已提示错误
    }
  }, []);

  const loadReports = useCallback(async (type?: AShareReviewType) => {
    try {
      setReports(await getAShareReports(type));
    } catch {
      // http 已提示错误
    }
  }, []);

  const loadAll = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [ov, st, se] = await Promise.all([
        getAShareOverview(),
        getAShareStrategy(),
        getAShareSettings(),
      ]);
      setOverview(ov);
      setStrategy(st);
      setSettings(se);
    } catch (e: unknown) {
      const msg = (e as { message?: string })?.message;
      setError(msg || '加载失败，请稍后重试');
    } finally {
      setLoading(false);
    }
    await Promise.all([
      loadOrders(orderStatus),
      loadLessons(lessonKind),
      loadReports(reportType),
      loadUniverse(),
      loadWatchlist(),
      loadSignals(signalStatus),
      loadMarket(),
    ]);
  }, [
    loadWatchlist,
    loadSignals,
    loadMarket,
    signalStatus,
    loadOrders,
    loadLessons,
    loadReports,
    loadUniverse,
    orderStatus,
    lessonKind,
    reportType,
  ]);

  useEffect(() => {
    loadAll();
    // 只在进入页面时加载一次；筛选条件变化时单独刷新
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 有复盘在进行时，定时刷新日报
  const running = reports.some(r => r.status === 'RUNNING');
  useEffect(() => {
    if (!running) {
      return;
    }
    const timer = window.setInterval(() => {
      loadReports(reportType);
    }, 8000);
    return () => window.clearInterval(timer);
  }, [running, loadReports, reportType]);

  // 股票池更新在后台进行时，定时刷新进度
  const universeRunning = universe?.running;
  useEffect(() => {
    if (!universeRunning) {
      return;
    }
    const timer = window.setInterval(() => {
      loadUniverse();
    }, 15000);
    return () => window.clearInterval(timer);
  }, [universeRunning, loadUniverse]);

  const handleRefreshUniverse = async (): Promise<void> => {
    try {
      const r = await refreshAShareUniverse();
      if (r.started) {
        message.success(r.message);
      } else {
        message.info(r.message);
      }
      await loadUniverse();
    } catch {
      // http 已提示错误
    }
  };

  const handleSwitchUniverse = async (): Promise<void> => {
    setSwitching(true);
    try {
      const r = await switchAShareAutoUniverse();
      message.success(`已生成 v${r.version}：股票池改为规则筛选`);
      setStrategy(await getAShareStrategy());
      await Promise.all([loadUniverse(), loadLessons(lessonKind)]);
    } catch {
      // http 已提示错误
    } finally {
      setSwitching(false);
    }
  };

  // 交易时段每分钟刷新账户（盘中会自动成交、止损止盈）
  const tradingNow = settings?.tradingNow;
  useEffect(() => {
    if (!tradingNow) {
      return;
    }
    const timer = window.setInterval(async () => {
      try {
        setOverview(await getAShareOverview());
      } catch {
        // http 已提示错误
      }
    }, 60000);
    return () => window.clearInterval(timer);
  }, [tradingNow]);

  const handleRunReview = async (type: AShareReviewType): Promise<void> => {
    setStarting(true);
    try {
      await runAShareReview(type);
      message.success(
        `已开始${REVIEW_TYPE[type]}，通常需要 1–5 分钟，完成后会通知你`
      );
      await loadReports(reportType);
    } catch {
      // http 已提示错误
    } finally {
      setStarting(false);
    }
  };

  const updateSettings = async (
    patch: Parameters<typeof saveAShareSettings>[0]
  ): Promise<void> => {
    setSavingSettings(true);
    try {
      setSettings(await saveAShareSettings(patch));
      message.success('已保存');
    } catch {
      // http 已提示错误
    } finally {
      setSavingSettings(false);
    }
  };

  const handleSearch = async (): Promise<void> => {
    setSearching(true);
    try {
      const r = await searchAShareStrategy();
      setSearchResult(r);
      message.success(
        r.trial
          ? `v${r.trial.version} 通过回测闸门，进入前进测试`
          : `试了 ${r.tried} 个候选，没有通过闸门，策略保持不变`
      );
      setStrategy(await getAShareStrategy());
      await loadLessons(lessonKind);
    } catch {
      // http 已提示错误
    } finally {
      setSearching(false);
    }
  };

  const handleRetire = async (id: number): Promise<void> => {
    try {
      await retireAShareLesson(id);
      message.success('已停用');
      await loadLessons(lessonKind);
    } catch {
      // http 已提示错误
    }
  };

  const holdingColumns: ColumnsType<AShareHolding> = [
    {
      title: '股票',
      key: 'stock',
      render: (_, r) => (
        <span>
          {r.name} <span className="text-[#9ca3af]">{r.code}</span>
        </span>
      ),
    },
    { title: '持股', dataIndex: 'qty', align: 'right' },
    {
      title: (
        <Tooltip title="T+1：当日买入的股份下一个交易日才能卖">
          今日可卖
        </Tooltip>
      ),
      dataIndex: 'sellableQty',
      align: 'right',
    },
    { title: '成本价', dataIndex: 'costPerShare', align: 'right' },
    {
      title: '现价',
      dataIndex: 'price',
      align: 'right',
      render: v => v ?? '-',
    },
    {
      title: '市值',
      dataIndex: 'marketValue',
      align: 'right',
      render: v => money(v),
    },
    {
      title: '浮动盈亏',
      key: 'pnl',
      align: 'right',
      render: (_, r) => (
        <span className={pnlColor(r.pnl)}>
          {money(r.pnl)}（{pct(r.pnlPct)}）
        </span>
      ),
    },
    { title: '买入日', dataIndex: 'firstBuyDate' },
    { title: '持有交易日', dataIndex: 'holdTradingDays', align: 'right' },
  ];

  const orderColumns: ColumnsType<AShareOrder> = [
    { title: '单号', dataIndex: 'orderId', width: 70 },
    {
      title: '方向',
      dataIndex: 'side',
      width: 70,
      render: v => (
        <span className={v === 'BUY' ? 'text-[#d92d20]' : 'text-[#079455]'}>
          {v === 'BUY' ? '买入' : '卖出'}
        </span>
      ),
    },
    {
      title: '股票',
      key: 'stock',
      render: (_, r) => (
        <span>
          {r.name} <span className="text-[#9ca3af]">{r.code}</span>
        </span>
      ),
    },
    {
      title: '类型',
      key: 'type',
      width: 100,
      render: (_, r) =>
        r.orderType === 'LIMIT' ? `限价 ${r.limitPrice ?? ''}` : '市价',
    },
    { title: '数量', dataIndex: 'qty', align: 'right', width: 80 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (v: AShareOrder['status'], r) => (
        <Tooltip title={r.rejectReason}>
          <Tag color={ORDER_STATUS[v]?.color}>{ORDER_STATUS[v]?.text || v}</Tag>
        </Tooltip>
      ),
    },
    {
      title: '来源',
      dataIndex: 'source',
      width: 90,
      render: (v, r) =>
        v === 'STRATEGY'
          ? `策略 v${r.strategyVersion ?? ''}`
          : v === 'IMPORT'
            ? '实盘同步'
            : '智能体',
    },
    {
      title: '下单时间',
      dataIndex: 'placedAt',
      width: 170,
      render: v => String(v).replace('T', ' '),
    },
    {
      title: '成交',
      key: 'fill',
      render: (_, r) =>
        r.fillDate
          ? `${String(r.fillTime || r.fillDate)
              .replace('T', ' ')
              .slice(0, 16)} @ ${r.fillPrice}`
          : r.rejectReason || '-',
    },
    {
      title: '已实现盈亏',
      dataIndex: 'realizedPnl',
      align: 'right',
      render: v =>
        v === undefined || v === null ? (
          '-'
        ) : (
          <span className={pnlColor(v)}>{money(v)}</span>
        ),
    },
  ];

  const lessonColumns: ColumnsType<AShareLesson> = [
    { title: '#', dataIndex: 'id', width: 60 },
    {
      title: '类型',
      dataIndex: 'kind',
      width: 90,
      render: (v: AShareLesson['kind']) => LESSON_KIND[v] || v,
    },
    { title: '内容', dataIndex: 'content' },
    {
      title: '证据',
      key: 'evidence',
      width: 110,
      render: (_, r) =>
        r.kind === 'LESSON' ? `支持 ${r.support} / 反驳 ${r.contradict}` : '-',
    },
    {
      title: '置信度',
      dataIndex: 'confidence',
      width: 80,
      render: (v, r) => (r.kind === 'LESSON' ? Number(v).toFixed(2) : '-'),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (v: AShareLesson['status']) => (
        <Tag color={LESSON_STATUS[v]?.color}>{LESSON_STATUS[v]?.text || v}</Tag>
      ),
    },
    {
      title: '操作',
      key: 'op',
      width: 80,
      render: (_, r) =>
        r.status !== 'RETIRED' && r.kind !== 'META' ? (
          <Popconfirm
            title="停用后不再注入智能体提示词，确定？"
            onConfirm={() => handleRetire(r.id)}
          >
            <Button type="link" size="small">
              停用
            </Button>
          </Popconfirm>
        ) : null,
    },
  ];

  const versionColumns: ColumnsType<AShareStrategyVersion> = [
    { title: '版本', dataIndex: 'version', width: 70, render: v => `v${v}` },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (v: AShareStrategyVersion['status'], r) => (
        <>
          <Tag color={VERSION_STATUS[v]?.color}>
            {VERSION_STATUS[v]?.text || v}
          </Tag>
          {r.evaluation?.methodChange && (
            <Tooltip title="方法变更不经闸门：旧版本的手选股票池含事后挑选偏差，其回测不是公平基线">
              <Tag color="purple" className="mt-1">
                方法变更
              </Tag>
            </Tooltip>
          )}
        </>
      ),
    },
    {
      title: '样本外（候选 vs 基线）',
      key: 'oos',
      width: 330,
      render: (_, r) => {
        const e = r.evaluation;
        if (!e) {
          return '-';
        }
        const c = e.candidate?.outOfSample;
        const b = e.baseline?.outOfSample;
        return (
          <div className="text-xs leading-5">
            <div>
              夏普 {c?.sharpe ?? c?.score ?? '-'} vs{' '}
              {b?.sharpe ?? b?.score ?? '-'}（v{e.baseline?.version}）
              {e.significanceZ !== undefined
                ? `，显著性 z=${e.significanceZ}`
                : ''}
            </div>
            <div className="text-[#6b7280]">
              收益 {pct(c?.returnPct)} vs {pct(b?.returnPct)}，回撤{' '}
              {c?.maxDrawdownPct ?? '-'}% vs {b?.maxDrawdownPct ?? '-'}%，
              {c?.trades ?? 0} 笔
              {e.windowsWon !== undefined
                ? `，窗口赢 ${e.windowsWon}/${e.candidate?.windows?.length ?? 3}`
                : ''}
              {c?.exposurePct !== undefined
                ? `，仓位占用 ${c.exposurePct}% vs ${b?.exposurePct ?? '-'}%`
                : ''}
              {e.candidate?.pool?.sharpe !== undefined
                ? `，扩展池夏普 ${e.candidate.pool.sharpe} vs ${e.baseline?.pool?.sharpe ?? '-'}`
                : ''}
              {e.forward?.candidate
                ? `；前进测试 ${e.forward.days} 天：收益 ${pct(e.forward.candidate.returnPct)} vs ${pct(e.forward.baseline?.returnPct)}`
                : ''}
            </div>
          </div>
        );
      },
    },
    {
      title: '改动理由 / 结论',
      key: 'why',
      render: (_, r) => (
        <div className="text-xs leading-5">
          <div>{r.rationale || '-'}</div>
          {r.evaluation?.reasons?.length ? (
            <div className="text-[#b42318]">
              未采纳：{r.evaluation.reasons.join('；')}
            </div>
          ) : null}
        </div>
      ),
    },
    {
      title: '时间',
      dataIndex: 'createTime',
      width: 170,
      render: v => String(v).replace('T', ' ').slice(0, 19),
    },
  ];

  const benchmark = overview?.benchmark;
  const reviewBotOptions = useMemo(
    () =>
      (settings?.reviewBots || []).map(b => ({
        value: b.botId,
        label: `${b.name}${b.published ? '' : '（未发布，不能运行）'}`,
      })),
    [settings]
  );

  const tabs = [
    {
      key: 'positions',
      label: `持仓（${overview?.positions?.length ?? 0}）`,
      children: (
        <>
          <Table
            rowKey="code"
            size="middle"
            columns={holdingColumns}
            dataSource={overview?.positions || []}
            pagination={false}
            locale={{ emptyText: <Empty description="空仓" /> }}
          />
          {(overview?.pendingOrders?.length ?? 0) > 0 && (
            <>
              <div className="mb-2 mt-6 text-base font-medium text-[#202124]">
                待成交订单（下一交易日开盘价成交）
              </div>
              <Table
                rowKey="orderId"
                size="small"
                columns={orderColumns}
                dataSource={overview?.pendingOrders || []}
                pagination={false}
              />
            </>
          )}
        </>
      ),
    },
    {
      key: 'orders',
      label: '订单',
      children: (
        <>
          <div className="mb-3">
            <Select
              allowClear
              placeholder="全部状态"
              className="w-40"
              value={orderStatus}
              onChange={v => {
                setOrderStatus(v);
                loadOrders(v);
              }}
              options={Object.entries(ORDER_STATUS).map(([value, s]) => ({
                value,
                label: s.text,
              }))}
            />
          </div>
          <Table
            rowKey="orderId"
            size="middle"
            columns={orderColumns}
            dataSource={orders}
            pagination={{ pageSize: 10 }}
            expandable={{
              expandedRowRender: r => (
                <div className="space-y-1 text-xs leading-5 text-[#4b5563]">
                  <div>下单依据：{r.reason || '-'}</div>
                  {r.expectation && <div>预期：{r.expectation}</div>}
                  {r.review && <div>复盘：{r.review}</div>}
                </div>
              ),
            }}
          />
        </>
      ),
    },
    {
      key: 'reports',
      label: '复盘日报',
      children: (
        <>
          <div className="mb-3 flex items-center gap-3">
            <Select
              allowClear
              placeholder="全部类型"
              className="w-40"
              value={reportType}
              onChange={(v?: AShareReviewType) => {
                setReportType(v);
                loadReports(v);
              }}
              options={Object.entries(REVIEW_TYPE).map(([value, label]) => ({
                value,
                label,
              }))}
            />
            <span className="text-xs text-[#9ca3af]">
              交易日 9:26 盘前计划、15:30 收盘复盘；每周、每月最后一个交易日
              16:00 周复盘、月复盘。
            </span>
          </div>
          {reports.length ? (
            <div className="space-y-4">
              {reports.map(r => (
                <div
                  key={r.id}
                  className="rounded-lg border border-[#e5e7eb] bg-white p-5"
                >
                  <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
                    <span className="font-medium text-[#202124]">
                      {REVIEW_TYPE[r.reviewType] || '复盘'}
                      {r.period ? ` · ${r.period}` : ` · ${r.tradeDate}`}
                    </span>
                    <Tag>{r.triggerType === 'AUTO' ? '定时' : '手动'}</Tag>
                    <Tag color={REPORT_STATUS[r.status]?.color}>
                      {REPORT_STATUS[r.status]?.text}
                    </Tag>
                    {r.costMs ? (
                      <span className="text-xs text-[#9ca3af]">
                        耗时 {Math.round(r.costMs / 1000)} 秒
                      </span>
                    ) : null}
                  </div>
                  {r.status === 'DONE' && r.content ? (
                    <MarkdownRender content={r.content} isSending={false} />
                  ) : r.status === 'FAILED' ? (
                    <div className="text-sm text-[#b42318]">{r.error}</div>
                  ) : (
                    <div className="text-sm text-[#6b7280]">
                      <Spin size="small" /> 智能体正在复盘…
                    </div>
                  )}
                </div>
              ))}
            </div>
          ) : (
            <Empty description="还没有复盘记录。交易日会按时自动复盘，也可以点右上角「立即复盘」" />
          )}
        </>
      ),
    },
    {
      key: 'lessons',
      label: '交易经验',
      children: (
        <>
          <div className="mb-3 flex items-center gap-3">
            <Select
              allowClear
              placeholder="全部类型"
              className="w-40"
              value={lessonKind}
              onChange={v => {
                setLessonKind(v);
                loadLessons(v);
              }}
              options={Object.entries(LESSON_KIND).map(([value, label]) => ({
                value,
                label,
              }))}
            />
            <span className="text-xs text-[#9ca3af]">
              经验需引用成交订单；获得至少 2 次支持且置信度 ≥ 0.6
              才生效并注入智能体提示词，反驳多了自动停用。
            </span>
          </div>
          <Table
            rowKey="id"
            size="middle"
            columns={lessonColumns}
            dataSource={lessons}
            pagination={{ pageSize: 10 }}
          />
        </>
      ),
    },
    {
      key: 'strategy',
      label: `策略${strategy ? ` v${strategy.current.version}` : ''}`,
      children: strategy ? (
        <>
          <div className="mb-4 rounded-lg border border-[#e5e7eb] bg-white p-5">
            <div className="mb-2 text-base font-medium text-[#202124]">
              当前策略 v{strategy.current.version}
              {strategy.current.since && (
                <span className="ml-2 text-xs font-normal text-[#9ca3af]">
                  {strategy.current.since} 起生效
                </span>
              )}
            </div>
            <div className="text-sm leading-6 text-[#374151]">
              {strategy.current.description}
            </div>
            <div className="mt-2 text-xs leading-5 text-[#6b7280]">
              {strategy.current.params?.universeMode === 'AUTO'
                ? `股票池（规则筛选，本月 ${strategy.current.universe?.length ?? 0} 只，详见「股票池」页）：`
                : '股票池（手选）：'}
              {(
                strategy.current.universe || strategy.current.params?.universe
              )?.join('、')}
            </div>
            <div className="mt-2 text-xs leading-5 text-[#9ca3af]">
              {strategy.current.gate}
            </div>
            <div className="mt-3 flex items-center gap-3">
              <Button onClick={handleSearch} loading={searching}>
                立即进化搜索
              </Button>
              <span className="text-xs text-[#9ca3af]">
                系统自动生成约 24
                个候选版本（入场信号、仓位方式、止损/移动止损/分批止盈、自定义规则的阈值等），用
                4
                年数据回测；通过闸门的最好一个进入前进测试。每周最后一个交易日也会自动运行，约需
                1 分钟。
              </span>
            </div>
          </div>
          {(strategy.current.trials?.length ?? 0) > 0 && (
            <div className="mb-4 rounded-lg border border-[#fde68a] bg-[#fffbeb] p-5">
              <div className="mb-2 text-sm font-medium text-[#92400e]">
                前进测试中的候选（通过回测闸门，正在新行情上与当前版本对比）
              </div>
              {strategy.current.trials?.map(t => (
                <div
                  key={t.version}
                  className="mb-2 text-xs leading-5 text-[#78350f]"
                >
                  <span className="font-medium">v{t.version}</span>：
                  {t.forward
                    ? `已测 ${t.forward.days}/${t.forward.needDays} 个交易日，收益 ${pct(t.forward.candidate?.returnPct)} vs 当前 ${pct(t.forward.baseline?.returnPct)}，回撤 ${t.forward.candidate?.maxDrawdownPct ?? '-'}% vs ${t.forward.baseline?.maxDrawdownPct ?? '-'}%`
                    : '等待第一个交易日的数据'}
                  <div className="text-[#a16207]">{t.description}</div>
                </div>
              ))}
            </div>
          )}
          {searchResult && !searchResult.skipped && (
            <div className="mb-4 rounded-lg border border-[#e5e7eb] bg-white p-5">
              <div className="mb-2 text-sm font-medium text-[#202124]">
                最近一次搜索：试了 {searchResult.tried} 个候选，
                {searchResult.passed} 个通过闸门，
                {searchResult.trial
                  ? `v${searchResult.trial.version}（${searchResult.trial.changes}）通过回测闸门，进入前进测试`
                  : `没有候选通过，当前仍为 v${searchResult.baselineVersion}`}
                ；基线样本外夏普 {searchResult.baselineOosScore ?? '-'}（
                {searchResult.historyDays ?? '-'} 个交易日，扩展池{' '}
                {searchResult.poolSize ?? '-'} 只）
              </div>
              <Table
                rowKey="changes"
                size="small"
                pagination={false}
                dataSource={searchResult.top}
                columns={[
                  { title: '候选改动（前 5 名）', dataIndex: 'changes' },
                  { title: '样本外夏普', dataIndex: 'oosScore', width: 100 },
                  { title: '显著性 z', dataIndex: 'z', width: 80 },
                  {
                    title: '仓位占用',
                    dataIndex: 'exposurePct',
                    width: 90,
                    render: v => (v === undefined ? '-' : `${v}%`),
                  },
                  {
                    title: '收益 / 回撤',
                    key: 'rd',
                    width: 140,
                    render: (_, r) =>
                      `${pct(r.oosReturnPct)} / ${r.oosMaxDrawdownPct ?? '-'}%`,
                  },
                  {
                    title: '窗口',
                    dataIndex: 'windowsWon',
                    width: 70,
                    render: (v, r) => `${v}/${r.windows ?? 6}`,
                  },
                  { title: '笔数', dataIndex: 'trades', width: 60 },
                  {
                    title: '闸门',
                    key: 'gate',
                    render: (_, r) =>
                      r.passed ? (
                        <Tag color="green">通过</Tag>
                      ) : (
                        <Tooltip title={r.reasons.join('；')}>
                          <Tag>未通过</Tag>
                        </Tooltip>
                      ),
                  },
                ]}
              />
            </div>
          )}
          <Table
            rowKey="version"
            size="middle"
            columns={versionColumns}
            dataSource={strategy.history}
            pagination={{ pageSize: 10 }}
          />
        </>
      ) : (
        <Empty />
      ),
    },
    {
      key: 'signals',
      label: `建议后验${signals?.stats?.evaluated ? `（${signals.stats.evaluated}）` : ''}`,
      children: signals ? (
        <>
          <div className="mb-4 rounded-lg border border-[#e5e7eb] bg-white p-5">
            <div className="mb-2 text-base font-medium text-[#202124]">
              智能体建议的命中率
            </div>
            <div className="text-sm leading-6 text-[#374151]">
              已评估 {signals.stats.evaluated} 条，待评估{' '}
              {signals.stats.pending} 条，不计入 {signals.stats.unable} 条
              {signals.stats.evaluated > 0 && (
                <>
                  ；命中率{' '}
                  <span className="font-medium">
                    {signals.stats.overall.hitRatePct}%
                  </span>
                  ，平均 {pct(signals.stats.overall.avgReturnPct)}
                  ，平均最大逆向波动 {signals.stats.overall.avgMaxAdversePct}%
                </>
              )}
              {!signals.stats.overall.enough && (
                <Tag color="gold" className="ml-2">
                  样本不足 {signals.stats.minSample} 条，仅供观察
                </Tag>
              )}
            </div>
            <div className="mt-2 text-xs leading-5 text-[#9ca3af]">
              {signals.stats.rule}
            </div>
            <div className="mt-3 flex items-center gap-3">
              <Button
                icon={<ReloadOutlined />}
                onClick={handleEvaluate}
                loading={evaluating}
              >
                立即评估到期建议
              </Button>
              <span className="text-xs text-[#9ca3af]">
                每个交易日 15:25
                自动评估，并把结果写入「交易经验」里的进化记录。
              </span>
            </div>
          </div>
          <div className="mb-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
            {(
              [
                ['按动作', signals.stats.byAction],
                ['按周期', signals.stats.byHorizon],
                ['按数据质量', signals.stats.byDataQuality],
                ['按计划完整度', signals.stats.byPlanQuality],
              ] as [string, AShareSignalGroup[]][]
            ).map(([title, groups]) => (
              <div
                key={title}
                className="rounded-lg border border-[#e5e7eb] bg-white p-4"
              >
                <div className="mb-2 text-sm font-medium text-[#202124]">
                  {title}
                </div>
                {groups.length === 0 ? (
                  <div className="text-xs text-[#9ca3af]">
                    还没有已评估的建议
                  </div>
                ) : (
                  <Table
                    rowKey="key"
                    size="small"
                    pagination={false}
                    dataSource={groups}
                    columns={[
                      {
                        title: '分组',
                        dataIndex: 'key',
                        render: (v: string) =>
                          SIGNAL_ACTION[v]?.text || QUALITY_LABEL[v] || v,
                      },
                      { title: '样本', dataIndex: 'samples', width: 70 },
                      {
                        title: '命中率',
                        key: 'hit',
                        width: 150,
                        render: (_, r) => (
                          <span>
                            {r.hitRatePct ?? '-'}%
                            {!r.enough && (
                              <Tooltip title={r.note}>
                                <Tag className="ml-1">样本少</Tag>
                              </Tooltip>
                            )}
                          </span>
                        ),
                      },
                      {
                        title: '平均收益',
                        dataIndex: 'avgReturnPct',
                        width: 90,
                        render: v => pct(v),
                      },
                    ]}
                  />
                )}
              </div>
            ))}
          </div>
          <div className="mb-3 flex items-center gap-3">
            <Select
              allowClear
              size="small"
              className="w-[140px]"
              placeholder="全部状态"
              value={signalStatus}
              onChange={v => {
                setSignalStatus(v);
                loadSignals(v);
              }}
              options={Object.entries(SIGNAL_STATUS).map(([k, v]) => ({
                value: k,
                label: v.text,
              }))}
            />
          </div>
          <Table
            rowKey="signalId"
            size="middle"
            dataSource={signals.signals}
            pagination={{ pageSize: 10 }}
            locale={{ emptyText: '还没有留痕的建议' }}
            expandable={{
              rowExpandable: r => Boolean(r.reason || r.risk || r.watch),
              expandedRowRender: r => (
                <div className="space-y-1 text-xs leading-5 text-[#374151]">
                  <div>依据：{r.reason || '-'}</div>
                  {r.risk && <div>风险：{r.risk}</div>}
                  {r.watch && <div>观察/失效条件：{r.watch}</div>}
                  {r.outcome?.detail && (
                    <div className="text-[#6b7280]">
                      评估：{r.outcome.detail}
                    </div>
                  )}
                </div>
              ),
            }}
            columns={[
              {
                title: '股票',
                key: 'stock',
                width: 150,
                render: (_, r: AShareSignal) => (
                  <span>
                    {r.name || r.code}
                    <span className="ml-1 text-xs text-[#9ca3af]">
                      {r.code}
                    </span>
                  </span>
                ),
              },
              {
                title: '建议',
                key: 'action',
                width: 150,
                render: (_, r) => (
                  <span>
                    <Tag color={SIGNAL_ACTION[r.action]?.color}>
                      {SIGNAL_ACTION[r.action]?.text || r.action}
                    </Tag>
                    <span className="text-xs text-[#6b7280]">{r.horizon}</span>
                  </span>
                ),
              },
              {
                title: '锚定（已收盘价）',
                key: 'anchor',
                width: 160,
                render: (_, r) =>
                  `${r.anchorDate ?? '-'} @${r.anchorPrice ?? '-'}`,
              },
              {
                title: '结果',
                key: 'result',
                width: 190,
                render: (_, r) => (
                  <span>
                    <Tag color={SIGNAL_STATUS[r.status]?.color}>
                      {SIGNAL_STATUS[r.status]?.text || r.status}
                    </Tag>
                    {r.outcome?.returnPct !== undefined && (
                      <span className={pnlColor(r.outcome.returnPct)}>
                        {pct(r.outcome.returnPct)}
                      </span>
                    )}
                  </span>
                ),
              },
              {
                title: '记录时',
                key: 'meta',
                render: (_, r) => (
                  <span className="text-xs text-[#6b7280]">
                    数据质量 {QUALITY_LABEL[r.dataQuality ?? ''] ?? '-'}，计划{' '}
                    {QUALITY_LABEL[r.planQuality ?? ''] ?? '-'}，
                    {r.source === 'REVIEW' ? '复盘' : '对话'}
                    {r.confidence !== undefined &&
                      r.confidence !== null &&
                      `，置信 ${r.confidence}`}
                  </span>
                ),
              },
            ]}
          />
        </>
      ) : (
        <Empty />
      ),
    },
    {
      key: 'watchlist',
      label: `自选${watchlist?.stocks?.length ? `（${watchlist.stocks.length}）` : ''}`,
      children: (
        <>
          <div className="mb-4 rounded-lg border border-[#e5e7eb] bg-white p-5">
            <div className="mb-2 flex flex-wrap items-center gap-3">
              <span className="text-base font-medium text-[#202124]">
                自选关注
              </span>
              <span className="text-sm text-[#6b7280]">信号出现时自动买入</span>
              <Switch
                size="small"
                checked={settings?.watchlistAutoTrade ?? true}
                loading={savingSettings}
                onChange={async v => {
                  await updateSettings({ watchlistAutoTrade: v });
                  await loadWatchlist();
                }}
              />
              {watchlist && (
                <Tag
                  color={
                    watchlist.effective.includes('自动买入')
                      ? 'green'
                      : 'default'
                  }
                >
                  {watchlist.effective}
                </Tag>
              )}
            </div>
            <div className="text-xs leading-5 text-[#9ca3af]">
              每个交易日 14:50
              用当前策略的入场规则扫描自选股，和规则股票池的信号一起按量比排序，共用持仓上限和仓位预算；离场、止损止盈与其他持仓相同。每次自选股出现信号都会通知你买入结果。
              {watchlist?.note}
            </div>
            <div className="mt-3 flex max-w-[560px] gap-2">
              <Input
                value={watchInput}
                placeholder="输入股票代码，多个用空格或逗号分隔，如 002837 002281"
                onChange={e => setWatchInput(e.target.value)}
                onPressEnter={handleAddWatch}
              />
              <Button
                type="primary"
                loading={watchSaving}
                onClick={handleAddWatch}
              >
                加入自选
              </Button>
            </div>
          </div>
          <Table
            rowKey="code"
            size="middle"
            dataSource={watchlist?.stocks || []}
            pagination={false}
            locale={{ emptyText: '还没有自选股' }}
            columns={[
              {
                title: '股票',
                key: 'stock',
                render: (_, r) => (
                  <span>
                    {r.name || r.code}
                    <span className="ml-1 text-xs text-[#9ca3af]">
                      {r.code}
                    </span>
                    {r.inUniverse && (
                      <Tag color="green" className="ml-2">
                        本月交易池
                      </Tag>
                    )}
                    {r.held && <Tag color="blue">持仓中</Tag>}
                  </span>
                ),
              },
              {
                title: '现价',
                key: 'price',
                width: 150,
                render: (_, r) => (
                  <span className={pnlColor(r.pctChg)}>
                    {r.price ?? '-'}（{pct(r.pctChg)}）
                  </span>
                ),
              },
              {
                title: '策略信号（最新日线）',
                key: 'signal',
                render: (_, r) =>
                  r.error && r.signal === undefined ? (
                    <span className="text-xs text-[#b42318]">{r.error}</span>
                  ) : (
                    <div className="text-xs leading-5">
                      {r.signal ? (
                        <Tag color="red">买入信号</Tag>
                      ) : (
                        <Tag>无信号</Tag>
                      )}
                      <span className="text-[#6b7280]">
                        均线 {r.maFast ?? '-'} / {r.maSlow ?? '-'}，MA60{' '}
                        {r.ma60 ?? '-'}，RSI14 {r.rsi14 ?? '-'}，量比{' '}
                        {r.volumeRatio ?? '-'}
                        {r.marketOk === false ? '，大盘过滤未通过' : ''}
                      </span>
                    </div>
                  ),
              },
              {
                title: '操作',
                key: 'op',
                width: 90,
                render: (_, r) => (
                  <Popconfirm
                    title={`把 ${r.name || r.code} 移出自选？`}
                    description="已有持仓不受影响，仍按策略离场。"
                    onConfirm={() => handleRemoveWatch(r.code)}
                  >
                    <Button type="link" size="small">
                      移出
                    </Button>
                  </Popconfirm>
                ),
              },
            ]}
          />
        </>
      ),
    },
    {
      key: 'universe',
      label: `股票池${universe?.live?.length ? `（${universe.live.length}）` : ''}`,
      children: universe ? (
        <>
          <div className="mb-4 rounded-lg border border-[#e5e7eb] bg-white p-5">
            <div className="mb-2 flex items-center gap-2 text-base font-medium text-[#202124]">
              股票池规则
              <Tag color={universe.mode === 'AUTO' ? 'green' : 'default'}>
                {universe.mode === 'AUTO' ? '当前策略使用' : '当前策略未使用'}
              </Tag>
            </div>
            <div className="text-sm leading-6 text-[#374151]">
              {universe.rules}
            </div>
            <div className="mt-2 text-xs leading-5 text-[#9ca3af]">
              候选为新浪行情中当前总市值 ≥ 150 亿的沪深 A
              股（含曾经更大、后来跌下来的股票）。每月第一个交易日收盘后按当时数据重新筛选，盘后每天补齐
              K
              线；回测时每个月用当月之前的数据筛出的股票池（时点数据，不用今天的名单回看过去）。局限：已退市的股票不在候选里，历史市值按复权价估算，ST
              按当前名称判断，仍有少量幸存者偏差。
            </div>
            <div className="mt-3 flex flex-wrap items-center gap-3">
              <Button
                icon={<ReloadOutlined />}
                onClick={handleRefreshUniverse}
                loading={universe.running}
              >
                重新筛选本月
              </Button>
              <span className="text-xs text-[#6b7280]">
                {universe.running
                  ? `更新中：${universe.progress}`
                  : universe.progress && universe.progress !== 'idle'
                    ? `上次更新：${universe.progress}`
                    : universe.snapshots[0]
                      ? `本月名单数据截至 ${universe.snapshots[0].asOf}；交易日 15:12 自动补齐 K 线`
                      : '尚未筛选'}
              </span>
            </div>
            {universe.mode !== 'AUTO' && (
              <Alert
                className="mt-4"
                type="warning"
                showIcon
                message="当前策略仍使用手选股票池"
                description={
                  <div className="text-xs leading-5">
                    {universe.note}
                    。手选股票池是按今天的眼光挑的，回测结果偏乐观。改用规则筛选会生成一个新版本（其余参数不变），作为方法变更直接生效，不经回测闸门，两种股票池的回测结果都会记录在进化记录里。
                    <div className="mt-2">
                      <Popconfirm
                        title="改用规则筛选的股票池？"
                        description="会生成新版本并立即生效，用于之后的自动交易和回测。"
                        onConfirm={handleSwitchUniverse}
                      >
                        <Button size="small" type="primary" loading={switching}>
                          改用规则筛选股票池
                        </Button>
                      </Popconfirm>
                    </div>
                  </div>
                }
              />
            )}
          </div>
          {universe.snapshots.length === 0 ? (
            <Empty description="还没有筛选结果，点击「重新筛选本月」开始（首次约 10 分钟）" />
          ) : (
            (() => {
              const snap =
                universe.snapshots.find(x => x.month === universeMonth) ||
                universe.snapshots[0];
              if (!snap) {
                return null;
              }
              const tradeSet = new Set(
                snap.tradeCodes.slice(0, universe.tradeSize)
              );
              const added = new Set(snap.added || []);
              return (
                <div className="mb-4 rounded-lg border border-[#e5e7eb] bg-white p-5">
                  <div className="mb-3 flex flex-wrap items-center gap-3">
                    <Select
                      size="small"
                      className="w-[120px]"
                      value={snap.month}
                      onChange={setUniverseMonth}
                      options={universe.snapshots.map(x => ({
                        value: x.month,
                        label: x.month,
                      }))}
                    />
                    <span className="text-sm text-[#374151]">
                      数据截至 {snap.asOf}，{snap.candidates} 只候选中{' '}
                      {snap.poolSize} 只进入扩展验证池，前 {universe.tradeSize}{' '}
                      只为交易股票池
                    </span>
                  </div>
                  {snap.added !== undefined && (
                    <div className="mb-3 text-xs leading-5 text-[#6b7280]">
                      较上月新进：
                      {snap.added.length ? snap.added.join('、') : '无'}
                      ；移出：
                      {snap.removed?.length ? snap.removed.join('、') : '无'}
                    </div>
                  )}
                  <Table
                    rowKey="code"
                    size="small"
                    dataSource={snap.details}
                    pagination={{ pageSize: 30 }}
                    columns={[
                      {
                        title: '排名',
                        key: 'rank',
                        width: 60,
                        render: (_, __, i) => i + 1,
                      },
                      {
                        title: '股票',
                        key: 'stock',
                        render: (_, r) => (
                          <span>
                            {r.name}
                            <span className="ml-1 text-xs text-[#9ca3af]">
                              {r.code}
                            </span>
                            {tradeSet.has(r.code) && (
                              <Tag color="green" className="ml-2">
                                交易池
                              </Tag>
                            )}
                            {added.has(r.code) && <Tag color="gold">新进</Tag>}
                          </span>
                        ),
                      },
                      {
                        title: '近 60 日日均成交额',
                        dataIndex: 'avgAmount',
                        width: 150,
                        render: v => `${v} 亿`,
                      },
                      {
                        title: '总市值（估）',
                        dataIndex: 'cap',
                        width: 130,
                        render: v => `${v} 亿`,
                      },
                      {
                        title: '复权收盘价',
                        dataIndex: 'price',
                        width: 110,
                      },
                    ]}
                  />
                </div>
              );
            })()
          )}
          {universe.pointInTime.length > 0 && (
            <div className="rounded-lg border border-[#e5e7eb] bg-white p-5">
              <div className="mb-2 text-sm font-medium text-[#202124]">
                回测使用的历史股票池（每月按当时数据筛选）
              </div>
              <Table
                rowKey="month"
                size="small"
                dataSource={[...universe.pointInTime].reverse()}
                pagination={{ pageSize: 12 }}
                columns={[
                  { title: '月份', dataIndex: 'month', width: 90 },
                  { title: '数据截至', dataIndex: 'asOf', width: 110 },
                  { title: '通过规则', dataIndex: 'passed', width: 90 },
                  {
                    title: '成交额前 5',
                    dataIndex: 'top',
                    render: (v: string[]) => v.join('、'),
                  },
                ]}
              />
            </div>
          )}
        </>
      ) : (
        <Empty />
      ),
    },
    {
      key: 'settings',
      label: '设置',
      children: settings ? (
        <div className="max-w-[760px] space-y-4">
          <div className="flex items-start justify-between gap-6 rounded-lg border border-[#e5e7eb] bg-white p-5">
            <div>
              <div className="font-medium text-[#202124]">策略自动交易</div>
              <div className="mt-1 text-sm leading-6 text-[#6b7280]">
                交易时段（9:30–11:30、13:00–15:00）每分钟盯盘：持仓达到止损或止盈立即按最新价卖出；14:50
                按当前策略买入触发入场信号的股票，并执行到期、均线死叉离场。遵守
                T+1，涨停封板买不进、跌停封板卖不出。关闭后只保留自动止损。
              </div>
            </div>
            <Switch
              checked={settings.autoTrade}
              loading={savingSettings}
              onChange={v => updateSettings({ autoTrade: v })}
            />
          </div>
          <div className="rounded-lg border border-[#e5e7eb] bg-white p-5">
            <div className="font-medium text-[#202124]">自动复盘</div>
            <div className="mt-1 text-sm leading-6 text-[#6b7280]">
              由下面选择的智能体执行。收盘、周、月复盘会更新交易经验，证据充足时提交一次策略改进（须通过回测闸门）；盘前计划只做计划。没有持仓和交易的时段会跳过。
            </div>
            <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2">
              {(
                [
                  ['preOpenReview', '盘前计划', '交易日 9:26，集合竞价后'],
                  ['autoReview', '收盘复盘', '交易日 15:30'],
                  ['weeklyReview', '周复盘', '每周最后一个交易日 16:00'],
                  ['monthlyReview', '月复盘', '每月最后一个交易日 16:00'],
                  [
                    'autoEvolve',
                    '自动进化',
                    '每周搜索策略改进，每月检查是否需要回滚',
                  ],
                ] as const
              ).map(([key, label, hint]) => (
                <div
                  key={key}
                  className="flex items-center justify-between rounded-md border border-[#f0f0f0] px-4 py-3"
                >
                  <div>
                    <div className="text-sm text-[#202124]">{label}</div>
                    <div className="text-xs text-[#9ca3af]">{hint}</div>
                  </div>
                  <Switch
                    size="small"
                    checked={settings[key]}
                    loading={savingSettings}
                    onChange={v => updateSettings({ [key]: v })}
                  />
                </div>
              ))}
            </div>
            <div className="mt-4 text-sm text-[#202124]">复盘智能体</div>
            <div className="mt-1 text-xs text-[#9ca3af]">
              需开启「A股投研工具」并已发布。默认是你第一次用来交易的智能体。
            </div>
            <Select
              className="mt-2 w-80"
              placeholder="选择智能体"
              value={settings.reviewBotId ?? undefined}
              options={reviewBotOptions}
              disabled={savingSettings}
              onChange={v => updateSettings({ reviewBotId: v })}
              notFoundContent="没有开启 A股投研工具的智能体"
            />
          </div>
        </div>
      ) : (
        <Empty />
      ),
    },
  ];

  return (
    <div className="h-full overflow-auto bg-[#f7f8fa] px-8 py-7">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="m-0 text-2xl font-semibold text-[#202124]">
            模拟炒股
          </h1>
          <div className="mt-1 flex flex-wrap items-center gap-2 text-sm text-[#6b7280]">
            <span>
              本金 {money(overview?.initialCash)} 元，按 A
              股规则成交：交易时段按最新价成交，非交易时段的订单在下一交易日开盘成交；T+1。
            </span>
            {settings && (
              <Tag color={settings.tradingNow ? 'red' : 'default'}>
                {settings.tradingNow ? '交易中 · 盯盘运行' : '休市 · 不盯盘'}
              </Tag>
            )}
            {marketOverview && (
              <Tag color="blue">{marketOverview.marketPhaseLabel}</Tag>
            )}
            {marketOverview && marketOverview.dataQuality.level !== 'GOOD' && (
              <Tooltip
                title={
                  marketOverview.dataQuality.limitations.join('；') ||
                  marketOverview.rule
                }
              >
                <Tag color="gold">
                  数据质量 {marketOverview.dataQuality.score} 分（
                  {marketOverview.dataQuality.levelLabel}）
                </Tag>
              </Tooltip>
            )}
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Button icon={<ReloadOutlined />} onClick={loadAll} loading={loading}>
            刷新
          </Button>
          <Dropdown
            disabled={running || starting}
            menu={{
              items: Object.entries(REVIEW_TYPE).map(([key, label]) => ({
                key,
                label,
              })),
              onClick: ({ key }) => handleRunReview(key as AShareReviewType),
            }}
          >
            <Button
              type="primary"
              icon={<ThunderboltOutlined />}
              loading={starting}
            >
              {running ? '复盘中…' : '立即复盘'} <DownOutlined />
            </Button>
          </Dropdown>
        </div>
      </div>
      <Alert
        className="mb-4"
        type="warning"
        showIcon
        message="仅为模拟交易与学习研究，不构成投资建议。行情来自东方财富公开接口，日 K 线在东方财富不可用时改用腾讯公开接口（均非官方，可能变化或中断）。"
      />
      {error && (
        <Alert className="mb-4" type="error" showIcon message={error} />
      )}
      <Spin spinning={loading && !overview}>
        <div className="mb-5 grid grid-cols-2 gap-4 lg:grid-cols-3 2xl:grid-cols-6">
          <Stat
            label="总资产"
            value={money(overview?.totalAssets)}
            hint={`初始 ${money(overview?.initialCash)}`}
          />
          <Stat
            label="累计收益"
            value={pct(overview?.returnPct)}
            className={pnlColor(overview?.returnPct)}
            hint={
              benchmark
                ? `${benchmark.name}同期 ${pct(benchmark.returnPct)}（自 ${benchmark.from}）`
                : undefined
            }
          />
          <Stat
            label="最大回撤"
            value={
              overview?.maxDrawdownPct !== undefined
                ? `${overview.maxDrawdownPct}%`
                : '-'
            }
          />
          <Stat
            label="已实现盈亏"
            value={money(overview?.realizedPnl)}
            className={pnlColor(overview?.realizedPnl)}
            hint={`已平仓 ${overview?.closedTrades ?? 0} 笔，胜率 ${
              overview?.winRatePct !== undefined &&
              overview?.winRatePct !== null
                ? `${overview.winRatePct}%`
                : '-'
            }`}
          />
          <Stat
            label="可用现金"
            value={money(overview?.cash)}
            hint={`冻结 ${money(overview?.frozenCash)}`}
          />
          <Stat
            label="持仓市值"
            value={money(overview?.marketValue)}
            hint={`累计手续费 ${money(overview?.totalFees)}`}
          />
        </div>
        <div className="rounded-lg border border-[#e5e7eb] bg-white px-5 pb-5 pt-2 shadow-sm">
          {marketOverview && (
            <div className="mb-4 rounded-lg border border-[#e5e7eb] bg-white px-5 py-4">
              <div className="flex flex-wrap items-center gap-x-6 gap-y-2 text-sm">
                {marketOverview.indices.map(i => (
                  <span key={i.name}>
                    <span className="text-[#6b7280]">{i.name}</span>{' '}
                    <span className={pnlColor(i.pctChg)}>
                      {i.price ?? '-'}（{pct(i.pctChg)}）
                    </span>
                  </span>
                ))}
              </div>
              {marketOverview.breadth?.advances !== undefined && (
                <div className="mt-2 text-xs leading-5 text-[#6b7280]">
                  市场宽度：上涨 {marketOverview.breadth.advances} 家，下跌{' '}
                  {marketOverview.breadth.declines} 家
                  {marketOverview.breadth.limitUp !== undefined &&
                    `；涨停 ${marketOverview.breadth.limitUp} 家，跌停 ${marketOverview.breadth.limitDown} 家`}
                  {Boolean(marketOverview.breadth.brokenLimitUp) &&
                    `，炸板 ${marketOverview.breadth.brokenLimitUp} 家`}
                </div>
              )}
              {Boolean(marketOverview.industryLeaders?.length) && (
                <div className="mt-1 text-xs leading-5 text-[#6b7280]">
                  领涨行业：
                  {marketOverview.industryLeaders
                    ?.map(x => `${x.name} ${pct(x.pctChg)}`)
                    .join('、')}
                  ；领跌：
                  {marketOverview.industryLaggards
                    ?.map(x => `${x.name} ${pct(x.pctChg)}`)
                    .join('、')}
                </div>
              )}
              {Boolean(marketOverview.hotConcepts?.length) && (
                <div className="mt-1 text-xs leading-5 text-[#6b7280]">
                  热门概念：
                  {marketOverview.hotConcepts
                    ?.map(x => `${x.name} ${pct(x.pctChg)}`)
                    .join('、')}
                </div>
              )}
            </div>
          )}
          <Tabs items={tabs} />
        </div>
      </Spin>
    </div>
  );
};

export default AShareTradingPage;

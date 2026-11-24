import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Avatar, Button, Switch, Tag, Tooltip } from 'antd';
import {
  DeleteOutlined,
  PlusOutlined,
  QuestionCircleOutlined,
  RobotOutlined,
} from '@ant-design/icons';
import cls from 'classnames';
import {
  getAgentCandidates,
  type AgentCandidate,
  type AgentCandidateList,
} from '@/services/agent';
import type { AgentRef } from '@/types/agent-ref';
import AgentSelectDrawer, { capabilityTags } from './AgentSelectDrawer';
import styles from './CapabilityDevelopment.module.scss';

interface SubAgentSectionProps {
  agents: AgentRef[];
  setAgents: (v: AgentRef[]) => void;
  /** Pick experts from the space's published agents per question. */
  autoTeam?: boolean;
  setAutoTeam?: (v: boolean) => void;
  /** The agent being edited; undefined while creating a new agent. */
  currentBotId?: number;
  /** Extra rows rendered at the bottom of the block (legacy workflows). */
  children?: React.ReactNode;
}

/** 能力页「智能体」block: attached sub-agents plus the selection drawer. */
const SubAgentSection: React.FC<SubAgentSectionProps> = ({
  agents,
  setAgents,
  autoTeam,
  setAutoTeam,
  currentBotId,
  children,
}) => {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<AgentCandidateList | null>(null);

  const load = useCallback((): void => {
    setLoading(true);
    getAgentCandidates(currentBotId)
      .then(res => setData(res))
      .catch(() => setData(null))
      .finally(() => setLoading(false));
  }, [currentBotId]);

  useEffect(() => {
    load();
  }, [load]);

  const byId = useMemo(() => {
    const map = new Map<number, AgentCandidate>();
    (data?.candidates ?? []).forEach(c => map.set(c.botId, c));
    return map;
  }, [data]);

  const level = data?.level ?? 0;
  const maxDepth = data?.maxDepth ?? 2;
  // `level` reference levels already sit above this agent; adding agents uses one more.
  const canAdd = level < maxDepth;
  const usedByNames = (data?.usedBy ?? []).map(u => u.name).join('、');

  /** Why an attached agent would be rejected on save, if it would. */
  const problemOf = (item: AgentRef): string | undefined => {
    if (!data) return undefined;
    const candidate = byId.get(item.botId);
    if (!candidate) return '已删除或无权访问，请移除';
    if (!candidate.published) return '未发布，对话时不会调用；请先发布它或移除';
    if (!candidate.selectable && candidate.botId !== currentBotId)
      return `${candidate.reason}，请移除`;
    return undefined;
  };

  const openDrawer = (): void => {
    if (!canAdd) return;
    load();
    setOpen(true);
  };

  const addButton = (
    <Button
      icon={<PlusOutlined />}
      className={styles.capabilityActionButton}
      disabled={!canAdd}
      onClick={openDrawer}
    >
      {agents.length > 0 ? '管理智能体' : '添加智能体'}
    </Button>
  );

  return (
    <div className={styles.capabilityBlock}>
      <div className={styles.capabilityBlockHeader}>
        <div className={styles.capabilityTitleGroup}>
          <span className={cls(styles.capabilityIcon, styles.mcpIcon)}>
            <RobotOutlined />
          </span>
          <div>
            <div className={styles.capabilityTitleRow}>
              <span className={styles.capabilityTitle}>智能体</span>
              <span
                className={cls(
                  styles.capabilityStatus,
                  agents.length > 0 ? styles.statusReady : styles.statusMuted
                )}
              >
                {agents.length > 0 ? `已添加 ${agents.length} 个` : '未添加'}
              </span>
            </div>
            <div className={styles.capabilityDescription}>
              添加本平台已发布的其他智能体，模型会根据用户意图和智能体简介选择交给哪个智能体处理。可在「个性化设置」里写明分派规则，例如“售后问题交给客服助手”。
            </div>
          </div>
        </div>
        {addButton}
      </div>

      {setAutoTeam && canAdd && (
        <div
          className="flex items-center justify-between gap-4 mb-3 px-3.5 py-2.5 rounded-lg bg-[#f7f8fd]"
          data-testid="auto-team"
        >
          <div>
            <div className="text-sm font-medium text-[#1f2937]">
              从专家库自动组队
            </div>
            <div className="text-xs text-[#6b7280] mt-0.5">
              每次提问时，从本空间已发布的智能体里挑出与问题相关的专家（最多 6
              位）供模型选用，不必逐个添加。下方添加的智能体始终可用。
            </div>
          </div>
          <Switch
            className="list-switch config-switch flex-none"
            checked={!!autoTeam}
            onChange={setAutoTeam}
          />
        </div>
      )}

      {usedByNames && (
        <Alert
          type={canAdd ? 'info' : 'warning'}
          showIcon
          className="mb-3"
          message={
            canAdd
              ? `已被「${usedByNames}」引用。引用最多 ${maxDepth} 层，从这里往下还能再引用 ${maxDepth - level} 层。`
              : `已被「${usedByNames}」引用，引用已达 ${maxDepth} 层，不能再添加智能体。`
          }
          description={
            canAdd
              ? `${maxDepth - level === 1 ? '只能添加没有再引用其他智能体的智能体；' : ''}也不能添加「${usedByNames}」，以免相互调用。`
              : agents.length > 0
                ? '下方的智能体不会再被调用，请移除后保存。'
                : undefined
          }
        />
      )}

      {agents.length > 0 ? (
        <div className={styles.skillList}>
          {agents.map(item => {
            const candidate = byId.get(item.botId);
            const problem = problemOf(item);
            const tags = candidate ? capabilityTags(candidate) : [];
            return (
              <div
                className={cls(styles.skillRow, styles.agentRow)}
                key={item.botId}
              >
                <div className="flex items-center gap-3 min-w-0">
                  <Avatar
                    shape="square"
                    size={36}
                    src={candidate?.avatar || item.avatar || undefined}
                    icon={<RobotOutlined />}
                    className="flex-none"
                  />
                  <div className={styles.skillRowMeta}>
                    <span className={styles.skillRowName}>
                      {candidate?.name || item.name}
                      {problem && (
                        <Tag color="error" className="ml-2">
                          {problem}
                        </Tag>
                      )}
                    </span>
                    <span className={styles.skillRowDesc}>
                      {candidate?.description || item.description || '暂无简介'}
                    </span>
                    {candidate && (
                      <span className={styles.skillRowDesc}>
                        {[
                          candidate.modelName,
                          tags.length > 0 ? tags.join(' · ') : '仅提示词',
                        ]
                          .filter(Boolean)
                          .join(' ｜ ')}
                      </span>
                    )}
                  </div>
                </div>
                <div className={styles.agentRowActions}>
                  <label
                    className={styles.agentRowSwitch}
                    data-testid="agent-direct"
                  >
                    <Tooltip title="开启后，只交给它一个处理时，它的回答会边生成边直接发给用户，当前智能体不再复述，首字更快。需要和其他智能体的结果合并时，当前智能体会同时调用它们再汇总。">
                      <span>
                        直接回复用户
                        <QuestionCircleOutlined className="ml-1 text-[#9ca3af]" />
                      </span>
                    </Tooltip>
                    <Switch
                      className="list-switch config-switch"
                      checked={!!item.direct}
                      onChange={checked =>
                        setAgents(
                          agents.map(a =>
                            a.botId === item.botId
                              ? { ...a, direct: checked || undefined }
                              : a
                          )
                        )
                      }
                    />
                  </label>
                  <Tooltip title="移除">
                    <Button
                      className={styles.mcpDeleteButton}
                      icon={<DeleteOutlined />}
                      onClick={() =>
                        setAgents(agents.filter(a => a.botId !== item.botId))
                      }
                    />
                  </Tooltip>
                </div>
              </div>
            );
          })}
        </div>
      ) : (
        canAdd && (
          <button
            type="button"
            className={styles.capabilityEmptyState}
            onClick={openDrawer}
          >
            <span className={styles.capabilityEmptyTitle}>暂无智能体</span>
            <span className={styles.capabilityEmptyDescription}>
              点击添加智能体，让当前智能体按意图把问题分派给它们处理。
            </span>
          </button>
        )
      )}
      {children}
      <AgentSelectDrawer
        open={open}
        selected={agents}
        data={data}
        loading={loading}
        onReload={load}
        onClose={() => setOpen(false)}
        // 重新选择时保留已有成员的「直接回复用户」设置
        onConfirm={next =>
          setAgents(
            next.map(a => ({
              ...a,
              direct: agents.find(o => o.botId === a.botId)?.direct,
            }))
          )
        }
      />
    </div>
  );
};

export default SubAgentSection;

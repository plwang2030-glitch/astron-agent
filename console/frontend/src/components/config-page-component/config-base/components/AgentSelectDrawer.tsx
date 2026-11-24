import React, { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Avatar,
  Button,
  Drawer,
  Empty,
  Input,
  Segmented,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  InfoCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  RobotOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import type { AgentCandidate, AgentCandidateList } from '@/services/agent';
import type { AgentRef } from '@/types/agent-ref';
import { MAX_AGENTS } from '../mcp-url-config';
import styles from './AgentSelectDrawer.module.scss';

type FilterKey = 'all' | 'selectable' | 'selected';

interface AgentSelectDrawerProps {
  open: boolean;
  /** Agents currently attached (before this edit). */
  selected: AgentRef[];
  data: AgentCandidateList | null;
  loading: boolean;
  onReload: () => void;
  onClose: () => void;
  onConfirm: (next: AgentRef[]) => void;
}

const toRef = (item: AgentCandidate): AgentRef => ({
  botId: item.botId,
  name: item.name || '',
  description: item.description || '',
  avatar: item.avatar || undefined,
});

/** Capability tags shown in the list and on the attached rows. */
export const capabilityTags = (item: AgentCandidate): string[] => {
  const tags: string[] = [];
  if (item.hasKnowledge) tags.push('知识库');
  if (item.skillCount > 0) tags.push(`技能 ${item.skillCount}`);
  if (item.toolCount > 0) tags.push(`插件 ${item.toolCount}`);
  if (item.mcpCount > 0) tags.push(`MCP ${item.mcpCount}`);
  return tags;
};

/**
 * Full selection list for sub-agents: search and filter, a table with model / capabilities / usage /
 * status, unavailable agents listed with the reason, and the selection applied only on 确定.
 */
const AgentSelectDrawer: React.FC<AgentSelectDrawerProps> = ({
  open,
  selected,
  data,
  loading,
  onReload,
  onClose,
  onConfirm,
}) => {
  const [draft, setDraft] = useState<AgentRef[]>([]);
  const [keyword, setKeyword] = useState('');
  const [filter, setFilter] = useState<FilterKey>('all');

  useEffect(() => {
    if (open) {
      setDraft(selected);
      setKeyword('');
      setFilter('all');
    }
  }, [open]);

  const draftIds = useMemo(() => new Set(draft.map(a => a.botId)), [draft]);
  const candidates = data?.candidates ?? [];

  const rows = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    return candidates.filter(item => {
      if (filter === 'selectable' && !item.selectable) return false;
      if (filter === 'selected' && !draftIds.has(item.botId)) return false;
      if (!kw) return true;
      return (
        (item.name || '').toLowerCase().includes(kw) ||
        (item.description || '').toLowerCase().includes(kw)
      );
    });
  }, [candidates, keyword, filter, draftIds]);

  const selectableCount = candidates.filter(c => c.selectable).length;

  const toggle = (item: AgentCandidate): void => {
    if (draftIds.has(item.botId)) {
      setDraft(draft.filter(a => a.botId !== item.botId));
      return;
    }
    if (!item.selectable) {
      return;
    }
    if (draft.length >= MAX_AGENTS) {
      message.warning(`最多添加 ${MAX_AGENTS} 个智能体`);
      return;
    }
    setDraft([...draft, toRef(item)]);
  };

  const columns: ColumnsType<AgentCandidate> = [
    {
      title: '智能体',
      key: 'name',
      render: (_, item) => (
        <div className={styles.nameCell}>
          <Avatar
            shape="square"
            size={36}
            src={item.avatar || undefined}
            icon={<RobotOutlined />}
            className={styles.avatar}
          />
          <div className={styles.nameMeta}>
            <span className={styles.name}>
              {item.name}
              {!item.published && (
                <Tag bordered={false} className={styles.unpublishedTag}>
                  未发布
                </Tag>
              )}
            </span>
            <Typography.Text
              type="secondary"
              className={styles.desc}
              ellipsis={{ tooltip: item.description }}
            >
              {item.description || '暂无简介'}
            </Typography.Text>
          </div>
        </div>
      ),
    },
    {
      title: '模型',
      dataIndex: 'modelName',
      width: 120,
      render: (value?: string) => (
        <Typography.Text
          ellipsis={{ tooltip: value }}
          className={styles.cellText}
        >
          {value || '—'}
        </Typography.Text>
      ),
    },
    {
      title: '能力',
      key: 'capabilities',
      width: 170,
      render: (_, item) => {
        const tags = capabilityTags(item);
        return tags.length === 0 ? (
          <span className={styles.muted}>仅提示词</span>
        ) : (
          <div className={styles.tags}>
            {tags.map(tag => (
              <Tag key={tag} bordered={false}>
                {tag}
              </Tag>
            ))}
          </div>
        );
      },
    },
    {
      title: '被引用',
      key: 'usedBy',
      width: 100,
      render: (_, item) =>
        item.usedBy.length === 0 ? (
          <span className={styles.muted}>—</span>
        ) : (
          <Tooltip title={`被 ${item.usedBy.join('、')} 使用`}>
            <span className={styles.cellText}>
              {item.usedBy.length} 个智能体
            </span>
          </Tooltip>
        ),
    },
    {
      title: '更新时间',
      dataIndex: 'updateTime',
      width: 140,
      render: (value?: string) => (
        <span className={styles.cellText}>
          {value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—'}
        </span>
      ),
    },
    {
      title: '状态',
      key: 'status',
      width: 130,
      render: (_, item) => {
        if (draftIds.has(item.botId)) {
          return item.selectable ? (
            <Tag color="processing">已选</Tag>
          ) : (
            <Tooltip title={`${item.reason}，建议移除`}>
              <Tag color="error">
                已选·不可用 <InfoCircleOutlined />
              </Tag>
            </Tooltip>
          );
        }
        return item.selectable ? (
          <Tag color="success">可添加</Tag>
        ) : (
          <Tooltip title={item.reason}>
            <Tag>
              不可添加 <InfoCircleOutlined />
            </Tag>
          </Tooltip>
        );
      },
    },
  ];

  const unknownSelected = draft.filter(
    a => !candidates.some(c => c.botId === a.botId)
  );

  return (
    <Drawer
      open={open}
      onClose={onClose}
      width={1000}
      destroyOnClose
      title="添加智能体"
      extra={
        <Button
          type="link"
          icon={<PlusOutlined />}
          onClick={() => window.open('/space/agent-create', '_blank')}
        >
          新建智能体
        </Button>
      }
      footer={
        <div className={styles.footer}>
          <div className={styles.footerSelected}>
            <span className={styles.footerCount}>
              已选 {draft.length}/{MAX_AGENTS}
            </span>
            {draft.map(a => (
              <Tag
                key={a.botId}
                closable
                color={
                  unknownSelected.some(u => u.botId === a.botId)
                    ? 'error'
                    : undefined
                }
                onClose={e => {
                  e.preventDefault();
                  setDraft(draft.filter(x => x.botId !== a.botId));
                }}
              >
                {a.name || `智能体 ${a.botId}`}
              </Tag>
            ))}
          </div>
          <div className={styles.footerActions}>
            <Button onClick={onClose}>取消</Button>
            <Button
              type="primary"
              onClick={() => {
                onConfirm(draft);
                onClose();
              }}
            >
              确定
            </Button>
          </div>
        </div>
      }
    >
      <Alert
        type="info"
        showIcon
        className={styles.alert}
        message="选择当前空间里的指令型智能体。模型会按用户意图和智能体简介，把问题交给其中一个处理。"
        description={`只能添加已发布的智能体，对话时使用它们的发布版本；引用最多 ${data?.maxDepth ?? 2} 层（例如 办公助手 → 客服助手 → 文案写作助手），且不能相互调用。不满足的显示为不可添加，悬停可查看原因。`}
      />
      <div className={styles.toolbar}>
        <Input.Search
          allowClear
          placeholder="搜索名称或简介"
          value={keyword}
          onChange={e => setKeyword(e.target.value)}
          className={styles.search}
        />
        <Segmented<FilterKey>
          value={filter}
          onChange={setFilter}
          options={[
            { label: `全部 ${candidates.length}`, value: 'all' },
            { label: `可添加 ${selectableCount}`, value: 'selectable' },
            { label: `已选 ${draft.length}`, value: 'selected' },
          ]}
        />
        <Tooltip title="刷新">
          <Button icon={<ReloadOutlined />} onClick={onReload} />
        </Tooltip>
      </div>
      <Table<AgentCandidate>
        rowKey="botId"
        size="middle"
        tableLayout="fixed"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={{
          pageSize: 8,
          hideOnSinglePage: true,
          showSizeChanger: false,
        }}
        rowClassName={item =>
          item.selectable || draftIds.has(item.botId)
            ? styles.clickableRow || ''
            : styles.disabledRow || ''
        }
        onRow={item => ({ onClick: () => toggle(item) })}
        rowSelection={{
          hideSelectAll: true,
          selectedRowKeys: draft.map(a => a.botId),
          getCheckboxProps: item => ({
            disabled: !item.selectable && !draftIds.has(item.botId),
          }),
          onSelect: item => toggle(item),
        }}
        locale={{
          emptyText: (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={
                candidates.length === 0
                  ? '当前空间还没有其他指令型智能体'
                  : '没有符合条件的智能体'
              }
            />
          ),
        }}
      />
    </Drawer>
  );
};

export default AgentSelectDrawer;

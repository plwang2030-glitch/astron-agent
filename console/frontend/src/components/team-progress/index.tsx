import React, { ReactElement, useEffect, useState } from 'react';
import { Tag } from 'antd';
import {
  CheckCircleFilled,
  CloseCircleFilled,
  DownOutlined,
  FileTextOutlined,
  LoadingOutlined,
  MinusCircleFilled,
  RightOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import type { TeamMember, TeamState, TeamStatus } from '@/types/team';
import styles from './index.module.scss';

const STATUS: Record<
  TeamStatus,
  { label: string; color: string; icon: ReactElement }
> = {
  running: { label: '进行中', color: 'processing', icon: <LoadingOutlined /> },
  ok: { label: '完成', color: 'success', icon: <CheckCircleFilled /> },
  empty: { label: '无内容', color: 'default', icon: <MinusCircleFilled /> },
  timeout: { label: '超时', color: 'warning', icon: <CloseCircleFilled /> },
  failed: { label: '失败', color: 'error', icon: <CloseCircleFilled /> },
  cancelled: { label: '已停止', color: 'default', icon: <MinusCircleFilled /> },
  skipped: { label: '未执行', color: 'default', icon: <MinusCircleFilled /> },
};

const formatSize = (size?: number): string => {
  if (!size) return '';
  return size > 1024 * 1024
    ? `${(size / 1024 / 1024).toFixed(1)} MB`
    : `${Math.max(1, Math.round(size / 1024))} KB`;
};

const Elapsed = ({ member }: { member: TeamMember }): ReactElement | null => {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    if (member.status !== 'running') return undefined;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [member.status]);
  const ms =
    member.costMs ??
    (member.status === 'running' && member.startedAt
      ? now - member.startedAt
      : undefined);
  if (ms === undefined) return null;
  return <span className={styles.elapsed}>{Math.round(ms / 1000)} 秒</span>;
};

const MemberRow = ({
  member,
  nested,
  defaultOpen,
}: {
  member: TeamMember;
  nested: boolean;
  defaultOpen: boolean;
}): ReactElement => {
  const [open, setOpen] = useState(defaultOpen);
  const status = STATUS[member.status] || STATUS.ok;
  return (
    <div className={nested ? styles.memberNested : styles.member}>
      <div className={styles.memberHead} onClick={() => setOpen(!open)}>
        <span className={styles.toggle}>
          {open ? <DownOutlined /> : <RightOutlined />}
        </span>
        <span className={styles.name}>{member.name}</span>
        <Tag color={status.color} icon={status.icon} bordered={false}>
          {status.label}
        </Tag>
        <Elapsed member={member} />
      </div>
      {member.query && (
        <div className={styles.task} title={member.query}>
          任务：{member.query}
        </div>
      )}
      {open && member.output && (
        <div className={styles.output}>{member.output}</div>
      )}
    </div>
  );
};

/**
 * Expert-team progress: which experts the lead dispatched, what each is working on, their live
 * output and status, nested experts under the expert that called them, and delivered files.
 */
const TeamProgress = ({
  team,
  streaming,
}: {
  team?: TeamState;
  streaming?: boolean;
}): ReactElement | null => {
  const [collapsed, setCollapsed] = useState(false);
  if (!team || (team.members.length === 0 && team.files.length === 0)) {
    return null;
  }
  const running = team.members.filter(m => m.status === 'running').length;
  const top = team.members.filter(m => !m.parentBotId);
  const nestedOf = (botId: number): TeamMember[] =>
    team.members.filter(m => m.parentBotId === botId);
  const orphans = team.members.filter(
    m => m.parentBotId && !team.members.some(p => p.botId === m.parentBotId)
  );
  const experts = new Set(team.members.map(m => m.botId)).size;
  return (
    <div className={styles.panel} data-testid="team-progress">
      <div className={styles.header} onClick={() => setCollapsed(!collapsed)}>
        <TeamOutlined className={styles.headerIcon} />
        <span className={styles.title}>专家团</span>
        <span className={styles.summary}>
          {experts} 位专家
          {running > 0
            ? ` · ${running} 位进行中`
            : streaming
              ? ' · 汇总中'
              : ' · 已完成'}
        </span>
        <span className={styles.toggle}>
          {collapsed ? <RightOutlined /> : <DownOutlined />}
        </span>
      </div>
      {!collapsed && (
        <div className={styles.body}>
          {[...top, ...orphans].map(member => (
            <React.Fragment key={member.callId}>
              <MemberRow
                member={member}
                nested={Boolean(member.parentBotId)}
                defaultOpen={member.status === 'running'}
              />
              {nestedOf(member.botId).map(child => (
                <MemberRow
                  key={child.callId}
                  member={child}
                  nested
                  defaultOpen={child.status === 'running'}
                />
              ))}
            </React.Fragment>
          ))}
          {team.files.length > 0 && (
            <div className={styles.files}>
              {team.files.map(file => (
                <a
                  key={file.url}
                  className={styles.file}
                  href={file.url}
                  target="_blank"
                  rel="noreferrer"
                >
                  <FileTextOutlined />
                  <span>{file.fileName}</span>
                  <span className={styles.fileSize}>
                    {formatSize(file.size)}
                  </span>
                </a>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default TeamProgress;

import React, { Suspense, ReactElement } from 'react';
import { Spin } from 'antd';
import { Routes, Route, Navigate } from 'react-router-dom';
import styles from './index.module.scss';

// NOTE: 发布管理只管理智能体（指令型、虚拟人）；工作流在「资源管理 - 工作流」中管理和发布。
const ReleasedPage = React.lazy(() => import('./released-page'));
const AgentList = React.lazy(() => import('./agent-list'));
const ApprovalList = React.lazy(() => import('./approval-list'));
export default function Index(): ReactElement {
  return (
    <div
      className={styles.PublicContent}
      // style={{ width: "calc(100% - 262px)", margin: "0 auto" }}
    >
      <Suspense
        fallback={
          <div className="w-full h-full flex items-center justify-center">
            <Spin />
          </div>
        }
      >
        <Routes>
          <Route path="/" element={<ReleasedPage />}>
            <Route index element={<AgentList AgentType="all" />} />
            <Route path="all" element={<AgentList AgentType="all" />} />
            <Route path="agent" element={<AgentList AgentType="agent" />} />
            <Route path="workflow" element={<Navigate to="../all" replace />} />
            <Route path="virtual" element={<AgentList AgentType="virtual" />} />
            <Route path="approval" element={<ApprovalList />} />
          </Route>
        </Routes>
      </Suspense>
    </div>
  );
}

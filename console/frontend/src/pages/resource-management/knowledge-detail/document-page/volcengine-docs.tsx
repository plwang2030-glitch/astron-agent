import React, { FC, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Alert, Button, Table, Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ExportOutlined, ReloadOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { listVolcengineDocs, VolcengineDoc } from '@/services/knowledge';
import { RepoItem } from '@/types/resource';

const PAGE_SIZE = 20;
const VOLCENGINE_CONSOLE_URL =
  'https://console.volcengine.com/vikingdb/knowledge';

const formatTime = (value?: number | null): string => {
  if (!value) return '-';
  // Volcengine returns seconds.
  const ms = value < 1e12 ? value * 1000 : value;
  return dayjs(ms).format('YYYY-MM-DD HH:mm');
};

/**
 * Documents of a knowledge base bound to Volcengine (Volcengine-RAG). Read-only: upload, parsing and
 * chunking happen in the Volcengine console.
 */
const VolcengineDocs: FC<{ repoId: string; knowledgeInfo: RepoItem }> = ({
  repoId,
  knowledgeInfo,
}) => {
  const { t } = useTranslation();
  const [docs, setDocs] = useState<VolcengineDoc[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);

  const load = (targetPage: number): void => {
    setLoading(true);
    listVolcengineDocs({
      id: repoId,
      offset: (targetPage - 1) * PAGE_SIZE,
      limit: PAGE_SIZE,
    })
      .then(data => {
        setDocs(data?.docs || []);
        setTotal(data?.total || 0);
        setPage(targetPage);
      })
      .catch(() => {
        setDocs([]);
        setTotal(0);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load(1);
  }, [repoId]);

  const statusTag = (doc: VolcengineDoc): React.ReactNode => {
    if (doc.failedMsg) {
      return (
        <Tag color="red" title={doc.failedMsg}>
          {t('knowledge.volcengineDocFailed')}
        </Tag>
      );
    }
    if (doc.processStatus === 0) {
      return <Tag color="green">{t('knowledge.volcengineDocProcessed')}</Tag>;
    }
    return <Tag color="blue">{t('knowledge.volcengineDocProcessing')}</Tag>;
  };

  const columns: ColumnsType<VolcengineDoc> = [
    {
      title: t('knowledge.volcengineDocName'),
      dataIndex: 'name',
      ellipsis: true,
    },
    {
      title: t('knowledge.volcengineDocType'),
      dataIndex: 'type',
      width: 100,
      render: (value?: string) => value || '-',
    },
    {
      title: t('knowledge.volcengineDocChunks'),
      dataIndex: 'pointNum',
      width: 100,
      render: (value?: number | null) => value ?? '-',
    },
    {
      title: t('knowledge.volcengineDocStatus'),
      width: 110,
      render: (_: unknown, doc: VolcengineDoc) => statusTag(doc),
    },
    {
      title: t('knowledge.volcengineDocUpdated'),
      width: 170,
      render: (_: unknown, doc: VolcengineDoc) =>
        formatTime(doc.updateTime || doc.createTime),
    },
  ];

  return (
    <div
      className="h-full flex flex-col gap-4 bg-white rounded-2xl p-6 overflow-auto"
      data-testid="volcengine-docs"
    >
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <span className="text-base font-medium">
            {t('knowledge.volcengineDocsTitle')}
          </span>
          <span className="text-desc text-sm">
            {t('knowledge.volcengineBoundTo')}：
            <code className="ml-1">{knowledgeInfo?.coreRepoId || '-'}</code>
          </span>
        </div>
        <div className="flex items-center gap-2">
          <Button icon={<ReloadOutlined />} onClick={() => load(page)}>
            {t('common.refresh', { defaultValue: '刷新' })}
          </Button>
          <Button
            type="primary"
            icon={<ExportOutlined />}
            href={VOLCENGINE_CONSOLE_URL}
            target="_blank"
            rel="noopener noreferrer"
          >
            {t('knowledge.volcengineOpenConsole')}
          </Button>
        </div>
      </div>
      <Alert type="info" showIcon message={t('knowledge.volcengineDocsHint')} />
      <Table<VolcengineDoc>
        rowKey="docId"
        loading={loading}
        columns={columns}
        dataSource={docs}
        pagination={{
          current: page,
          pageSize: PAGE_SIZE,
          total,
          showSizeChanger: false,
          showTotal: value =>
            t('knowledge.volcengineDocsTotal', { total: value }),
          onChange: load,
        }}
      />
    </div>
  );
};

export default VolcengineDocs;

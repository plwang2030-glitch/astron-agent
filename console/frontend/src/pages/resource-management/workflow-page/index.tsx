import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Empty, Spin, Tag } from 'antd';
import dayjs from 'dayjs';
import MakeCreateModal from '@/components/make-creation';
import { BotData, getAgentList } from '@/services/agent';
import useSpaceStore from '@/store/space-store';

const WORKFLOW_VERSION = 3;

const isPublished = (status: number): boolean => [1, 2, 4].includes(status);

/**
 * 资源管理 - 工作流: fixed business processes that agents can call. Existing workflows keep their
 * own editor and runtime; this page only lists them and starts new ones.
 */
function WorkflowPage(): React.ReactElement {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { spaceId } = useSpaceStore();
  const [list, setList] = useState<BotData[]>([]);
  const [loading, setLoading] = useState(false);
  const [createVisible, setCreateVisible] = useState(false);

  const fetchList = useCallback((searchValue = ''): void => {
    setLoading(true);
    getAgentList({
      pageIndex: 1,
      pageSize: 200,
      botStatus: null,
      sort: 'updateTime',
      searchValue: searchValue.trim(),
      version: WORKFLOW_VERSION,
    })
      .then(res => setList(res?.pageData || []))
      .catch(() => setList([]))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    fetchList();
  }, [fetchList, spaceId]);

  useEffect(() => {
    const handleSearch = (event: Event): void => {
      const detail = (event as CustomEvent<{ value: string; type: string }>)
        .detail;
      if (detail?.type === 'workflow') {
        fetchList(detail.value);
      }
    };
    const handleCreate = (): void => setCreateVisible(true);
    window.addEventListener('headerSearch', handleSearch);
    window.addEventListener('headerCreateWorkflow', handleCreate);
    return () => {
      window.removeEventListener('headerSearch', handleSearch);
      window.removeEventListener('headerCreateWorkflow', handleCreate);
    };
  }, [fetchList]);

  return (
    <div className="w-full h-full overflow-auto">
      <div
        className="mx-auto max-w-[1425px] pt-4 pb-8"
        style={{ width: '86%' }}
      >
        <div className="text-[13px] text-[#7F7F7F] mb-4">
          {t('agentBuilder.resource.workflowDesc')}
        </div>
        {loading ? (
          <div className="flex justify-center py-16">
            <Spin />
          </div>
        ) : list.length === 0 ? (
          <Empty
            className="py-16"
            description={t('agentBuilder.resource.empty')}
          />
        ) : (
          <div
            className="grid grid-cols-3 gap-4"
            data-testid="workflow-resource-list"
          >
            {list.map(item => (
              <div
                key={item.botId}
                className="common-card-item cursor-pointer rounded-[16px] bg-white p-5 border border-[#ECECF4] hover:border-[#6356EA]"
                onClick={() =>
                  navigate(
                    `/work_flow/${item.maasId}/arrange?botId=${item.botId}`
                  )
                }
              >
                <div className="flex items-start gap-3">
                  <img
                    src={item.avatar}
                    className="w-12 h-12 rounded-[10px] object-cover shrink-0"
                    alt=""
                  />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center justify-between gap-2">
                      <span className="font-medium text-[15px] truncate">
                        {item.botName}
                      </span>
                      <Tag
                        color={
                          isPublished(item.botStatus) ? 'green' : 'default'
                        }
                      >
                        {isPublished(item.botStatus)
                          ? t('agentPage.agentPage.published')
                          : t('agentPage.agentPage.unpublished')}
                      </Tag>
                    </div>
                    <div className="mt-1 text-[13px] text-[#7F7F7F] line-clamp-2 h-[40px]">
                      {item.botDesc}
                    </div>
                    <div className="mt-2 text-[12px] text-[#A0A3B1]">
                      {item.createTime
                        ? dayjs(item.createTime.replace(' ', 'T')).format(
                            'YYYY-MM-DD HH:mm'
                          )
                        : ''}
                    </div>
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
      {createVisible && (
        <MakeCreateModal
          visible={createVisible}
          onCancel={() => setCreateVisible(false)}
        />
      )}
    </div>
  );
}

export default WorkflowPage;

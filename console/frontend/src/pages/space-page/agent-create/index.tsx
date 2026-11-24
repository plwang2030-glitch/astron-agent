import React, { memo, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Button, Input, Tag, message } from 'antd';
import {
  ArrowUpOutlined,
  EditOutlined,
  PaperClipOutlined,
  SoundOutlined,
  FileTextOutlined,
} from '@ant-design/icons';
import VirtualConfig from '@/components/virtual-config-modal';
import { createTalkAgent } from '@/services/spark-common';
import {
  BUILDER_ATTACHMENT_ACCEPT,
  BUILDER_ATTACHMENT_MAX_BYTES,
  BUILDER_ATTACHMENT_MAX_COUNT,
  BuildSession,
  BuilderAttachment,
  createBuildSession,
  listBuildSessions,
} from '@/services/agent-builder';
import useSpaceStore from '@/store/space-store';
import dayjs from 'dayjs';

const SCENARIOS = [
  'service',
  'weekly',
  'contract',
  'sales',
  'recruit',
  'report',
] as const;

const TEXT_EXT = /\.(txt|md|markdown|csv|json)$/i;

/** Agent-first creation entry: one goal box, optional SOP files, scenario templates. */
function AgentCreatePage(): React.ReactElement {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { spaceId } = useSpaceStore();
  const [goal, setGoal] = useState('');
  const [attachments, setAttachments] = useState<BuilderAttachment[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [recent, setRecent] = useState<BuildSession[]>([]);
  const [virtualModal, setVirtualModal] = useState(false);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    listBuildSessions(5)
      .then(list => setRecent(list || []))
      .catch(() => setRecent([]));
  }, [spaceId]);

  const handleFiles = async (files: FileList | null): Promise<void> => {
    if (!files) return;
    const next = [...attachments];
    for (const file of Array.from(files)) {
      if (next.length >= BUILDER_ATTACHMENT_MAX_COUNT) {
        message.warning(t('agentBuilder.entry.tooManyFiles'));
        break;
      }
      if (!TEXT_EXT.test(file.name)) {
        message.warning(
          t('agentBuilder.entry.fileTypeUnsupported', { name: file.name })
        );
        continue;
      }
      if (file.size > BUILDER_ATTACHMENT_MAX_BYTES) {
        message.warning(
          t('agentBuilder.entry.fileTooLarge', { name: file.name })
        );
        continue;
      }
      const content = await file.text();
      next.push({ name: file.name, content });
    }
    setAttachments(next);
  };

  const start = (): void => {
    if (submitting) return;
    if (!goal.trim() && attachments.length === 0) {
      message.info(t('agentBuilder.entry.emptyGoal'));
      return;
    }
    setSubmitting(true);
    createBuildSession({ goal: goal.trim(), attachments })
      .then(session => {
        navigate(`/space/agent-builder/${session.id}`);
      })
      .catch(() => {
        message.error(t('agentBuilder.entry.createFailed'));
      })
      .finally(() => setSubmitting(false));
  };

  return (
    <div className="w-full h-full overflow-auto">
      <div className="mx-auto max-w-[860px] px-6 pt-[72px] pb-12">
        <h1 className="text-[28px] font-semibold text-[#1F1F1F] text-center">
          {t('agentBuilder.entry.title')}
        </h1>
        <p className="mt-3 text-center text-[14px] text-[#7F7F7F] leading-6">
          {t('agentBuilder.entry.subtitle')}
        </p>

        <div className="mt-8 rounded-[16px] border border-[#E4E4F0] bg-white shadow-[0_4px_24px_rgba(0,18,70,0.06)] p-4">
          <Input.TextArea
            data-testid="agent-create-goal"
            value={goal}
            onChange={e => setGoal(e.target.value)}
            placeholder={t('agentBuilder.entry.placeholder')}
            autoSize={{ minRows: 4, maxRows: 10 }}
            maxLength={4000}
            variant="borderless"
            className="!text-[15px] !px-1"
            onPressEnter={e => {
              if (!e.shiftKey && !e.nativeEvent.isComposing) {
                e.preventDefault();
                start();
              }
            }}
          />
          {attachments.length > 0 && (
            <div className="flex flex-wrap gap-2 px-1 pt-2">
              {attachments.map((file, index) => (
                <Tag
                  key={`${file.name}-${index}`}
                  icon={<FileTextOutlined />}
                  closable
                  onClose={() =>
                    setAttachments(prev => prev.filter((_, i) => i !== index))
                  }
                >
                  {file.name}
                </Tag>
              ))}
            </div>
          )}
          <div className="flex items-center justify-between pt-3">
            <div className="flex items-center gap-2">
              <Button
                icon={<PaperClipOutlined />}
                onClick={() => fileInputRef.current?.click()}
              >
                {t('agentBuilder.entry.upload')}
              </Button>
              <span className="text-[12px] text-[#A0A3B1]">
                {t('agentBuilder.entry.uploadHint')}
              </span>
              <input
                ref={fileInputRef}
                type="file"
                multiple
                accept={BUILDER_ATTACHMENT_ACCEPT}
                className="hidden"
                onChange={e => {
                  handleFiles(e.target.files);
                  e.target.value = '';
                }}
              />
            </div>
            <Button
              type="primary"
              data-testid="agent-create-start"
              icon={<ArrowUpOutlined />}
              loading={submitting}
              onClick={start}
            >
              {t('agentBuilder.entry.start')}
            </Button>
          </div>
        </div>

        <div className="mt-8">
          <div className="text-[14px] font-medium text-[#3D3D3D] mb-3">
            {t('agentBuilder.entry.templates')}
          </div>
          <div className="grid grid-cols-3 gap-3">
            {SCENARIOS.map(key => (
              <div
                key={key}
                className="cursor-pointer rounded-[12px] border border-[#ECECF4] bg-white px-4 py-3 hover:border-[#6356EA] transition-colors"
                onClick={() => setGoal(t(`agentBuilder.scenarios.${key}.goal`))}
              >
                <div className="text-[14px] font-medium text-[#1F1F1F]">
                  {t(`agentBuilder.scenarios.${key}.title`)}
                </div>
                <div className="mt-1 text-[12px] text-[#7F7F7F] line-clamp-2 leading-5">
                  {t(`agentBuilder.scenarios.${key}.goal`)}
                </div>
              </div>
            ))}
          </div>
        </div>

        {recent.length > 0 && (
          <div className="mt-8">
            <div className="text-[14px] font-medium text-[#3D3D3D] mb-3">
              {t('agentBuilder.entry.recent')}
            </div>
            <div className="flex flex-col gap-2">
              {recent.map(session => (
                <div
                  key={session.id}
                  className="flex items-center justify-between cursor-pointer rounded-[10px] bg-white border border-[#ECECF4] px-4 py-2 hover:border-[#6356EA]"
                  onClick={() => navigate(`/space/agent-builder/${session.id}`)}
                >
                  <span className="truncate text-[13px] text-[#3D3D3D]">
                    {session.draft?.name && (
                      <span className="font-medium mr-2">
                        {session.draft.name}
                      </span>
                    )}
                    <span className="text-[#7F7F7F]">
                      {session.title || t('agentBuilder.builder.untitled')}
                    </span>
                  </span>
                  <span className="flex items-center gap-2 shrink-0 ml-3">
                    {session.updateTime && (
                      <span className="text-[12px] text-[#A0A3B1]">
                        {dayjs(session.updateTime).format('MM-DD HH:mm')}
                      </span>
                    )}
                    {session.status === 'published' && (
                      <Tag color="green">
                        {t('agentBuilder.entry.published')}
                      </Tag>
                    )}
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}

        <div className="mt-8">
          <div className="text-[14px] font-medium text-[#3D3D3D] mb-3">
            {t('agentBuilder.entry.otherWays')}
          </div>
          <div className="flex gap-3">
            <div
              className="flex-1 flex items-center gap-3 cursor-pointer rounded-[12px] border border-[#ECECF4] bg-white px-4 py-3 hover:border-[#6356EA]"
              onClick={() => navigate('/space/config/base?create=true')}
            >
              <EditOutlined className="text-[18px] text-[#6356EA]" />
              <div>
                <div className="text-[14px] font-medium">
                  {t('agentBuilder.entry.blank')}
                </div>
                <div className="text-[12px] text-[#7F7F7F]">
                  {t('agentBuilder.entry.blankDesc')}
                </div>
              </div>
            </div>
            <div
              className="flex-1 flex items-center gap-3 cursor-pointer rounded-[12px] border border-[#ECECF4] bg-white px-4 py-3 hover:border-[#6356EA]"
              onClick={() => setVirtualModal(true)}
            >
              <SoundOutlined className="text-[18px] text-[#6356EA]" />
              <div>
                <div className="text-[14px] font-medium">
                  {t('agentBuilder.entry.virtual')}
                </div>
                <div className="text-[12px] text-[#7F7F7F]">
                  {t('agentBuilder.entry.virtualDesc')}
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {virtualModal && (
        <VirtualConfig
          visible={virtualModal}
          onSubmit={(values: unknown) => {
            createTalkAgent(values)
              .then(response => {
                const res = response as unknown as {
                  maasId?: number;
                  botId?: number;
                };
                message.success(t('createAgent1.createSuccess'));
                setVirtualModal(false);
                navigate(
                  `/work_flow/${res?.maasId}/arrange?botId=${res?.botId}`
                );
              })
              .catch(() => undefined);
          }}
          onCancel={() => setVirtualModal(false)}
        />
      )}
    </div>
  );
}

export default memo(AgentCreatePage);

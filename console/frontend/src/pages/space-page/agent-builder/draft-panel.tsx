import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import dayjs from 'dayjs';
import {
  Alert,
  Button,
  Checkbox,
  Input,
  Modal,
  Select,
  Switch,
  Tag,
  message,
} from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { aiGenerateCover } from '@/services/spark-common';
import type { BuilderDraft, BuilderSkill } from '@/services/agent-builder';

export interface ModelOption {
  modelId: number;
  modelName: string;
  modelDomain: string;
}

export interface BotTypeOption {
  typeKey: number;
  typeName: string;
}

interface DraftPanelProps {
  draft: BuilderDraft;
  editable: boolean;
  models: ModelOption[];
  botTypes: BotTypeOption[];
  onChange: (draft: BuilderDraft) => void;
}

const Section = ({
  title,
  extra,
  children,
}: {
  title: string;
  extra?: React.ReactNode;
  children: React.ReactNode;
}): React.ReactElement => (
  <div className="rounded-[12px] border border-[#ECECF4] bg-white p-4">
    <div className="flex items-center justify-between mb-3">
      <span className="text-[14px] font-medium text-[#1F1F1F]">{title}</span>
      {extra}
    </div>
    {children}
  </div>
);

const Field = ({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}): React.ReactElement => (
  <div className="mb-3 last:mb-0">
    <div className="text-[12px] text-[#7F7F7F] mb-1">{label}</div>
    {children}
  </div>
);

/** Right-hand live view of the draft. Everything here is editable by hand when not replying. */
function DraftPanel({
  draft,
  editable,
  models,
  botTypes,
  onChange,
}: DraftPanelProps): React.ReactElement {
  const { t } = useTranslation();
  const [skillModal, setSkillModal] = useState<BuilderSkill | null>(null);
  const [skillContent, setSkillContent] = useState('');
  const [avatarLoading, setAvatarLoading] = useState(false);

  const update = (patch: Partial<BuilderDraft>): void =>
    onChange({ ...draft, ...patch });

  const none = (
    <span className="text-[12px] text-[#A0A3B1]">
      {t('agentBuilder.draft.none')}
    </span>
  );

  const examples = [0, 1, 2].map(i => draft.inputExamples?.[i] || '');

  const generateAvatar = (): void => {
    if (!draft.name) {
      message.info(t('agentBuilder.draft.genAvatarNeedName'));
      return;
    }
    setAvatarLoading(true);
    aiGenerateCover({ name: draft.name, botDesc: draft.description || '' })
      .then((url: string) => url && update({ avatar: url }))
      .catch(() => undefined)
      .finally(() => setAvatarLoading(false));
  };

  const changes = [...(draft.changes || [])].reverse();

  return (
    <div className="flex flex-col gap-3" data-testid="agent-builder-draft">
      <Section title={t('agentBuilder.draft.basic')}>
        <div className="flex gap-4">
          <div className="flex flex-col items-center gap-2 shrink-0">
            <img
              src={draft.avatar || '/skylumin-avatar.svg'}
              className="w-16 h-16 rounded-[12px] object-cover border border-[#ECECF4]"
              alt=""
            />
            <Button
              size="small"
              disabled={!editable}
              loading={avatarLoading}
              onClick={generateAvatar}
            >
              {t('agentBuilder.draft.genAvatar')}
            </Button>
          </div>
          <div className="flex-1 min-w-0">
            <Field label={t('agentBuilder.draft.name')}>
              <Input
                data-testid="draft-name"
                value={draft.name || ''}
                maxLength={20}
                showCount
                disabled={!editable}
                onChange={e => update({ name: e.target.value })}
              />
            </Field>
            <Field label={t('agentBuilder.draft.description')}>
              <Input.TextArea
                value={draft.description || ''}
                maxLength={100}
                showCount
                autoSize={{ minRows: 2, maxRows: 3 }}
                disabled={!editable}
                onChange={e => update({ description: e.target.value })}
              />
            </Field>
            <div className="flex gap-3">
              <div className="flex-1">
                <Field label={t('agentBuilder.draft.botType')}>
                  <Select
                    className="w-full"
                    value={draft.botType ?? undefined}
                    disabled={!editable}
                    options={botTypes.map(b => ({
                      label: b.typeName,
                      value: b.typeKey,
                    }))}
                    onChange={value => update({ botType: value })}
                  />
                </Field>
              </div>
              <div className="flex-1">
                <Field label={t('agentBuilder.draft.model')}>
                  <Select
                    className="w-full"
                    value={draft.model?.modelId}
                    disabled={!editable}
                    options={models.map(m => ({
                      label: m.modelName,
                      value: m.modelId,
                    }))}
                    onChange={value => {
                      const m = models.find(item => item.modelId === value);
                      if (m) {
                        update({
                          model: {
                            modelId: m.modelId,
                            modelDomain: m.modelDomain,
                            modelName: m.modelName,
                          },
                        });
                      }
                    }}
                  />
                </Field>
              </div>
            </div>
          </div>
        </div>
        <Field label={t('agentBuilder.draft.prologue')}>
          <Input.TextArea
            value={draft.prologue || ''}
            maxLength={500}
            autoSize={{ minRows: 1, maxRows: 4 }}
            disabled={!editable}
            onChange={e => update({ prologue: e.target.value })}
          />
        </Field>
        <Field label={t('agentBuilder.draft.inputExamples')}>
          <div className="flex flex-col gap-2">
            {examples.map((value, i) => (
              <Input
                key={i}
                value={value}
                maxLength={50}
                disabled={!editable}
                onChange={e => {
                  const next = [...examples];
                  next[i] = e.target.value;
                  // Blank rows are dropped server-side when the draft is saved.
                  update({ inputExamples: next });
                }}
              />
            ))}
          </div>
        </Field>
      </Section>

      <Section title={t('agentBuilder.draft.instructions')}>
        <Input.TextArea
          data-testid="draft-instructions"
          value={draft.instructions || ''}
          placeholder={t('agentBuilder.draft.instructionsEmpty')}
          autoSize={{ minRows: 6, maxRows: 20 }}
          disabled={!editable}
          className="!font-mono !text-[13px]"
          onChange={e => update({ instructions: e.target.value })}
        />
      </Section>

      <Section title={t('agentBuilder.draft.skills')}>
        {draft.skills.length === 0
          ? none
          : draft.skills.map(skill => (
              <div
                key={
                  skill.skillId
                    ? `id-${skill.skillId}`
                    : `f-${skill.folderName}`
                }
                className="flex items-start justify-between gap-3 py-2 border-b border-[#F2F2F7] last:border-0"
              >
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="text-[13px] font-medium">
                      {skill.name}
                    </span>
                    <Tag color={skill.source === 'draft' ? 'purple' : 'blue'}>
                      {skill.source === 'draft'
                        ? t('agentBuilder.draft.skillDraft')
                        : t('agentBuilder.draft.skillLibrary')}
                    </Tag>
                  </div>
                  <div className="text-[12px] text-[#7F7F7F] line-clamp-2">
                    {skill.description}
                  </div>
                </div>
                <div className="flex items-center gap-1 shrink-0">
                  <Button
                    size="small"
                    type="link"
                    onClick={() => {
                      setSkillModal(skill);
                      setSkillContent(skill.content || '');
                    }}
                  >
                    {t('agentBuilder.draft.viewSkill')}
                  </Button>
                  {editable && (
                    <Button
                      size="small"
                      type="text"
                      icon={<DeleteOutlined />}
                      onClick={() =>
                        update({
                          skills: draft.skills.filter(s => s !== skill),
                        })
                      }
                    />
                  )}
                </div>
              </div>
            ))}
      </Section>

      <Section title={t('agentBuilder.draft.knowledge')}>
        {draft.knowledge.length === 0
          ? none
          : draft.knowledge.map(k => (
              <Tag
                key={k.id}
                closable={editable}
                onClose={() =>
                  update({
                    knowledge: draft.knowledge.filter(x => x.id !== k.id),
                  })
                }
              >
                {k.name}
              </Tag>
            ))}
      </Section>

      <Section title={t('agentBuilder.draft.tools')}>
        {draft.tools.length === 0
          ? none
          : draft.tools.map(tool => (
              <Tag
                key={tool.toolId}
                closable={editable}
                onClose={() =>
                  update({
                    tools: draft.tools.filter(x => x.toolId !== tool.toolId),
                  })
                }
              >
                {tool.name}
              </Tag>
            ))}
      </Section>

      <Section title={t('agentBuilder.draft.agents')}>
        {(draft.agents ?? []).length === 0
          ? none
          : (draft.agents ?? []).map(a => (
              <Tag
                key={a.botId}
                closable={editable}
                onClose={() =>
                  update({
                    agents: (draft.agents ?? []).filter(
                      x => x.botId !== a.botId
                    ),
                  })
                }
              >
                {a.name}
              </Tag>
            ))}
      </Section>

      {draft.workflows.length > 0 && (
        <Section title={t('agentBuilder.draft.workflows')}>
          {draft.workflows.map(w => (
            <Tag
              key={w.flowId}
              closable={editable}
              onClose={() =>
                update({
                  workflows: draft.workflows.filter(x => x.flowId !== w.flowId),
                })
              }
            >
              {w.name}
            </Tag>
          ))}
        </Section>
      )}

      <Section title={t('agentBuilder.draft.runModes')}>
        <div className="flex flex-col gap-3">
          <Checkbox checked disabled>
            {t('agentBuilder.draft.runChat')}
          </Checkbox>
          <div className="flex items-center gap-3">
            <Switch
              size="small"
              checked={!!draft.runModes?.schedule?.enabled}
              disabled={!editable}
              onChange={checked =>
                update({
                  runModes: {
                    ...draft.runModes,
                    schedule: { ...draft.runModes.schedule, enabled: checked },
                  },
                })
              }
            />
            <span className="text-[13px] shrink-0">
              {t('agentBuilder.draft.runSchedule')}
            </span>
            <Input
              size="small"
              value={draft.runModes?.schedule?.description || ''}
              placeholder={t('agentBuilder.draft.runSchedulePlaceholder')}
              disabled={!editable || !draft.runModes?.schedule?.enabled}
              onChange={e =>
                update({
                  runModes: {
                    ...draft.runModes,
                    schedule: {
                      ...draft.runModes.schedule,
                      description: e.target.value,
                    },
                  },
                })
              }
            />
          </div>
          <div className="flex items-center gap-3">
            <Switch
              size="small"
              checked={!!draft.runModes?.wecom?.enabled}
              disabled={!editable}
              onChange={checked =>
                update({
                  runModes: { ...draft.runModes, wecom: { enabled: checked } },
                })
              }
            />
            <span className="text-[13px]">
              {t('agentBuilder.draft.runWecom')}
            </span>
          </div>
          <Alert
            type="info"
            showIcon
            message={t('agentBuilder.draft.runModesNote')}
          />
        </div>
      </Section>

      <Section
        title={t('agentBuilder.draft.approvals')}
        extra={
          editable && (
            <Button
              size="small"
              icon={<PlusOutlined />}
              onClick={() =>
                update({
                  approvals: [...draft.approvals, { action: '', reason: '' }],
                })
              }
            >
              {t('agentBuilder.draft.approvalAdd')}
            </Button>
          )
        }
      >
        {draft.approvals.length === 0 && none}
        <div className="flex flex-col gap-2">
          {draft.approvals.map((approval, index) => (
            <div key={index} className="flex items-center gap-2">
              <Input
                size="small"
                className="flex-[3]"
                value={approval.action}
                placeholder={t('agentBuilder.draft.approvalAction')}
                disabled={!editable}
                onChange={e => {
                  const next = [...draft.approvals];
                  next[index] = { ...approval, action: e.target.value };
                  update({ approvals: next });
                }}
              />
              <Input
                size="small"
                className="flex-[2]"
                value={approval.reason || ''}
                placeholder={t('agentBuilder.draft.approvalReason')}
                disabled={!editable}
                onChange={e => {
                  const next = [...draft.approvals];
                  next[index] = { ...approval, reason: e.target.value };
                  update({ approvals: next });
                }}
              />
              {editable && (
                <Button
                  size="small"
                  type="text"
                  icon={<DeleteOutlined />}
                  onClick={() =>
                    update({
                      approvals: draft.approvals.filter((_, i) => i !== index),
                    })
                  }
                />
              )}
            </div>
          ))}
        </div>
        <div className="mt-2 text-[12px] text-[#A0A3B1]">
          {t('agentBuilder.draft.approvalsNote')}
        </div>
      </Section>

      <Section title={t('agentBuilder.draft.buildNote')}>
        <Input.TextArea
          value={draft.buildNote || ''}
          autoSize={{ minRows: 2, maxRows: 8 }}
          disabled={!editable}
          onChange={e => update({ buildNote: e.target.value })}
        />
      </Section>

      <Section title={t('agentBuilder.draft.changes')}>
        {changes.length === 0
          ? none
          : changes.map((change, index) => (
              <div
                key={`${change.at}-${index}`}
                className="flex gap-3 text-[12px] py-1"
                data-testid="draft-change"
              >
                <span className="text-[#A0A3B1] shrink-0">
                  {dayjs(change.at).format('MM-DD HH:mm:ss')}
                </span>
                <span className="text-[#3D3D3D]">
                  {change.tool === 'manual'
                    ? t('agentBuilder.draft.changeManual')
                    : change.summary}
                </span>
              </div>
            ))}
      </Section>

      <Modal
        open={!!skillModal}
        title={`${t('agentBuilder.draft.skillModalTitle')} · ${skillModal?.name || ''}`}
        width={760}
        onCancel={() => setSkillModal(null)}
        okButtonProps={{
          style: {
            display:
              skillModal?.source === 'draft' && editable ? undefined : 'none',
          },
        }}
        onOk={() => {
          if (skillModal) {
            update({
              skills: draft.skills.map(s =>
                s === skillModal ? { ...s, content: skillContent } : s
              ),
            });
          }
          setSkillModal(null);
        }}
      >
        {skillModal?.source === 'draft' ? (
          <Input.TextArea
            value={skillContent}
            autoSize={{ minRows: 12, maxRows: 28 }}
            disabled={!editable}
            className="!font-mono !text-[13px]"
            onChange={e => setSkillContent(e.target.value)}
          />
        ) : (
          <div className="text-[13px] text-[#7F7F7F]">
            {t('agentBuilder.draft.skillLibraryHint')}
          </div>
        )}
      </Modal>
    </div>
  );
}

export default DraftPanel;

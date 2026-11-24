import React, { memo, useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Button, Input, Modal, Segmented, Spin, Tag, message } from 'antd';
import {
  ArrowLeftOutlined,
  FileTextOutlined,
  PaperClipOutlined,
  RollbackOutlined,
} from '@ant-design/icons';
import MarkdownRender from '@/components/markdown-render';
import { postStopChat } from '@/services/chat';
import { getBotType, getModelList } from '@/services/spark-common';
import {
  BUILDER_ATTACHMENT_ACCEPT,
  BUILDER_ATTACHMENT_MAX_BYTES,
  BUILDER_ATTACHMENT_MAX_COUNT,
  BuildSession,
  BuilderAttachment,
  BuilderDraft,
  BuilderMessage,
  getBuildSession,
  publishBuildSession,
  streamBuilderChat,
  undoBuildDraft,
  updateBuildDraft,
} from '@/services/agent-builder';
import DraftPanel, { BotTypeOption, ModelOption } from './draft-panel';
import TrialPanel from './trial-panel';

const POLL_MS = 1500;
const TEXT_EXT = /\.(txt|md|markdown|csv|json)$/i;

interface ChatMessage extends BuilderMessage {
  error?: boolean;
}

/**
 * Build mode: chat with the builder agent on the left, live draft and trial run on the right.
 * The builder edits the draft through draft-only tools; nothing is created until "确认创建".
 */
function AgentBuilderPage(): React.ReactElement {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { sessionId = '' } = useParams<{ sessionId: string }>();

  const [session, setSession] = useState<BuildSession | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [draft, setDraft] = useState<BuilderDraft | null>(null);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const [input, setInput] = useState('');
  const [attachments, setAttachments] = useState<BuilderAttachment[]>([]);
  const [tab, setTab] = useState<'draft' | 'trial'>('draft');
  const [models, setModels] = useState<ModelOption[]>([]);
  const [botTypes, setBotTypes] = useState<BotTypeOption[]>([]);
  const [publishing, setPublishing] = useState(false);

  const dirtyRef = useRef(false);
  const draftRef = useRef<BuilderDraft | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const streamingRef = useRef(false);
  const sseIdRef = useRef('');
  const autoStartedRef = useRef('');
  const listRef = useRef<HTMLDivElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  dirtyRef.current = dirty;
  draftRef.current = draft;
  const published = session?.status === 'published';
  const editable = !streaming && !published;

  /** Applies a server session. Local unsaved edits win over the server draft. */
  const applySession = useCallback(
    (next: BuildSession, options: { messages?: boolean } = {}): void => {
      setSession(next);
      if (!dirtyRef.current) {
        setDraft(next.draft);
      }
      if (options.messages) {
        setMessages(next.messages || []);
      }
    },
    []
  );

  const refresh = useCallback(
    (withMessages: boolean): Promise<BuildSession | null> =>
      getBuildSession(sessionId)
        .then(next => {
          applySession(next, { messages: withMessages });
          return next;
        })
        .catch(() => null),
    [sessionId, applySession]
  );

  useEffect(() => {
    getModelList()
      .then(list =>
        setModels(
          (list || []).map(m => ({
            // The backend sends a numeric id; the shared type declares it as string.
            modelId: Number(m.modelId),
            modelName: m.modelName,
            modelDomain: m.modelDomain,
          }))
        )
      )
      .catch(() => setModels([]));
    getBotType()
      .then(res => setBotTypes((res as unknown as BotTypeOption[]) || []))
      .catch(() => setBotTypes([]));
    return () => abortRef.current?.abort();
  }, []);

  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight });
  }, [messages]);

  const saveDraft = useCallback(async (): Promise<boolean> => {
    if (!dirtyRef.current || !draftRef.current) return true;
    setSaving(true);
    try {
      const next = await updateBuildDraft(sessionId, draftRef.current);
      setDirty(false);
      dirtyRef.current = false;
      applySession(next);
      setDraft(next.draft);
      return true;
    } catch {
      return false;
    } finally {
      setSaving(false);
    }
  }, [sessionId, applySession]);

  const sendToBuilder = useCallback(
    async (text: string, files: BuilderAttachment[] = []): Promise<void> => {
      if (streamingRef.current) {
        message.info(t('agentBuilder.builder.busy'));
        return;
      }
      streamingRef.current = true;
      if (!(await saveDraft())) {
        streamingRef.current = false;
        return;
      }
      const controller = new AbortController();
      abortRef.current = controller;
      sseIdRef.current = '';
      setStreaming(true);
      setMessages(prev => [
        ...prev,
        ...(text || files.length
          ? [
              {
                role: 'user' as const,
                content: text,
                attachments: files.map(f => ({
                  name: f.name,
                  chars: f.content.length,
                })),
              },
            ]
          : []),
        { role: 'assistant' as const, content: '' },
      ]);
      // The builder's tools write the draft while the reply streams; poll so the right side
      // updates live instead of only at the end.
      const poll = window.setInterval(() => refresh(false), POLL_MS);
      let answer = '';
      let failed = false;
      await streamBuilderChat(
        sessionId,
        { text, attachments: files },
        {
          onStart: id => (sseIdRef.current = id),
          onDelta: delta => {
            answer += delta;
            setMessages(prev => {
              const next = [...prev];
              next[next.length - 1] = { role: 'assistant', content: answer };
              return next;
            });
          },
          onError: msg => {
            failed = true;
            setMessages(prev => {
              const next = [...prev];
              next[next.length - 1] = {
                role: 'assistant',
                content: t('agentBuilder.builder.replyFailed', { msg }),
                error: true,
              };
              return next;
            });
          },
        },
        controller.signal
      );
      window.clearInterval(poll);
      streamingRef.current = false;
      setStreaming(false);
      // On success the server holds the saved reply; keep the local error message otherwise.
      await refresh(!failed);
    },
    [sessionId, saveDraft, refresh, t]
  );

  // Load the session; reply to the goal right away if the builder has not answered yet.
  useEffect(() => {
    let cancelled = false;
    setSession(null);
    setDraft(null);
    setDirty(false);
    getBuildSession(sessionId)
      .then(next => {
        if (cancelled) return;
        applySession(next, { messages: true });
        setDraft(next.draft);
        const last = next.messages?.[next.messages.length - 1];
        if (
          next.status !== 'published' &&
          !next.replying &&
          last?.role === 'user' &&
          autoStartedRef.current !== sessionId
        ) {
          autoStartedRef.current = sessionId;
          sendToBuilder('');
        }
      })
      .catch(() => navigate('/space/agent-create'));
    return () => {
      cancelled = true;
    };
    // Load once per session; sendToBuilder / applySession are stable for a given sessionId.
  }, [sessionId]);

  // A reply started elsewhere (e.g. before a page refresh): wait for it to finish.
  useEffect(() => {
    if (!session?.replying || streaming) return;
    const timer = window.setInterval(() => {
      refresh(true).then(next => {
        if (next && !next.replying) window.clearInterval(timer);
      });
    }, POLL_MS);
    return () => window.clearInterval(timer);
  }, [session?.replying, streaming, refresh]);

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
      next.push({ name: file.name, content: await file.text() });
    }
    setAttachments(next);
  };

  const submitInput = (): void => {
    const text = input.trim();
    if ((!text && attachments.length === 0) || streaming || published) return;
    const files = attachments;
    setInput('');
    setAttachments([]);
    sendToBuilder(text, files);
  };

  const stop = (): void => {
    if (sseIdRef.current) {
      postStopChat(sseIdRef.current).catch(() => undefined);
    }
  };

  const undo = (): void => {
    undoBuildDraft(sessionId)
      .then(next => {
        setDirty(false);
        dirtyRef.current = false;
        applySession(next);
        setDraft(next.draft);
        message.success(t('agentBuilder.builder.undoDone'));
      })
      .catch(() => undefined);
  };

  const publish = (): void => {
    if (!draft?.name?.trim()) {
      message.warning(t('agentBuilder.builder.publishNeedName'));
      return;
    }
    if (!draft?.model?.modelId) {
      message.warning(t('agentBuilder.builder.publishNeedModel'));
      return;
    }
    Modal.confirm({
      title: t('agentBuilder.builder.publishConfirmTitle'),
      content: t('agentBuilder.builder.publishConfirmContent'),
      okText: t('agentBuilder.builder.publish'),
      onOk: async () => {
        if (!(await saveDraft())) return;
        setPublishing(true);
        try {
          const botId = await publishBuildSession(sessionId);
          message.success(t('agentBuilder.builder.publishSuccess'));
          navigate(`/space/config/base?botId=${botId}`);
        } catch (err) {
          // Business errors (with a numeric code) are already shown by the http layer; network
          // failures (e.g. the server restarting) are not, so surface them here.
          if (typeof (err as { code?: unknown })?.code !== 'number') {
            message.error(t('agentBuilder.builder.publishFailed'));
          }
        } finally {
          setPublishing(false);
        }
      },
    });
  };

  if (!session || !draft) {
    return (
      <div className="w-full h-full flex items-center justify-center">
        <Spin />
      </div>
    );
  }

  const lastIndex = messages.length - 1;

  return (
    <div className="w-full h-full flex flex-col overflow-hidden bg-[#F7F7FB]">
      <div className="h-[56px] shrink-0 flex items-center justify-between px-5 bg-white border-b border-[#ECECF4]">
        <div className="flex items-center gap-3 min-w-0">
          <Button
            type="text"
            icon={<ArrowLeftOutlined />}
            onClick={() => navigate('/space/agent-create')}
          >
            {t('agentBuilder.builder.back')}
          </Button>
          <img
            src={draft.avatar || '/skylumin-avatar.svg'}
            className="w-7 h-7 rounded-[8px] object-cover"
            alt=""
          />
          <span
            className="font-medium text-[15px] truncate"
            data-testid="builder-title"
          >
            {draft.name || t('agentBuilder.builder.untitled')}
          </span>
          <Tag color={published ? 'green' : 'default'}>
            {published
              ? t('agentBuilder.builder.publishedTag')
              : t('agentBuilder.builder.draftTag')}
          </Tag>
        </div>
        <div className="flex items-center gap-2">
          {!published && dirty && (
            <>
              <span className="text-[12px] text-[#FA8C16]">
                {t('agentBuilder.draft.unsaved')}
              </span>
              <Button loading={saving} onClick={saveDraft}>
                {t('agentBuilder.draft.save')}
              </Button>
            </>
          )}
          <Button
            icon={<RollbackOutlined />}
            disabled={!editable || session.undoDepth === 0}
            onClick={undo}
          >
            {t('agentBuilder.builder.undo')}
          </Button>
          {published ? (
            <Button
              type="primary"
              onClick={() =>
                navigate(`/space/config/base?botId=${session.botId}`)
              }
            >
              {t('agentBuilder.builder.openBot')}
            </Button>
          ) : (
            <Button
              type="primary"
              data-testid="builder-publish"
              disabled={streaming}
              loading={publishing}
              onClick={publish}
            >
              {t('agentBuilder.builder.publish')}
            </Button>
          )}
        </div>
      </div>

      <div className="flex-1 flex overflow-hidden">
        {/* Builder conversation */}
        <div className="w-[42%] min-w-[380px] flex flex-col bg-white border-r border-[#ECECF4]">
          <div className="px-5 pt-4 pb-2 text-[14px] font-medium text-[#1F1F1F]">
            {t('agentBuilder.builder.chatTitle')}
          </div>
          <div
            ref={listRef}
            className="flex-1 overflow-auto px-5 py-2 flex flex-col gap-4"
            data-testid="builder-messages"
          >
            {messages.map((m, index) =>
              m.role === 'user' ? (
                <div
                  key={index}
                  className="self-end max-w-[85%] flex flex-col items-end gap-1"
                >
                  {m.content && (
                    <div className="rounded-[12px] bg-[#6356EA] text-white px-3 py-2 text-[14px] whitespace-pre-wrap">
                      {m.content}
                    </div>
                  )}
                  {(m.attachments || []).map(a => (
                    <Tag key={a.name} icon={<FileTextOutlined />}>
                      {a.name}
                    </Tag>
                  ))}
                </div>
              ) : (
                <div
                  key={index}
                  data-testid="builder-answer"
                  className={`self-start max-w-[95%] rounded-[12px] px-3 py-2 ${m.error ? 'bg-[#FEEDEC] text-[#F74E43]' : 'bg-[#F7F7FB]'}`}
                >
                  {m.content && (
                    <MarkdownRender
                      content={m.content}
                      isSending={streaming && index === lastIndex}
                    />
                  )}
                  {streaming && index === lastIndex && (
                    <div className="flex items-center gap-2 text-[12px] text-[#7F7F7F] mt-1">
                      <Spin size="small" />
                      {m.content
                        ? t('agentBuilder.builder.writing')
                        : t('agentBuilder.builder.thinking')}
                    </div>
                  )}
                </div>
              )
            )}
            {session.replying && !streaming && (
              <div className="flex items-center gap-2 text-[12px] text-[#7F7F7F]">
                <Spin size="small" />
                {t('agentBuilder.builder.writing')}
              </div>
            )}
          </div>
          <div className="px-5 pb-4 pt-2">
            {attachments.length > 0 && (
              <div className="flex flex-wrap gap-2 pb-2">
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
            <div className="flex items-end gap-2 rounded-[12px] border border-[#E4E4F0] p-2">
              <Button
                type="text"
                icon={<PaperClipOutlined />}
                disabled={published}
                onClick={() => fileInputRef.current?.click()}
              />
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
              <Input.TextArea
                data-testid="builder-input"
                value={input}
                variant="borderless"
                disabled={published}
                placeholder={
                  published
                    ? t('agentBuilder.builder.alreadyPublished')
                    : t('agentBuilder.builder.chatPlaceholder')
                }
                autoSize={{ minRows: 1, maxRows: 6 }}
                maxLength={4000}
                onChange={e => setInput(e.target.value)}
                onPressEnter={e => {
                  if (!e.shiftKey && !e.nativeEvent.isComposing) {
                    e.preventDefault();
                    submitInput();
                  }
                }}
              />
              {streaming ? (
                <Button onClick={stop}>{t('agentBuilder.builder.stop')}</Button>
              ) : (
                <Button
                  type="primary"
                  data-testid="builder-send"
                  disabled={published || session.replying}
                  onClick={submitInput}
                >
                  {t('agentBuilder.builder.send')}
                </Button>
              )}
            </div>
          </div>
        </div>

        {/* Live draft + trial run */}
        <div className="flex-1 flex flex-col overflow-hidden">
          <div className="px-5 pt-4 pb-3 flex items-center justify-between">
            <Segmented
              value={tab}
              onChange={value => setTab(value as 'draft' | 'trial')}
              options={[
                { label: t('agentBuilder.draft.tab'), value: 'draft' },
                { label: t('agentBuilder.draft.trialTab'), value: 'trial' },
              ]}
            />
            {streaming && tab === 'draft' && (
              <span className="text-[12px] text-[#7F7F7F]">
                {t('agentBuilder.draft.readonlyWhileReplying')}
              </span>
            )}
          </div>
          <div className="flex-1 overflow-auto px-5 pb-5">
            {tab === 'draft' ? (
              <DraftPanel
                draft={draft}
                editable={editable}
                models={models}
                botTypes={botTypes}
                onChange={next => {
                  setDraft(next);
                  setDirty(true);
                }}
              />
            ) : (
              <TrialPanel sessionId={sessionId} beforeRun={saveDraft} />
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

export default memo(AgentBuilderPage);

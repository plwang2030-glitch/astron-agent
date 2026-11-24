import React, { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Alert, Button, Input } from 'antd';
import MarkdownRender from '@/components/markdown-render';
import { postStopChat } from '@/services/chat';
import { streamBuilderTrial } from '@/services/agent-builder';

interface TrialMessage {
  role: 'user' | 'assistant';
  content: string;
  error?: boolean;
}

interface TrialPanelProps {
  sessionId: string;
  /** Saves pending manual edits so the trial runs the latest draft. */
  beforeRun: () => Promise<boolean>;
}

/** Runs the draft through the regular debug chat path (POST /agent-builder/.../trial). */
function TrialPanel({
  sessionId,
  beforeRun,
}: TrialPanelProps): React.ReactElement {
  const { t } = useTranslation();
  const [messages, setMessages] = useState<TrialMessage[]>([]);
  const [input, setInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const sseIdRef = useRef('');
  const listRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => () => abortRef.current?.abort(), []);

  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight });
  }, [messages]);

  const send = async (): Promise<void> => {
    const text = input.trim();
    if (!text || streaming) return;
    if (!(await beforeRun())) return;
    // History: completed turns only, alternating user / assistant.
    const history: string[] = [];
    messages.filter(m => !m.error).forEach(m => history.push(m.content));
    if (history.length % 2 === 1) history.pop();
    setInput('');
    setStreaming(true);
    setMessages(prev => [
      ...prev,
      { role: 'user', content: text },
      { role: 'assistant', content: '' },
    ]);
    const controller = new AbortController();
    abortRef.current = controller;
    let answer = '';
    await streamBuilderTrial(
      sessionId,
      { text, history },
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
          setMessages(prev => {
            const next = [...prev];
            next[next.length - 1] = {
              role: 'assistant',
              content: t('agentBuilder.trial.failed', { msg }),
              error: true,
            };
            return next;
          });
        },
      },
      controller.signal
    );
    setStreaming(false);
  };

  const stop = (): void => {
    if (sseIdRef.current) {
      postStopChat(sseIdRef.current).catch(() => undefined);
    }
  };

  return (
    <div className="flex flex-col h-full" data-testid="agent-builder-trial">
      <Alert type="info" showIcon message={t('agentBuilder.trial.note')} />
      <div
        ref={listRef}
        className="flex-1 overflow-auto py-4 flex flex-col gap-4"
      >
        {messages.length === 0 && (
          <div className="text-center text-[13px] text-[#A0A3B1] mt-12">
            {t('agentBuilder.trial.empty')}
          </div>
        )}
        {messages.map((m, index) =>
          m.role === 'user' ? (
            <div
              key={index}
              className="self-end max-w-[80%] rounded-[12px] bg-[#6356EA] text-white px-3 py-2 text-[14px] whitespace-pre-wrap"
            >
              {m.content}
            </div>
          ) : (
            <div
              key={index}
              data-testid="trial-answer"
              className={`self-start max-w-[92%] rounded-[12px] px-3 py-2 ${m.error ? 'bg-[#FEEDEC] text-[#F74E43]' : 'bg-white border border-[#ECECF4]'}`}
            >
              {m.content ? (
                <MarkdownRender
                  content={m.content}
                  isSending={streaming && index === messages.length - 1}
                />
              ) : (
                <span className="text-[13px] text-[#A0A3B1]">…</span>
              )}
            </div>
          )
        )}
      </div>
      <div className="flex items-end gap-2">
        <Input.TextArea
          data-testid="trial-input"
          value={input}
          placeholder={t('agentBuilder.trial.placeholder')}
          autoSize={{ minRows: 1, maxRows: 5 }}
          maxLength={4000}
          onChange={e => setInput(e.target.value)}
          onPressEnter={e => {
            if (!e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault();
              send();
            }
          }}
        />
        {streaming ? (
          <Button onClick={stop}>{t('agentBuilder.builder.stop')}</Button>
        ) : (
          <Button type="primary" data-testid="trial-send" onClick={send}>
            {t('agentBuilder.builder.send')}
          </Button>
        )}
        <Button
          disabled={streaming || messages.length === 0}
          onClick={() => setMessages([])}
        >
          {t('agentBuilder.trial.clear')}
        </Button>
      </div>
    </div>
  );
}

export default TrialPanel;

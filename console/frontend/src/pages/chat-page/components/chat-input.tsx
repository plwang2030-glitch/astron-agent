import React from 'react';
import useChatStore from '@/store/chat-store';
import { type BotInfoType, type MessageListType } from '@/types/chat';
import TextArea from 'antd/es/input/TextArea';
import { ReactElement, useCallback, useEffect, useRef, useState } from 'react';
import newChatIcon from '@/assets/imgs/chat/new-chat.svg';
import stopIcon from '@/assets/imgs/chat/stop-icon.svg';
import delIcon from '@/assets/imgs/chat/delete-history.svg';
import { useTranslation } from 'react-i18next';
import clsx from 'clsx';
import { postNewChat } from '@/services/chat';
import { message, Switch, Tooltip } from 'antd';
import useBotInfoStore from '@/store/bot-info-store';
import DeleteModal from './delete-modal';
import RecorderCom, { type RecorderRef } from './recorder-com';
import useChatFileUpload from '@/hooks/use-chat-file-upload';
import MultiUploadButtons from './multi-upload-buttons';
import FileGridDisplay from './file-grid-display';

const ChatInput = (props: {
  handleSendMessage: (params: {
    item: string;
    fileUrl?: string;
    callback?: () => void;
    background?: boolean;
  }) => void;
  botInfo: BotInfoType;
  stopAnswer: () => void;
}): ReactElement => {
  const { handleSendMessage, botInfo, stopAnswer } = props;
  const { t } = useTranslation();
  const messageList = useChatStore(state => state.messageList); //  消息列表
  const streamId = useChatStore(state => state.streamId); //  流式id
  const isLoading = useChatStore(state => state.isLoading); //  是否正在加载
  const currentChatId = useChatStore(state => state.currentChatId); //  当前聊天id
  const setCurrentChatId = useChatStore(state => state.setCurrentChatId);
  const addMessage = useChatStore(state => state.addMessage); //  添加消息
  const workflowOperation = useChatStore(state => state.workflowOperation); //  工作流操作
  const isWorkflowOption = useChatStore(state => state.isWorkflowOption); //  是否有工作流选项
  const chatFileListNoReq = useChatStore(state => state.chatFileListNoReq); //  文件列表
  const setChatFileListNoReq = useChatStore(
    state => state.setChatFileListNoReq
  ); //  设置文件列表
  const [deleteModalOpen, setDeleteModalOpen] = useState<boolean>(false); //  是否显示删除对话框
  const [isComposing, setIsComposing] = useState<boolean>(false); //  是否正在输入
  const [inputValue, setInputValue] = useState<string>(''); //  输入框值
  const [background, setBackground] = useState<boolean>(false); //  后台运行
  // 后台运行只对指令型智能体（含专家团）生效
  const supportsBackground = botInfo?.version === 1;
  const textAreaRef = useRef<HTMLTextAreaElement>(null); //  输入框ref
  const setBotInfo = useBotInfoStore(state => state.setBotInfo);
  const $record = useRef<RecorderRef>(null); //  录音ref
  const recordStartTextRef = useRef<string>(''); //  录音开始时的文本
  const { fileList, setFileList, handleFileSelect, removeFile, hasErrorFiles } =
    useChatFileUpload(botInfo);

  // 检查是否有待选择的工作流选项
  const hasWorkflowOptionsToSelect = (): boolean => {
    if (!isWorkflowOption || !workflowOperation.length) return false;

    // 检查最后一条消息是否有未选择的选项
    const lastMessage = messageList[messageList.length - 1];
    if (
      lastMessage?.reqType === 'BOT' &&
      lastMessage?.workflowEventData?.option &&
      lastMessage.workflowEventData.option.length > 0
    ) {
      // 检查是否有选项没被选中
      const hasUnselectedOptions = lastMessage.workflowEventData.option.some(
        (option: any) => !option.selected
      );
      return hasUnselectedOptions;
    }
    return false;
  };

  useEffect(() => {
    setFileList(chatFileListNoReq);
  }, [chatFileListNoReq]);

  // 录音状态变化回调
  const handleRecorderStatusChange = useCallback(
    (status: 'ready' | 'start' | 'end' | 'play') => {
      // 录音开始时，保存当前文本
      if (status === 'play') {
        recordStartTextRef.current = inputValue || '';
      }
      // 录音结束时，清空 ref
      if (status === 'end') {
        recordStartTextRef.current = '';
      }
    },
    [inputValue]
  );

  //全新对话
  const handleNewChat = async () => {
    if (streamId) {
      message.warning(t('chatPage.chatWindow.answeringInProgress'));
      return;
    }
    if (messageList?.at(-1)?.reqType === 'START') {
      return;
    }
    try {
      const res = await postNewChat(currentChatId);
      const nextChatId = res?.data?.data?.id;
      if (nextChatId) {
        setCurrentChatId(nextChatId);
        setBotInfo({ chatId: nextChatId });
      }
      setChatFileListNoReq([]);
      const startMessage: MessageListType = {
        id: new Date().getTime(),
        reqType: 'START',
        message: t('chatPage.chatWindow.freshStart'),
        updateTime: new Date().toISOString(),
      };
      addMessage(startMessage);
    } catch (error) {
      console.error(error);
    }
  };

  //清除对话历史点击
  const handleClearChatList = () => {
    if (isLoading || streamId) {
      message.warning(t('chatPage.chatWindow.answeringInProgress'));
      return;
    }
    setDeleteModalOpen(true);
  };

  //发送消息
  const handleSend = () => {
    if (!inputValue.trim()) {
      return;
    }
    // 检查是否有错误文件
    if (hasErrorFiles()) {
      message.error(t('chatPage.chatWindow.deleteErrorFilesBeforeSend'));
      return;
    }

    handleSendMessage({
      item: inputValue,
      background: supportsBackground && background,
      callback: () => {
        setInputValue('');
        setFileList([]);
      },
    });
    $record?.current?.stopAudio();
  };

  //按下回车键
  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !isComposing) {
      e.preventDefault();
      handleSend();
    }
  };

  // 同步到全局状态
  useEffect(() => {
    setChatFileListNoReq(fileList);
  }, [fileList]);

  return (
    <div className="pl-2.5 pr-[388px] py-6">
      <div className="w-full mx-auto max-w-[960px]">
        <div className="flex items-center relative">
          {messageList.length > 0 && (
            <div
              className="flex items-center justify-center w-auto h-8 px-2.5 border border-[#d3dbf8] rounded-2xl mb-3 cursor-pointer mr-3 bg-white text-[#333333] hover:border-[#5895f0]"
              onClick={handleNewChat}
            >
              <img src={newChatIcon} alt="" className="w-4 h-4" />
              <span className="text-sm  ml-2">
                {t('chatPage.chatWindow.newChat')}
              </span>
            </div>
          )}
          <div
            className="flex items-center justify-center w-auto h-8 px-2.5 border border-[#d3dbf8] rounded-2xl mb-3 cursor-pointer mr-3 bg-white text-[#333333] hover:border-[#5895f0]"
            onClick={handleClearChatList}
          >
            <img src={delIcon} alt="" className="w-3.5 h-3.5" />
            <span className="text-sm ml-2">
              {t('chatPage.chatWindow.clearChatHistory')}
            </span>
          </div>

          {streamId && (
            <div
              className="absolute right-2.5 flex items-center justify-center px-2 h-8 border border-[#d3dbf8] rounded-2xl mb-3 cursor-pointer bg-white text-[#333333] hover:border-[#5895f0]"
              onClick={stopAnswer}
            >
              <img src={stopIcon} alt="" className="w-4 h-4" />
              <span className="text-sm ml-2 ">
                {t('chatPage.chatWindow.stopOutput')}
              </span>
            </div>
          )}
        </div>
        <div
          className={clsx(
            'rounded-2xl min-h-[140px] bg-white border px-2.5 pt-4 border-[#d3dbf8] focus-within:border-[1.5px] focus-within:border-[#6356EA]',
            {
              'opacity-50 cursor-not-allowed': hasWorkflowOptionsToSelect(),
            }
          )}
        >
          {/* 文件网格显示 */}
          {fileList.length > 0 && (
            <FileGridDisplay files={fileList} onRemoveFile={removeFile} />
          )}

          <TextArea
            placeholder={
              hasWorkflowOptionsToSelect()
                ? t('chatPage.chatWindow.selectOptionFirst')
                : t('chatPage.chatWindow.defaultPlaceholder')
            }
            autoSize={{ minRows: 3, maxRows: 3 }}
            value={inputValue}
            onKeyDown={handleKeyDown}
            onChange={e => {
              setInputValue(e.target.value);
            }}
            className="chat-input-textarea"
            onCompositionStart={() => setIsComposing(true)}
            onCompositionEnd={() => setIsComposing(false)}
            ref={textAreaRef}
            readOnly={hasWorkflowOptionsToSelect()}
            disabled={hasWorkflowOptionsToSelect()}
          />
          <div className="flex items-center justify-between">
            {/* 多文件类型上传按钮 */}
            <MultiUploadButtons
              botInfo={botInfo}
              handleFileSelect={handleFileSelect}
              fileList={fileList}
            />
            <div className="flex items-center pb-2.5">
              {supportsBackground && (
                <Tooltip title="适合耗时较长的专家团任务：发送后立即返回，结果保存在本对话中，完成后在通知中心提醒你">
                  <label
                    className="flex items-center mr-3 text-xs text-[#6b7280] cursor-pointer select-none"
                    data-testid="background-toggle"
                  >
                    <Switch
                      size="small"
                      checked={background}
                      onChange={setBackground}
                      className="mr-1.5"
                    />
                    后台运行
                  </label>
                </Tooltip>
              )}
              <RecorderCom
                changeStatus={handleRecorderStatusChange}
                ref={$record}
                disabled={hasWorkflowOptionsToSelect()}
                send={result => {
                  textAreaRef?.current?.focus();
                  const newValue = (recordStartTextRef.current || '') + result;
                  setInputValue(newValue);
                }}
              />
              <div
                onClick={handleSend}
                className={clsx(
                  'w-10 h-10 bg-no-repeat bg-center ml-4 mr-1.5',
                  inputValue.trim() !== '' && !hasWorkflowOptionsToSelect()
                    ? "!bg-[url('@/assets/imgs/chat/send-hover.svg')] cursor-pointer"
                    : "bg-[url('@/assets/imgs/chat/send.svg')] cursor-not-allowed"
                )}
              />
            </div>
          </div>
        </div>
        <div className="mt-2 text-center text-xs text-[#9ca3af]">
          {t('chatPage.chatWindow.aiGeneratedNotice')}
        </div>
      </div>
      <DeleteModal
        open={deleteModalOpen}
        onCancel={() => setDeleteModalOpen(false)}
      />
    </div>
  );
};

export default ChatInput;

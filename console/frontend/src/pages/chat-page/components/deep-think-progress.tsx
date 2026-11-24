import React, { useState } from 'react';
import LoadingAnimate from '@/constants/lottie-react/chat-loading.json';
import Lottie from 'lottie-react';
import { useTranslation } from 'react-i18next';
import { MessageListType } from '@/types/chat';
import MarkdownRender from '@/components/markdown-render';

const DeepThinkProgress: React.FC<{
  answerItem?: MessageListType;
  /**
   * 该回答是否仍在生成。由父组件按流式状态传入；不传时沿用旧逻辑（有正文或 sid 即视为思考完成）。
   * 停止输出或出错时回答可能只有思考内容、没有正文和 sid，必须靠这个状态结束动画。
   */
  streaming?: boolean;
}> = ({ answerItem, streaming }) => {
  const { t } = useTranslation();
  const [open, setOpen] = useState(true);

  if (!answerItem?.reasoning) {
    return null;
  }
  const hasAnswer = Boolean(answerItem.message || answerItem.sid);
  const thinkComplete = hasAnswer || streaming === false;
  // 思考阶段被中断：没有产出正文
  const interrupted = streaming === false && !answerItem.message;
  const renderThinkText = () => {
    return (
      <div className="flex relative">
        <div className="w-auto flex mr-2.5 flex-col items-center">
          {!thinkComplete ? (
            <div className="flex justify-center">
              <Lottie
                animationData={LoadingAnimate}
                loop={true}
                className="w-[26px] h-[26px]"
                rendererSettings={{
                  preserveAspectRatio: 'xMidYMid slice',
                }}
              />
            </div>
          ) : (
            <div className="flex justify-center items-center w-full h-7">
              <img
                src="https://openres.xfyun.cn/xfyundoc/2025-03-31/1c2b6582-14d3-4741-8361-286401473663/1743423234749/scaasc.svg"
                alt=""
                className="w-[13px] h-[13px] flex-shrink-0"
              />
            </div>
          )}

          <div className="w-0.5 h-full flex-1 bg-[#dfdfdf]" />
        </div>
        <div className="pt-1 pb-2.5 min-h-10 reasoning-markdown">
          <MarkdownRender
            content={answerItem?.reasoning ?? ''}
            isSending={!thinkComplete}
          />
        </div>
      </div>
    );
  };
  return (
    <div
      className={`text-sm text-[#838a95] my-2.5 ${
        !open ? 'h-6 overflow-hidden' : ''
      }`}
    >
      <div
        className="w-full h-6 cursor-pointer flex items-center pb-2.5 select-none tracking-wider"
        onClick={() => {
          setOpen(!open);
        }}
      >
        {t('chatPage.deepThinkProgress.title')}
        <img
          src="https://openres.xfyun.cn/xfyundoc/2025-04-01/52202e3f-c57f-4820-81ee-361335e861f9/1743475056488/vasvasavs.svg"
          alt=""
          className={`w-2 h-auto ml-1 transition-transform duration-200 ${
            !open ? 'rotate-180' : ''
          }`}
        />
      </div>
      {renderThinkText()}
      {thinkComplete ? (
        <div className="flex items-center">
          <img
            src="https://openres.xfyun.cn/xfyundoc/2025-03-31/1c2b6582-14d3-4741-8361-286401473663/1743423234749/scaasc.svg"
            alt=""
            className="mr-2.5"
          />
          {interrupted
            ? t('chatPage.deepThinkProgress.stoppedTip')
            : t('chatPage.deepThinkProgress.endTip')}
        </div>
      ) : null}
    </div>
  );
};

export default React.memo(DeepThinkProgress);

import {
  ApartmentOutlined,
  ApiOutlined,
  CloudServerOutlined,
  DatabaseOutlined,
  RobotOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Spin,
  Tag,
  message,
} from 'antd';
import React, { useEffect, useMemo, useState } from 'react';
import {
  getPlatformAccountCards,
  savePlatformAccountConfig,
} from '@/services/platform-account';
import {
  AiCapabilityKey,
  AiProvider,
  PlatformAccountCard,
  PlatformAccountConfig,
  PlatformAccountType,
} from '@/types/platform-account';

interface ProviderTerms {
  /** 服务商名称，用于提示数据去向 */
  provider: string;
  links: { label: string; url: string }[];
}

const IFLYTEK_TERMS: ProviderTerms = {
  provider: '讯飞开放平台',
  links: [
    {
      label: '服务协议',
      url: 'https://www.xfyun.cn/doc/policy/agreement.html',
    },
    { label: '隐私政策', url: 'https://www.xfyun.cn/doc/policy/privacy.html' },
  ],
};

const VOLCENGINE_TERMS: ProviderTerms = {
  provider: '火山引擎',
  links: [
    { label: '法律协议', url: 'https://www.volcengine.com/docs/6256/64900' },
    { label: '隐私政策', url: 'https://www.volcengine.com/docs/6256/64902' },
  ],
};

const CARD_META: Record<
  PlatformAccountType,
  {
    desc: string;
    icon: React.ReactElement;
    /** 该卡片凭证所属服务商的条款；自定义/自建服务不设置 */
    terms?: ProviderTerms;
  }
> = {
  iflytek_open_platform: {
    desc: '星火对话、图片生成、语音转写、发音人与星火知识库签名能力',
    icon: <CloudServerOutlined />,
    terms: IFLYTEK_TERMS,
  },
  ai_ability_chat: {
    desc: 'OpenAI 兼容对话服务，用于系统内 AI Ability Chat 场景',
    icon: <RobotOutlined />,
  },
  virtual_man: {
    desc: '虚拟人互动 SDK 所需的 AppId 与接口鉴权密钥',
    icon: <ApiOutlined />,
    terms: IFLYTEK_TERMS,
  },
  knowledge_platform: {
    desc: 'RAGFlow 与星火知识库的全局连接配置',
    icon: <DatabaseOutlined />,
  },
  volcengine: {
    desc: '火山方舟（豆包对话、视觉理解、Seedream 生图、联网搜索）、豆包语音（TTS / ASR）与火山知识库凭证',
    icon: <ThunderboltOutlined />,
    terms: VOLCENGINE_TERMS,
  },
  ai_capability_routing: {
    desc: '按能力选择服务商：讯飞或火山引擎（字节），未配置时默认讯飞',
    icon: <ApartmentOutlined />,
  },
};

const PROVIDER_OPTIONS: { label: string; value: AiProvider }[] = [
  { label: '讯飞', value: 'iflytek' },
  { label: '火山引擎（字节）', value: 'volcengine' },
];

const CAPABILITY_ITEMS: {
  key: AiCapabilityKey;
  label: string;
  desc: string;
}[] = [
  {
    key: 'defaultChat',
    label: '默认对话模型',
    desc: '智能体使用内置星火模型时的对话服务',
  },
  {
    key: 'aiAbilityChat',
    label: 'AI 辅助生成',
    desc: '一句话创建、提示词优化等系统内 AI Ability Chat',
  },
  { key: 'webSearch', label: '联网搜索', desc: '对话中的 web_search 工具' },
  { key: 'avatarImage', label: '智能体头像生成', desc: 'AI 生成智能体头像' },
  {
    key: 'imageGeneration',
    label: '图片生成',
    desc: '工具/工作流中的图片生成',
  },
  {
    key: 'imageUnderstanding',
    label: '图片理解',
    desc: '工具/工作流中的图片理解',
  },
  { key: 'ocr', label: '文字识别（OCR）', desc: '工具/工作流中的通用 OCR' },
  { key: 'tts', label: '语音合成（TTS）', desc: '对话朗读与智能语音合成工具' },
  { key: 'asr', label: '语音识别（ASR）', desc: '对话输入框的实时语音转写' },
];

const DEFAULT_ROUTING: Record<AiCapabilityKey, AiProvider> =
  CAPABILITY_ITEMS.reduce(
    (acc, item) => ({ ...acc, [item.key]: 'iflytek' }),
    {} as Record<AiCapabilityKey, AiProvider>
  );

const TermsLinks = ({
  terms,
}: {
  terms: ProviderTerms;
}): React.ReactElement => (
  <span>
    {terms.links.map((link, index) => (
      <React.Fragment key={link.url}>
        {index > 0 && '、'}
        <a
          href={link.url}
          target="_blank"
          rel="noreferrer"
          onClick={e => e.stopPropagation()}
        >
          《{terms.provider}
          {link.label}》
        </a>
      </React.Fragment>
    ))}
  </span>
);

const PlatformAccountManagement = (): React.ReactElement => {
  const [cards, setCards] = useState<PlatformAccountCard[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [activeCard, setActiveCard] = useState<PlatformAccountCard | null>(
    null
  );
  const [form] = Form.useForm<PlatformAccountConfig>();

  const modalTitle = useMemo(
    () => (activeCard ? `${activeCard.name}配置` : ''),
    [activeCard]
  );

  const loadCards = async (): Promise<void> => {
    setLoading(true);
    try {
      const data = await getPlatformAccountCards();
      setCards(data);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadCards();
  }, []);

  const openConfig = (card: PlatformAccountCard): void => {
    setActiveCard(card);
    if (card.type === 'ai_capability_routing') {
      form.setFieldsValue({
        aiCapabilityRouting: {
          ...DEFAULT_ROUTING,
          ...(card.config?.aiCapabilityRouting || {}),
        },
      });
      return;
    }
    form.setFieldsValue(card.config || {});
  };

  const handleSave = async (): Promise<void> => {
    if (!activeCard) {
      return;
    }
    const values = await form.validateFields();
    setSaving(true);
    try {
      await savePlatformAccountConfig(activeCard.type, values);
      message.success('配置已保存');
      setActiveCard(null);
      await loadCards();
    } finally {
      setSaving(false);
    }
  };

  const renderForm = (): React.ReactElement | null => {
    if (!activeCard) {
      return null;
    }
    switch (activeCard.type) {
      case 'iflytek_open_platform':
        return (
          <>
            <Form.Item
              name={['iflytekOpenPlatform', 'platformAppId']}
              label="PLATFORM_APP_ID"
              rules={[{ required: true }]}
            >
              <Input placeholder="请输入" />
            </Form.Item>
            <Form.Item
              name={['iflytekOpenPlatform', 'platformApiKey']}
              label="PLATFORM_API_KEY"
              rules={[{ required: true }]}
            >
              <Input.Password placeholder="请输入" />
            </Form.Item>
            <Form.Item
              name={['iflytekOpenPlatform', 'platformApiSecret']}
              label="PLATFORM_API_SECRET"
              rules={[{ required: true }]}
            >
              <Input.Password placeholder="请输入" />
            </Form.Item>
            <Form.Item
              name={['iflytekOpenPlatform', 'sparkApiPassword']}
              label="SPARK_API_PASSWORD"
              rules={[{ required: true }]}
            >
              <Input.Password placeholder="请输入" />
            </Form.Item>
            <Form.Item
              name={['iflytekOpenPlatform', 'sparkRtasrApiKey']}
              label="SPARK_RTASR_API_KEY"
              rules={[{ required: true }]}
            >
              <Input.Password placeholder="请输入" />
            </Form.Item>
          </>
        );
      case 'ai_ability_chat':
        return (
          <>
            <Form.Item
              name={['aiAbilityChat', 'baseUrl']}
              label="AI_ABILITY_CHAT_BASE_URL"
              rules={[{ required: true }]}
            >
              <Input placeholder="https://spark-api-open.xf-yun.com/v1" />
            </Form.Item>
            <Form.Item
              name={['aiAbilityChat', 'model']}
              label="AI_ABILITY_CHAT_MODEL"
              rules={[{ required: true }]}
            >
              <Input placeholder="请输入" />
            </Form.Item>
            <Form.Item
              name={['aiAbilityChat', 'apiKey']}
              label="AI_ABILITY_CHAT_API_KEY"
              rules={[{ required: true }]}
            >
              <Input.Password placeholder="请输入" />
            </Form.Item>
          </>
        );
      case 'virtual_man':
        return (
          <>
            <Form.Item
              name={['virtualMan', 'sparkVirtualManAppId']}
              label="SPARK_VIRTUAL_MAN_APP_ID"
              rules={[{ required: true }]}
            >
              <Input placeholder="请输入" />
            </Form.Item>
            <Form.Item
              name={['virtualMan', 'sparkVirtualManApiKey']}
              label="SPARK_VIRTUAL_MAN_API_KEY"
              rules={[{ required: true }]}
            >
              <Input.Password placeholder="请输入" />
            </Form.Item>
            <Form.Item
              name={['virtualMan', 'sparkVirtualManApiSecret']}
              label="SPARK_VIRTUAL_MAN_API_SECRET"
              rules={[{ required: true }]}
            >
              <Input.Password placeholder="请输入" />
            </Form.Item>
          </>
        );
      case 'knowledge_platform':
        return (
          <>
            <div className="mb-3 text-base font-medium text-[#202124]">
              RAGFlow
            </div>
            <Form.Item
              name={['knowledgePlatform', 'ragflow', 'baseUrl']}
              label="RAGFLOW_BASE_URL"
            >
              <Input placeholder="http://your-ragflow-url/" />
            </Form.Item>
            <Form.Item
              name={['knowledgePlatform', 'ragflow', 'apiToken']}
              label="RAGFLOW_API_TOKEN"
            >
              <Input.Password placeholder="请输入" />
            </Form.Item>
            <Form.Item
              name={['knowledgePlatform', 'ragflow', 'timeout']}
              label="RAGFLOW_TIMEOUT"
            >
              <InputNumber min={1} className="w-full" placeholder="60" />
            </Form.Item>
            <Form.Item
              name={['knowledgePlatform', 'ragflow', 'defaultGroup']}
              label="RAGFLOW_DEFAULT_GROUP"
            >
              <Input placeholder="请输入" />
            </Form.Item>
            <div className="mb-3 mt-6 text-base font-medium text-[#202124]">
              星火知识库
            </div>
            <Form.Item
              name={['knowledgePlatform', 'xinghuo', 'datasetId']}
              label="XINGHUO_DATASET_ID"
            >
              <Input placeholder="请输入" />
            </Form.Item>
          </>
        );
      case 'volcengine':
        return (
          <>
            <div className="mb-3 text-base font-medium text-[#202124]">
              火山方舟（Ark）
            </div>
            <Form.Item
              name={['volcengine', 'arkBaseUrl']}
              label="ARK_BASE_URL"
              extra="留空使用默认地址"
            >
              <Input placeholder="https://ark.cn-beijing.volces.com/api/v3" />
            </Form.Item>
            <Form.Item name={['volcengine', 'arkApiKey']} label="ARK_API_KEY">
              <Input.Password placeholder="方舟控制台 API Key" />
            </Form.Item>
            <Form.Item
              name={['volcengine', 'chatModel']}
              label="对话模型（Model ID 或接入点 ID）"
            >
              <Input placeholder="doubao-seed-2-1-pro-260915" />
            </Form.Item>
            <Form.Item
              name={['volcengine', 'visionModel']}
              label="视觉理解模型"
            >
              <Input placeholder="doubao-seed-2-1-pro-260915" />
            </Form.Item>
            <Form.Item name={['volcengine', 'imageModel']} label="生图模型">
              <Input placeholder="doubao-seedream-5-0-260128" />
            </Form.Item>
            <Form.Item
              name={['volcengine', 'webSearchSource']}
              label="联网搜索组件"
              extra="需先在方舟控制台开通对应组件"
            >
              <Select
                allowClear
                placeholder="豆包搜索 Custom 版（默认）"
                options={[
                  { label: '豆包搜索 Custom 版（方舟组件）', value: 'doubao' },
                  { label: '联网内容插件（方舟组件）', value: 'plugin' },
                  {
                    label: '豆包搜索 API（独立 API Key）',
                    value: 'doubao_api',
                  },
                ]}
              />
            </Form.Item>
            <Form.Item
              name={['volcengine', 'searchApiKey']}
              label="豆包搜索 API Key"
              extra="豆包搜索控制台创建的 API Key，联网搜索组件选「豆包搜索 API」时使用；按调用次数计费，订阅套餐与按量计费的 Key 不通用"
            >
              <Input.Password placeholder="请输入" />
            </Form.Item>
            <div className="mb-3 mt-6 text-base font-medium text-[#202124]">
              豆包语音（TTS / ASR）
            </div>
            <Form.Item
              name={['volcengine', 'speechApiKey']}
              label="SPEECH_API_KEY"
              extra="新版控制台 API Key；与下方 AppID + Access Token 二选一"
            >
              <Input.Password placeholder="请输入" />
            </Form.Item>
            <Form.Item
              name={['volcengine', 'speechAppId']}
              label="SPEECH_APP_ID"
            >
              <Input placeholder="旧版控制台 AppID" />
            </Form.Item>
            <Form.Item
              name={['volcengine', 'speechAccessToken']}
              label="SPEECH_ACCESS_TOKEN"
            >
              <Input.Password placeholder="旧版控制台 Access Token" />
            </Form.Item>
            <Form.Item
              name={['volcengine', 'ttsResourceId']}
              label="TTS 资源 ID"
            >
              <Select
                allowClear
                placeholder="seed-tts-1.0（默认）"
                options={[
                  {
                    label: '豆包语音合成 1.0（seed-tts-1.0）',
                    value: 'seed-tts-1.0',
                  },
                  {
                    label: '豆包语音合成 2.0（seed-tts-2.0）',
                    value: 'seed-tts-2.0',
                  },
                ]}
              />
            </Form.Item>
            <Form.Item
              name={['volcengine', 'ttsSpeaker']}
              label="默认发音人"
              extra="需与 TTS 资源 ID 版本匹配"
            >
              <Input placeholder="zh_female_shuangkuaisisi_moon_bigtts" />
            </Form.Item>
            <Form.Item
              name={['volcengine', 'asrResourceId']}
              label="ASR 资源 ID"
            >
              <Select
                allowClear
                placeholder="volc.bigasr.sauc.duration（默认）"
                options={[
                  {
                    label: '流式语音识别 1.0（volc.bigasr.sauc.duration）',
                    value: 'volc.bigasr.sauc.duration',
                  },
                  {
                    label: '流式语音识别 2.0（volc.seedasr.sauc.duration）',
                    value: 'volc.seedasr.sauc.duration',
                  },
                ]}
              />
            </Form.Item>
            <div className="mb-3 mt-6 text-base font-medium text-[#202124]">
              火山知识库（VikingDB）
            </div>
            <Form.Item
              name={['volcengine', 'knowledgeApiKey']}
              label="KNOWLEDGE_API_KEY"
              extra="火山引擎控制台「访问控制 - API 访问密钥」中 API Key 的值（点复制得到的长字符串），不是 api-key-xxxx 这样的名称"
              rules={[
                {
                  validator: (_, value?: string) =>
                    value && /^api-key-\d+$/.test(value.trim())
                      ? Promise.reject(
                          new Error(
                            '这是 API Key 的名称，请在火山控制台复制 API Key 的值'
                          )
                        )
                      : Promise.resolve(),
                },
              ]}
            >
              <Input.Password placeholder="请输入" />
            </Form.Item>
            <Form.Item
              name={['volcengine', 'knowledgeCollection']}
              label="火山知识库名称或 ID"
              extra="创建「火山引擎知识库」时关联的知识库：填火山知识库列表中的名称（如 lumin_viking）、项目/名称，或以 kb- 开头的知识库 ID"
            >
              <Input placeholder="lumin_viking" />
            </Form.Item>
            <Form.Item
              name={['volcengine', 'knowledgeHost']}
              label="知识库服务地址"
              extra="留空使用华北（北京）地域；华东（上海）为 api-knowledgebase.mlp.cn-shanghai.volces.com"
            >
              <Input placeholder="api-knowledgebase.mlp.cn-beijing.volces.com" />
            </Form.Item>
            <Form.Item
              name={['volcengine', 'knowledgeProject']}
              label="默认项目"
              extra="按名称关联知识库时使用的项目，留空为 default"
            >
              <Input placeholder="default" />
            </Form.Item>
          </>
        );
      case 'ai_capability_routing':
        return (
          <>
            {CAPABILITY_ITEMS.map(item => (
              <Form.Item
                key={item.key}
                name={['aiCapabilityRouting', item.key]}
                label={item.label}
                extra={item.desc}
              >
                <Select options={PROVIDER_OPTIONS} />
              </Form.Item>
            ))}
          </>
        );
      default:
        return null;
    }
  };

  return (
    <div className="h-full overflow-auto bg-[#f7f8fa] px-8 py-7">
      <div className="mb-6">
        <h1 className="m-0 text-2xl font-semibold text-[#202124]">
          平台账号管理
        </h1>
      </div>
      <Spin spinning={loading}>
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2 2xl:grid-cols-4">
          {cards.map(card => (
            <button
              key={card.type}
              type="button"
              onClick={() => openConfig(card)}
              className="min-h-[168px] rounded-lg border border-[#e5e7eb] bg-white p-5 text-left shadow-sm transition hover:border-[#4f46e5] hover:shadow-md"
            >
              <div className="mb-4 flex items-start justify-between gap-3">
                <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-[#eef2ff] text-xl text-[#4338ca]">
                  {CARD_META[card.type].icon}
                </div>
                <Tag color={card.configured ? 'green' : 'default'}>
                  {card.configured ? '已配置' : '未配置'}
                </Tag>
              </div>
              <div className="text-lg font-semibold text-[#202124]">
                {card.name}
              </div>
              <div className="mt-2 text-sm leading-6 text-[#6b7280]">
                {CARD_META[card.type].desc}
              </div>
              {CARD_META[card.type].terms && (
                <div className="mt-2 text-xs leading-5 text-[#9ca3af]">
                  服务条款：
                  <TermsLinks terms={CARD_META[card.type].terms!} />
                </div>
              )}
              <div className="mt-5">
                <Button type="primary">
                  {card.configured ? '修改配置' : '去配置'}
                </Button>
              </div>
            </button>
          ))}
        </div>
      </Spin>

      <Modal
        title={modalTitle}
        open={!!activeCard}
        onCancel={() => setActiveCard(null)}
        onOk={handleSave}
        confirmLoading={saving}
        destroyOnClose
        width={720}
      >
        {activeCard && CARD_META[activeCard.type].terms && (
          <Alert
            className="mb-4"
            type="info"
            showIcon
            message={
              <span>
                凭证由本平台管理员在
                {CARD_META[activeCard.type].terms!.provider}
                开通，调用时用户输入的内容会发送给该服务商处理。请确认已接受
                <TermsLinks terms={CARD_META[activeCard.type].terms!} />
                ，并在本平台的用户协议与隐私政策中告知用户。
              </span>
            }
          />
        )}
        {activeCard?.type === 'ai_capability_routing' && (
          <Alert
            className="mb-4"
            type="info"
            showIcon
            message="切换服务商后，对应能力的用户数据将发送给所选服务商处理，并适用该服务商的服务条款。"
          />
        )}
        <Form form={form} layout="vertical" preserve={false}>
          {renderForm()}
        </Form>
      </Modal>
    </div>
  );
};

export default PlatformAccountManagement;

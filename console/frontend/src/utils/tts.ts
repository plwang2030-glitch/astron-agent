import { getTtsSign } from '@/services/chat';
import { getAuthorization, getFixedUrl } from '@/components/workflow/utils';
import { message } from 'antd';
import { Base64 } from 'js-base64';

// 类型定义
interface ExperienceConfig {
  language?: string;
  isDIY?: boolean;
  isDialect?: boolean;
  speed?: number;
  voice?: number;
  pitch?: number;
  text?: string;
  engineType?: string;
  tte?: string;
  voiceName?: string;
  defaultText?: string;
  close?: () => void;
  isAIPartner?: boolean;
  useTtsSignV2?: boolean;
}

interface SetConfigParams {
  speed?: number;
  voice?: number;
  pitch?: number;
  text?: string;
  defaultText?: string;
  engineType?: string;
  voiceName?: string;
  isDIY?: boolean;
  isDialect?: boolean;
  language?: string;
  tte?: string;
  isAIPartner?: boolean;
  useTtsSignV2?: boolean;
}

interface WebSocketParams {
  common?: {
    app_id: string;
  };
  business?: {
    aue: string;
    auf: string;
    ent: string;
    pitch: number;
    tte: string;
    vcn: string;
    volume: number;
    speed: number;
  };
  data?: {
    status: number;
    text: string | ArrayBuffer;
  };
  header?: {
    app_id: string;
    uid: string;
    did: string;
    imei: string;
    imsi: string;
    mac: string;
    net_type: string;
    net_isp: string;
    status: number;
    request_id: null;
    res_id: string;
  };
  parameter?: {
    tts: {
      vcn: string;
      speed: number;
      volume: number;
      pitch: number;
      bgs: number;
      reg: number;
      rdn: number;
      rhy: number;
      audio: {
        encoding: string;
        sample_rate: number;
        channels: number;
        bit_depth: number;
        frame_size: number;
      };
      pybuf: {
        encoding: string;
        compress: string;
        format: string;
      };
    };
  };
  payload?: {
    text: {
      encoding: string;
      compress: string;
      format: string;
      status: number;
      seq: number;
      text: string | ArrayBuffer;
    };
  };
}

interface WebSocketResponse {
  code?: number;
  message?: string;
  header?: {
    status: number;
  };
  payload?: {
    audio?: {
      audio: string;
    };
  };
  data?: {
    audio: string;
    status?: number;
  };
}

export interface TtsSignResponse {
  appId: string;
  type: string;
  url: string;
}

const NOT_SUPPORT_TIP = '当前浏览器不支持该功能，请换个浏览器试试';

// 优化缓冲参数以减少破音
const START_MIN_FRAMES = 16000 * 0.5; // 至少缓冲 500ms 音频（增加缓冲）
const MAX_WAIT_MS = 600; // 最多等待 600ms（稍微延长等待时间）
const MIN_PLAYABLE_FRAMES = 16000 * 0.2; // 每次播放至少 200ms 的数据

let audioCtx: AudioContext | null = null;
let source: AudioBufferSourceNode | null = null;

class Experience {
  private speed: number;
  private voice: number;
  private pitch: number;
  private text: string;
  private engineType: string;
  private tte: string;
  private voiceName: string;
  private isDialect: boolean;
  private language: string;
  private playState: string;
  private audioDatas: Float32Array[];
  private rawAudioData: number[];
  private audioBuffer: AudioBuffer | undefined;
  private close: (() => void) | undefined;
  private websocket: WebSocket | null = null;
  private audioDatasIndex: number = 0;
  private playTimeout: NodeJS.Timeout | undefined;
  private flag: boolean = false;
  private firstBufferWaitStartMs: number | null = null; // 首次播放的预缓冲起始时间
  // 火山引擎（豆包语音）：通过后端 NDJSON 流式返回 16k PCM
  private volcAbort: AbortController | null = null;
  private volcStreaming: boolean = false;
  private volcCarry: number | null = null;

  constructor({
    speed = 50,
    voice = 7,
    pitch = 50,
    text = '',
    engineType = 'intp65',
    voiceName = '',
    isDialect = false,
    tte = 'UTF8',
    language = 'cn',
    close,
  }: ExperienceConfig = {}) {
    this.speed = speed;
    this.voice = voice;
    this.pitch = pitch;
    this.text = text;
    this.engineType = engineType;
    this.voiceName = voiceName;
    this.isDialect = isDialect;
    this.tte = tte;
    this.language = language;
    this.playState = '';
    this.audioDatas = [];
    this.rawAudioData = [];
    this.close = close;
  }

  setConfig({
    speed,
    voice,
    pitch,
    text,
    engineType,
    voiceName,
    isDialect,
    tte,
    language,
  }: SetConfigParams): void {
    speed !== undefined && (this.speed = speed);
    voice !== undefined && (this.voice = voice);
    pitch !== undefined && (this.pitch = pitch);
    text && (this.text = text);
    engineType && (this.engineType = engineType);
    voiceName && (this.voiceName = voiceName);
    isDialect !== undefined && (this.isDialect = isDialect);
    tte && (this.tte = tte);
    language && (this.language = language);
    this.resetAudio();
  }

  onmessageWork(e: MessageEvent): void {
    switch (e.data.command) {
      case 'newAudioData': {
        this.audioDatas.push(e.data.data);
        if (this.playState === 'ttsing' && this.audioDatas.length === 1) {
          this.playTimeout = setTimeout(() => {
            if (this.playState === 'unTTS') {
              this.resetAudio();
              return;
            }
            this.audioPlay();
          }, 1000) as NodeJS.Timeout;
        }
        break;
      }
      default:
        break;
    }
  }

  // 获取音频
  getAudio(): void {
    getTtsSign({ code: this.voiceName })
      .then((result: TtsSignResponse) => {
        const appId = result.appId;
        const url = result.url;
        const type = result.type;
        if (type === 'VOLCENGINE') {
          void this.streamVolcengine(url);
          return;
        }
        this.connectWebsocket(url, appId, type);
      })
      .catch(err => {
        console.log(err);
        this.resetAudio();
        this.close?.();
      });
  }

  // websocket连接
  connectWebsocket(url: string, appId: string, type: string): void {
    if ('WebSocket' in window) {
      this.websocket = new WebSocket(url);
    } else if ('MozWebSocket' in window) {
      this.websocket = new (
        window as unknown as { MozWebSocket: typeof WebSocket }
      ).MozWebSocket(url);
    } else {
      message.info(NOT_SUPPORT_TIP);
      return;
    }
    const self = this;
    if (!this.websocket) return;
    const voiceValue = type === 'CLONE' ? 'x5_clone' : this.voiceName;

    this.websocket.onopen = () => {
      if (this.playState === 'unTTS') {
        this.resetAudio();
        return;
      }

      const params: WebSocketParams = {
        header: {
          app_id: appId,
          uid: '',
          did: '',
          imei: '',
          imsi: '',
          mac: '',
          net_type: 'wifi',
          net_isp: 'CMCC',
          status: 2,
          request_id: null,
          res_id: type === 'CLONE' ? this.voiceName : '',
        },
        parameter: {
          tts: {
            vcn: voiceValue,
            speed: self.speed,
            volume: 50,
            pitch: self.pitch,
            bgs: 0,
            reg: 0,
            rdn: 0,
            rhy: 0,
            audio: {
              encoding: 'raw',
              sample_rate: 16000,
              channels: 1,
              bit_depth: 16,
              frame_size: 0,
            },
            pybuf: {
              encoding: 'utf8',
              compress: 'raw',
              format: 'plain',
            },
          },
        },
        payload: {
          text: {
            encoding: 'utf8',
            compress: 'raw',
            format: 'plain',
            status: 2,
            seq: 0,
            text: self.encodeText(self.text),
          },
        },
      };

      this.websocket?.send(JSON.stringify(params));
      this.playTimeout = setTimeout(() => {
        this.playSource();
      }, 1500) as NodeJS.Timeout;
    };

    this.websocket.onmessage = (e: MessageEvent) => {
      const jsonData: WebSocketResponse = JSON.parse(e.data);
      const audioData = jsonData?.payload?.audio?.audio;
      if (audioData) {
        this.handleAudioChunk(audioData);
      }
      // 合成结束
      if (jsonData?.header?.status === 2 || jsonData?.data?.status === 2) {
        this.websocket?.close();
      }
    };

    this.websocket.onerror = (e: Event) => {
      console.log(e);
      this.close?.();
    };

    this.websocket.onclose = (e: CloseEvent) => {
      console.log(e);
    };
  }

  // 处理一段 base64 编码的 16k PCM 音频，首包自适应预缓冲后开始播放
  handleAudioChunk(audioData: string): void {
    this.pushPcm(this.base64ToS16(audioData));
  }

  pushPcm(s16: Int16Array): void {
    if (s16.length === 0) return;
    const f32 = this.transS16ToF32(s16);
    this.audioDatas.push(f32);

    // 首包：按数据量自适应预缓冲，避免启动后断流
    if (this.audioDatas.length === 1) {
      clearTimeout(this.playTimeout);

      if (this.firstBufferWaitStartMs == null) {
        this.firstBufferWaitStartMs = Date.now();
      }
      const tryStart = () => {
        // 计算当前可用的帧数
        let frames = 0;
        for (let i = this.audioDatasIndex; i < this.audioDatas.length; i++) {
          const chunk = this.audioDatas[i];
          if (chunk) frames += chunk.length;
        }
        const waited = Date.now() - (this.firstBufferWaitStartMs || 0);

        //缓冲判断，确保有足够数据再开始
        const hasEnoughBuffer = frames >= START_MIN_FRAMES;
        const hasWaitedTooLong = waited >= MAX_WAIT_MS;
        const hasMinimumBuffer = frames >= MIN_PLAYABLE_FRAMES;

        // 合成已结束但总时长不足最小缓冲时也直接播放，避免短文本一直等待
        const streamFinished = !this.isReceiving() && frames > 0;
        if (
          ((hasEnoughBuffer || hasWaitedTooLong) && hasMinimumBuffer) ||
          streamFinished
        ) {
          this.firstBufferWaitStartMs = null;
          this.playSource();
        } else {
          this.playTimeout = setTimeout(tryStart, 50) as NodeJS.Timeout;
        }
      };
      this.playTimeout = setTimeout(tryStart, 50) as NodeJS.Timeout;
    }
  }

  // 是否仍在接收音频数据（讯飞 WebSocket 或火山引擎流）
  isReceiving(): boolean {
    return this.volcStreaming || this.websocket?.readyState === WebSocket.OPEN;
  }

  // 火山引擎（豆包语音）流式合成：后端按行返回 {"code":0,"data":"<base64 pcm>"}
  async streamVolcengine(path: string): Promise<void> {
    if (this.playState === 'unTTS') {
      this.resetAudio();
      return;
    }
    const controller = new AbortController();
    this.volcAbort = controller;
    this.volcStreaming = true;
    this.volcCarry = null;
    let failed = false;
    try {
      const response = await fetch(getFixedUrl(path), {
        method: 'POST',
        headers: {
          Authorization: getAuthorization(),
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          text: this.text,
          speaker: this.voiceName,
          speed: this.speed,
        }),
        signal: controller.signal,
      });
      if (!response.ok || !response.body) {
        throw new Error(`TTS request failed: ${response.status}`);
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let pending = '';
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        pending += decoder.decode(value, { stream: true });
        const lines = pending.split('\n');
        pending = lines.pop() ?? '';
        for (const line of lines) {
          if (!this.handleVolcLine(line)) {
            failed = true;
          }
        }
      }
      if (pending.trim() && !this.handleVolcLine(pending)) {
        failed = true;
      }
    } catch (err) {
      if ((err as Error)?.name !== 'AbortError') {
        console.error('Volcengine TTS failed', err);
        failed = true;
      }
    } finally {
      if (this.volcAbort === controller) {
        this.volcAbort = null;
        this.volcStreaming = false;
      }
    }
    if (failed && this.audioDatas.length === 0) {
      message.error('语音合成失败，请检查火山引擎（字节）豆包语音配置');
      this.resetAudio();
      this.close?.();
    }
  }

  // 豆包语音分片可能是奇数字节，保留末尾 1 字节拼到下一片
  volcBase64ToS16(b64: string): Int16Array {
    const decoded = window.atob(b64);
    const carry = this.volcCarry;
    const total = decoded.length + (carry === null ? 0 : 1);
    const bytes = new Uint8Array(total);
    let offset = 0;
    if (carry !== null) {
      bytes[0] = carry;
      offset = 1;
    }
    for (let i = 0; i < decoded.length; i++) {
      bytes[offset + i] = decoded.charCodeAt(i);
    }
    const even = total - (total % 2);
    this.volcCarry = total % 2 === 1 ? (bytes[total - 1] ?? null) : null;
    return new Int16Array(bytes.buffer, 0, even / 2);
  }

  // 返回 false 表示服务端返回错误
  handleVolcLine(line: string): boolean {
    const text = line.trim();
    if (!text) return true;
    try {
      const obj = JSON.parse(text) as {
        code?: number;
        data?: string;
        message?: string;
      };
      if (obj.data) {
        this.pushPcm(this.volcBase64ToS16(obj.data));
      }
      const code = obj.code ?? 0;
      if (code !== 0 && code !== 20000000) {
        console.error('Volcengine TTS error', code, obj.message);
        return false;
      }
    } catch (e) {
      console.error('Failed to parse Volcengine TTS line', e);
    }
    return true;
  }

  // 编码文本
  encodeText(text: string, encoding?: string): string | ArrayBuffer {
    switch (encoding) {
      case 'utf16le': {
        const buf = new ArrayBuffer(text.length * 4);
        const bufView = new Uint16Array(buf);
        for (let i = 0, strlen = text.length; i < strlen; i++) {
          bufView[i] = text.charCodeAt(i);
        }
        return buf;
      }
      case 'buffer2Base64': {
        let binary = '';
        const bytes = new Uint8Array(text as any);
        const len = bytes.byteLength;
        for (let i = 0; i < len; i++) {
          const byte = bytes[i];
          if (byte !== undefined) {
            binary += String.fromCharCode(byte);
          }
        }
        return window.btoa(binary);
      }
      case 'base64&utf16le': {
        return this.encodeText(
          this.encodeText(text, 'utf16le') as string,
          'buffer2Base64'
        ) as string;
      }
      default: {
        return Base64.encode(text);
      }
    }
  }

  resetAudio(): void {
    this.audioPause();
    this.audioDatasIndex = 0;
    this.audioDatas = [];
    this.websocket && this.websocket.close();
    this.abortVolcengine();
    clearTimeout(this.playTimeout);
    this.firstBufferWaitStartMs = null;
  }

  abortVolcengine(): void {
    this.volcAbort?.abort();
    this.volcAbort = null;
    this.volcStreaming = false;
  }

  audioPlay(): void {
    this.resetAudio();
    source && source.stop();
    if (source?.buffer) source = null;

    try {
      if (!audioCtx) {
        const AudioContextClass =
          window.AudioContext ||
          (window as unknown as { webkitAudioContext: typeof AudioContext })
            .webkitAudioContext;
        audioCtx = new AudioContextClass();
      }
      if (!audioCtx) {
        message.info(NOT_SUPPORT_TIP);
        return;
      }
    } catch (e) {
      message.info(NOT_SUPPORT_TIP);
      return;
    }

    this.audioDatasIndex = 0;
    this.playState = 'play';
    this.getAudio();
  }

  audioPause(): void {
    if (this.playState === 'play') {
      clearTimeout(this.playTimeout);
      try {
        this.playState = 'stop';
        this.audioDatasIndex = 0;
        this.audioDatas = [];
        this.websocket && this.websocket.close();
        this.abortVolcengine();
        source && source.stop();
      } catch (e) {
        console.log(e);
      }
    }
  }

  async playSource(): Promise<void> {
    let bufferLength = 0;
    const dataLength = this.audioDatas.length;

    for (let i = this.audioDatasIndex; i < dataLength; i++) {
      const audioData = this.audioDatas[i];
      if (audioData) {
        bufferLength += audioData.length;
      }
    }

    // 若没有可播放的数据，稍后重试，避免创建零长度 buffer 造成频繁 onended
    if (bufferLength === 0) {
      clearTimeout(this.playTimeout);
      this.playTimeout = setTimeout(() => {
        if (this.playState === 'play') {
          this.playSource();
        }
      }, 10) as NodeJS.Timeout;
      return;
    }

    // ⭐ 优化：确保有足够的数据再播放，避免播放过短的片段导致破音
    const isWebSocketOpen = this.isReceiving();
    if (bufferLength < MIN_PLAYABLE_FRAMES && isWebSocketOpen) {
      // 数据太少且还在接收中，等待更多数据
      clearTimeout(this.playTimeout);
      this.playTimeout = setTimeout(() => {
        if (this.playState === 'play') {
          this.playSource();
        }
      }, 50) as NodeJS.Timeout;
      return;
    }

    if (!audioCtx) return;

    const audioBuffer = audioCtx.createBuffer(1, bufferLength, 16000);
    let offset = 0;

    for (let i = this.audioDatasIndex; i < dataLength; i++) {
      const audioData = this.audioDatas[i];
      if (audioData) {
        audioBuffer.copyToChannel(
          audioData as unknown as Float32Array<ArrayBuffer>,
          0,
          offset
        );
        offset += audioData.length;
        this.audioDatasIndex++;
      }
    }

    source = audioCtx.createBufferSource();
    source.buffer = audioBuffer;
    source.connect(audioCtx.destination);

    if (this.flag) return;

    source.start();
    source.onended = () => {
      if (this.playState !== 'play') {
        this.close?.();
        return;
      }
      // 首先检查是否还有未播放的音频数据
      if (this.audioDatasIndex < this.audioDatas.length) {
        // 还有数据，立即继续播放，避免空隙
        if (this.playState === 'play') {
          this.playSource();
        }
      } else if (this.isReceiving()) {
        // 没有更多数据但WebSocket还开着，快速轮询等待新数据
        this.playTimeout = setTimeout(() => {
          if (this.playState === 'play') {
            this.playSource();
          }
        }, 10) as NodeJS.Timeout;
      } else {
        // 没有更多数据且WebSocket已关闭，播放完毕
        this.close?.();
        this.audioPause();
      }
    };
  }

  transToAudioData(
    audioDataStr: string,
    fromRate: number = 16000,
    toRate: number = 22505
  ): void {
    const outputS16 = this.base64ToS16(audioDataStr);
    let output = this.transS16ToF32(outputS16);
    output = this.transSamplingRate(output, fromRate, toRate);
    const outputArray = Array.from(output);

    this.audioDatas.push(...(outputArray as any));
    this.rawAudioData.push(...Array.from(outputS16));
  }

  transSamplingRate(
    data: Float32Array,
    fromRate: number = 44100,
    toRate: number = 16000
  ): Float32Array {
    const fitCount = Math.round(data.length * (toRate / fromRate));
    const newData = new Float32Array(fitCount);

    if (data.length === 0 || fitCount === 0) {
      return newData;
    }

    const springFactor = (data.length - 1) / (fitCount - 1);

    if (data[0] !== undefined) {
      newData[0] = data[0];
    }

    for (let i = 1; i < fitCount - 1; i++) {
      const tmp = i * springFactor;
      const before = Math.floor(tmp);
      const after = Math.ceil(tmp);
      const atPoint = tmp - before;

      const beforeValue = data[before];
      const afterValue = data[after];

      if (beforeValue !== undefined && afterValue !== undefined) {
        newData[i] = beforeValue + (afterValue - beforeValue) * atPoint;
      }
    }

    if (data.length > 0 && fitCount > 0) {
      const lastValue = data[data.length - 1];
      if (lastValue !== undefined) {
        newData[fitCount - 1] = lastValue;
      }
    }

    return newData;
  }

  base64ToS16(base64AudioData: string): Int16Array {
    const decodedData = window.atob(base64AudioData);
    const outputArray = new Uint8Array(decodedData.length);

    for (let i = 0; i < decodedData.length; ++i) {
      outputArray[i] = decodedData.charCodeAt(i);
    }

    return new Int16Array(new DataView(outputArray.buffer).buffer);
  }

  transS16ToF32(input: Int16Array): Float32Array {
    const tmpData: number[] = [];

    for (let i = 0; i < input.length; i++) {
      const value = input[i];
      if (value !== undefined) {
        const d = value < 0 ? value / 0x8000 : value / 0x7fff;
        tmpData.push(d);
      }
    }
    return new Float32Array(tmpData);
  }
}

export default Experience;

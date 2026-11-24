/** An agent attached to another agent; called by the main agent's model as a tool. */
export interface AgentRef {
  botId: number;
  name: string;
  description?: string;
  avatar?: string;
  /** 直接回复用户：只调用它一个时，它的回答流式发给用户，主智能体不再复述 */
  direct?: boolean;
}

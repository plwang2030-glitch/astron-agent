package com.iflytek.astron.console.hub.service.chat.springai;

import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.toolkit.entity.vo.LLMInfoVo;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * A sub-agent (expert) resolved on the request thread (published prompt, model, skills, knowledge
 * ids), ready to be exposed to its parent's model as a {@code call_agent_<botId>} tool.
 *
 * @param llmInfoVo custom model config; {@code null} means the built-in default model
 *        ({@link #sparkModelName}).
 * @param subAgents its own agents (next reference level), already reflected in
 *        {@link #systemPrompt}
 * @param fromLibrary picked from the space's expert library for this turn (auto team) rather than
 *        attached by the user
 * @param deepThinking the agent's 深度思考 switch ({@code false} disables reasoning where supported)
 * @param direct 直接回复用户: when the chat's lead calls only this agent, its answer is streamed to the
 *        user as the final reply (set on the lead's own members only)
 * @param builtinTools opt-in built-in tool groups of the agent, e.g. {@code a_share}
 */
public record PreparedSubAgent(Integer botId,String name,String description,String toolName,String systemPrompt,LLMInfoVo llmInfoVo,String sparkModelName,String openedTool,String mcpServerUrls,List<JSONObject>skills,String tools,String workflows,List<Long>datasetIds,List<PreparedSubAgent>subAgents,boolean fromLibrary,boolean deepThinking,boolean direct,List<String>builtinTools){

/** Copy with its own agents attached and their routing instructions appended to the prompt. */
public PreparedSubAgent withSubAgents(List<PreparedSubAgent>children){if(children==null||children.isEmpty()){return this;}return new PreparedSubAgent(botId,name,description,toolName,StringUtils.defaultString(systemPrompt)+SubAgentPreparer.routingPrompt(children),llmInfoVo,sparkModelName,openedTool,mcpServerUrls,skills,tools,workflows,datasetIds,List.copyOf(children),fromLibrary,deepThinking,direct,builtinTools);}

/** Copy marked as picked from the expert library. */
public PreparedSubAgent asLibraryExpert(){return new PreparedSubAgent(botId,name,description,toolName,systemPrompt,llmInfoVo,sparkModelName,openedTool,mcpServerUrls,skills,tools,workflows,datasetIds,subAgents,true,deepThinking,false,builtinTools);}

/** Copy whose answer goes straight to the user when it is the only agent called. */
public PreparedSubAgent asDirect(){return new PreparedSubAgent(botId,name,description,toolName,systemPrompt,llmInfoVo,sparkModelName,openedTool,mcpServerUrls,skills,tools,workflows,datasetIds,subAgents,fromLibrary,deepThinking,true,builtinTools);}}

package com.iflytek.astron.console.hub.service.chat.impl;

import com.iflytek.astron.console.commons.dto.chat.ChatRespModelDto;
import com.iflytek.astron.console.commons.entity.chat.ChatTraceSource;
import com.iflytek.astron.console.hub.service.chat.TraceToSourceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author mingsuiyongheng
 */
@Service
@Slf4j
public class TraceToSourceServiceImpl implements TraceToSourceService {

    /**
     * Add trace information to response
     *
     * @param respList Response list where each element will have trace information attached
     * @param traceList Trace source list used to get trace content and type
     */
    @Override
    public void respAddTrace(List<ChatRespModelDto> respList, List<ChatTraceSource> traceList) {
        // Each answer gets the trace of its own request. Previously every answer received the last trace
        // of the chat, so e.g. one turn's search sources or expert-team record showed on all answers.
        Map<Long, ChatTraceSource> byReqId = new HashMap<>();
        for (ChatTraceSource chatTraceSource : traceList) {
            if (chatTraceSource != null && chatTraceSource.getReqId() != null) {
                // Later rows win, matching the update-in-place on re-answer.
                byReqId.put(chatTraceSource.getReqId(), chatTraceSource);
            }
        }
        for (ChatRespModelDto dto : respList) {
            ChatTraceSource trace = dto.getReqId() == null ? null : byReqId.get(dto.getReqId());
            if (trace != null) {
                dto.setTraceSource(trace.getContent());
                dto.setSourceType(trace.getType());
            }
        }
    }
}

package com.iflytek.astron.console.hub.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.commons.constant.ResponseEnum;
import com.iflytek.astron.console.commons.exception.BusinessException;
import com.iflytek.astron.console.hub.service.ManagedWebSearchService.SearchAugmentation;
import com.iflytek.astron.console.toolkit.entity.platform.AiCapability;
import com.iflytek.astron.console.toolkit.service.platform.PlatformAccountService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagedWebSearchServiceTest {

    @Mock
    private PlatformAccountService platformAccountService;

    @InjectMocks
    private ManagedWebSearchService managedWebSearchService;

    @Test
    void parseArkSearchResponseMapsCitationsToTrace() {
        JSONObject response = JSON.parseObject("""
                {"output":[
                  {"type":"web_search_call","status":"completed","action":{"type":"search","query":"q"}},
                  {"type":"message","role":"assistant","content":[
                    {"type":"output_text","text":"Answer text.","annotations":[
                      {"type":"url_citation","title":"Title A","url":"https://a.example"},
                      {"type":"url_citation","title":"Title A again","url":"https://a.example"},
                      {"type":"url_citation","title":"","url":"https://b.example"}
                    ]}
                  ]}
                ]}""");

        SearchAugmentation result = ManagedWebSearchService.parseArkSearchResponse(response);

        assertThat(result.failed()).isFalse();
        assertThat(result.summary()).startsWith("Answer text.")
                .contains("[1] Title A https://a.example")
                .contains("[2] https://b.example https://b.example");
        JSONArray trace = JSON.parseArray(result.traceJson());
        assertThat(trace).hasSize(1);
        JSONObject toolCall = trace.getJSONObject(0);
        assertThat(toolCall.getString("deskToolName")).isEqualTo("Web Search");
        JSONArray outputs = toolCall.getJSONObject("web_search").getJSONArray("outputs");
        assertThat(outputs).hasSize(2);
        assertThat(outputs.getJSONObject(1).getIntValue("index")).isEqualTo(2);
    }

    @Test
    void parseArkSearchResponseWithoutMessageFails() {
        SearchAugmentation result = ManagedWebSearchService.parseArkSearchResponse(JSON.parseObject("{\"output\":[]}"));

        assertThat(result.failed()).isTrue();
    }

    @Test
    void searchReturnsFailedResultWhenProviderNotConfigured() {
        when(platformAccountService.routesToVolcengine(AiCapability.WEB_SEARCH)).thenReturn(true);
        when(platformAccountService.requireVolcengineWebSearch())
                .thenThrow(new BusinessException(ResponseEnum.PLATFORM_ACCOUNT_NOT_CONFIGURED, "火山引擎"));

        SearchAugmentation result = managedWebSearchService.search("weather", "u1");

        assertThat(result.failed()).isTrue();
    }
}

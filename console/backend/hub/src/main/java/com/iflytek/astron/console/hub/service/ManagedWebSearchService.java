package com.iflytek.astron.console.hub.service;

import cn.xfyun.api.SparkChatClient;
import cn.xfyun.config.SparkModel;
import cn.xfyun.model.sparkmodel.RoleContent;
import cn.xfyun.model.sparkmodel.SparkChatParam;
import cn.xfyun.model.sparkmodel.WebSearch;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.commons.exception.BusinessException;
import com.iflytek.astron.console.toolkit.entity.platform.AiCapability;
import com.iflytek.astron.console.toolkit.entity.platform.PlatformAccountConfigDto;
import com.iflytek.astron.console.toolkit.entity.platform.VolcengineDefaults;
import com.iflytek.astron.console.toolkit.service.platform.PlatformAccountService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ManagedWebSearchService {

    private static final String SEARCH_SYSTEM_PROMPT = """
            You are a real-time web search assistant.
            Use web search and answer the user's question with a concise factual summary.
            Keep any source reference indices returned by search, such as [1], [2].
            Do not describe yourself, the model, or the search provider.
            Only return external search findings relevant to the user's query.
            """;
    private static final long SEARCH_TIMEOUT_SECONDS = 60L;
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient arkHttpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(SEARCH_TIMEOUT_SECONDS + 10, TimeUnit.SECONDS)
            .build();

    @Resource
    private PlatformAccountService platformAccountService;

    public SearchAugmentation search(String query, String userId) {
        if (StringUtils.isBlank(query)) {
            return SearchAugmentation.empty();
        }
        try {
            if (platformAccountService.routesToVolcengine(AiCapability.WEB_SEARCH)) {
                return searchWithVolcengine(query, userId);
            }
            return searchWithSpark(query, userId);
        } catch (BusinessException e) {
            // Missing platform account must not abort the whole chat; the tool reports a failed search instead.
            log.warn("Managed web search unavailable, userId={}, reason={}", userId, e.getMessage());
            return SearchAugmentation.failed(e.getMessage());
        }
    }

    /**
     * Volcengine Ark Responses API with the built-in {@code web_search} tool. Plain HTTP is used
     * because the Ark-specific tool fields ({@code sources}, {@code max_keyword}) and citation fields
     * are not modeled by the OpenAI SDK already on the classpath.
     */
    private SearchAugmentation searchWithVolcengine(String query, String userId) {
        PlatformAccountConfigDto.VolcengineConfig config = platformAccountService.requireVolcengineWebSearch();
        String source = config.getWebSearchSource();
        if (VolcengineDefaults.WEB_SEARCH_SOURCE_DOUBAO_API.equalsIgnoreCase(source)) {
            return searchWithDoubaoApi(query, userId, config.getSearchApiKey());
        }
        log.info("Managed web search routed to volcengine, userId={}, model={}, source={}",
                userId, config.getChatModel(), source);

        JSONObject tool = new JSONObject();
        tool.put("type", "web_search");
        if (VolcengineDefaults.WEB_SEARCH_SOURCE_PLUGIN.equalsIgnoreCase(source)) {
            tool.put("max_keyword", 2);
        } else {
            tool.put("sources", List.of(VolcengineDefaults.WEB_SEARCH_SOURCE_DOUBAO));
        }
        JSONObject body = new JSONObject();
        body.put("model", config.getChatModel());
        body.put("stream", false);
        body.put("tools", List.of(tool));
        body.put("input", List.of(
                arkInputMessage("system", SEARCH_SYSTEM_PROMPT),
                arkInputMessage("user", query)));

        Request request = new Request.Builder()
                .url(config.getArkBaseUrl() + "/responses")
                .header("Authorization", "Bearer " + config.getArkApiKey())
                .post(RequestBody.create(body.toJSONString(), JSON_MEDIA_TYPE))
                .build();
        long start = System.currentTimeMillis();
        try (Response response = arkHttpClient.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            String text = responseBody == null ? "" : responseBody.string();
            JSONObject json = StringUtils.isBlank(text) ? new JSONObject() : JSON.parseObject(text);
            if (!response.isSuccessful() || json.containsKey("error")) {
                JSONObject err = json.getJSONObject("error");
                String code = err == null ? String.valueOf(response.code()) : err.getString("code");
                String message = err == null ? response.message() : err.getString("message");
                log.warn("Volcengine web search failed, userId={}, httpStatus={}, code={}, message={}",
                        userId, response.code(), code, message);
                return SearchAugmentation.failed("volcengine web search failed: " + code);
            }
            SearchAugmentation result = parseArkSearchResponse(json);
            log.info("Volcengine web search completed, userId={}, costMs={}, failed={}",
                    userId, System.currentTimeMillis() - start, result.failed());
            return result;
        } catch (IOException e) {
            log.warn("Volcengine web search request error, userId={}, errorType={}",
                    userId, e.getClass().getSimpleName());
            return SearchAugmentation.failed("volcengine web search request error");
        }
    }

    /** Results returned by the standalone 豆包搜索 API per call (the API allows 1~50). */
    private static final int DOUBAO_API_COUNT = 8;
    /** The API rejects queries longer than 100 characters. */
    private static final int DOUBAO_API_MAX_QUERY_CHARS = 100;
    private static final int DOUBAO_API_SNIPPET_CHARS = 400;

    /**
     * Standalone 豆包搜索 API ({@code POST /search_api/web_search}, Bearer = 豆包搜索 API Key). It returns raw
     * results (title, site, time, snippet), which go to the calling model as-is: one HTTP call, no
     * extra model round, and the model cites them by index.
     */
    private SearchAugmentation searchWithDoubaoApi(String query, String userId, String apiKey) {
        String trimmed = StringUtils.left(StringUtils.normalizeSpace(query), DOUBAO_API_MAX_QUERY_CHARS);
        JSONObject body = new JSONObject()
                .fluentPut("Query", trimmed)
                .fluentPut("SearchType", "web")
                .fluentPut("Count", DOUBAO_API_COUNT);
        Request request = new Request.Builder()
                .url(VolcengineDefaults.SEARCH_API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body.toJSONString(), JSON_MEDIA_TYPE))
                .build();
        long start = System.currentTimeMillis();
        try (Response response = arkHttpClient.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            String text = responseBody == null ? "" : responseBody.string();
            JSONObject json = StringUtils.isBlank(text) ? new JSONObject() : JSON.parseObject(text);
            JSONObject error = json.getJSONObject("ResponseMetadata") == null
                    ? null
                    : json.getJSONObject("ResponseMetadata").getJSONObject("Error");
            if (!response.isSuccessful() || (error != null && StringUtils.isNotBlank(error.getString("Code")))) {
                String code = error == null ? String.valueOf(response.code()) : error.getString("Code");
                log.warn("Doubao search API failed, userId={}, httpStatus={}, code={}, message={}", userId,
                        response.code(), code, error == null ? response.message() : error.getString("Message"));
                return SearchAugmentation.failed("doubao search api failed: " + code);
            }
            SearchAugmentation result = parseDoubaoApiResponse(json);
            log.info("Doubao search API completed, userId={}, costMs={}, results={}, failed={}", userId,
                    System.currentTimeMillis() - start,
                    json.getJSONObject("Result") == null ? 0 : json.getJSONObject("Result").getIntValue("ResultCount"),
                    result.failed());
            return result;
        } catch (IOException e) {
            log.warn("Doubao search API request error, userId={}, errorType={}", userId, e.getClass().getSimpleName());
            return SearchAugmentation.failed("doubao search api request error");
        }
    }

    static SearchAugmentation parseDoubaoApiResponse(JSONObject json) {
        JSONObject result = json == null ? null : json.getJSONObject("Result");
        JSONArray items = result == null ? null : result.getJSONArray("WebResults");
        if (items == null || items.isEmpty()) {
            return SearchAugmentation.failed("doubao search api returned no results");
        }
        StringBuilder summary = new StringBuilder("Web search results (cite as [n]):");
        JSONArray outputs = new JSONArray();
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item == null) {
                continue;
            }
            int index = outputs.size() + 1;
            String title = StringUtils.defaultIfBlank(item.getString("Title"), item.getString("Url"));
            String snippet = StringUtils.defaultIfBlank(item.getString("Summary"), item.getString("Snippet"));
            summary.append("\n\n[").append(index).append("] ").append(StringUtils.defaultString(title));
            String meta = String.join(" · ", java.util.stream.Stream.of(item.getString("SiteName"),
                    item.getString("PublishTime")).filter(StringUtils::isNotBlank).toList());
            if (!meta.isEmpty()) {
                summary.append("（").append(meta).append("）");
            }
            if (StringUtils.isNotBlank(snippet)) {
                summary.append("\n").append(StringUtils.abbreviate(snippet.trim(), DOUBAO_API_SNIPPET_CHARS));
            }
            if (StringUtils.isNotBlank(item.getString("Url"))) {
                summary.append("\n").append(item.getString("Url"));
                outputs.add(new JSONObject()
                        .fluentPut("index", index)
                        .fluentPut("url", item.getString("Url"))
                        .fluentPut("title", StringUtils.defaultString(title)));
            }
        }
        JSONArray trace = new JSONArray();
        if (!outputs.isEmpty()) {
            trace.add(new JSONObject()
                    .fluentPut("type", "web_search")
                    .fluentPut("deskToolName", "Web Search")
                    .fluentPut("web_search", new JSONObject().fluentPut("outputs", outputs)));
        }
        return new SearchAugmentation(summary.toString(), JSON.toJSONString(trace), false, null);
    }

    static SearchAugmentation parseArkSearchResponse(JSONObject json) {
        StringBuilder summary = new StringBuilder();
        JSONArray outputs = new JSONArray();
        Map<String, Integer> indexByUrl = new LinkedHashMap<>();
        JSONArray output = json.getJSONArray("output");
        if (output != null) {
            for (int i = 0; i < output.size(); i++) {
                JSONObject item = output.getJSONObject(i);
                if (item == null || !"message".equals(item.getString("type"))) {
                    continue;
                }
                JSONArray contents = item.getJSONArray("content");
                if (contents == null) {
                    continue;
                }
                for (int j = 0; j < contents.size(); j++) {
                    JSONObject content = contents.getJSONObject(j);
                    if (content == null || !"output_text".equals(content.getString("type"))) {
                        continue;
                    }
                    summary.append(StringUtils.defaultString(content.getString("text")));
                    collectCitations(content.getJSONArray("annotations"), indexByUrl, outputs);
                }
            }
        }
        if (summary.isEmpty() && outputs.isEmpty()) {
            return SearchAugmentation.failed("managed web search returned no results");
        }
        if (!outputs.isEmpty()) {
            summary.append("\n\nSources:");
            for (int i = 0; i < outputs.size(); i++) {
                JSONObject out = outputs.getJSONObject(i);
                summary.append("\n[")
                        .append(out.getIntValue("index"))
                        .append("] ")
                        .append(out.getString("title"))
                        .append(" ")
                        .append(out.getString("url"));
            }
        }
        JSONArray trace = new JSONArray();
        if (!outputs.isEmpty()) {
            JSONObject webSearch = new JSONObject();
            webSearch.put("outputs", outputs);
            JSONObject toolCall = new JSONObject();
            toolCall.put("type", "web_search");
            toolCall.put("deskToolName", "Web Search");
            toolCall.put("web_search", webSearch);
            trace.add(toolCall);
        }
        return new SearchAugmentation(summary.toString().trim(), JSON.toJSONString(trace), false, null);
    }

    private static void collectCitations(JSONArray annotations, Map<String, Integer> indexByUrl, JSONArray outputs) {
        if (annotations == null) {
            return;
        }
        for (int k = 0; k < annotations.size(); k++) {
            JSONObject annotation = annotations.getJSONObject(k);
            if (annotation == null || !"url_citation".equals(annotation.getString("type"))) {
                continue;
            }
            String url = annotation.getString("url");
            if (StringUtils.isBlank(url) || indexByUrl.containsKey(url)) {
                continue;
            }
            int index = indexByUrl.size() + 1;
            indexByUrl.put(url, index);
            JSONObject out = new JSONObject();
            out.put("index", index);
            out.put("url", url);
            out.put("title", StringUtils.defaultIfBlank(annotation.getString("title"), url));
            outputs.add(out);
        }
    }

    private static JSONObject arkInputMessage(String role, String text) {
        JSONObject part = new JSONObject();
        part.put("type", "input_text");
        part.put("text", text);
        JSONObject message = new JSONObject();
        message.put("role", role);
        message.put("content", List.of(part));
        return message;
    }

    private SearchAugmentation searchWithSpark(String query, String userId) {
        PlatformAccountConfigDto.IflytekOpenPlatformConfig config =
                platformAccountService.requireIflytekOpenPlatform();
        SparkChatClient client = new SparkChatClient.Builder()
                .signatureHttp(config.getSparkApiPassword(), SparkModel.SPARK_X1)
                .build();

        SparkChatParam request = buildSearchRequest(query, userId);
        StringBuffer summary = new StringBuffer();
        StringBuffer trace = new StringBuffer();
        StringBuffer error = new StringBuffer();
        CountDownLatch latch = new CountDownLatch(1);

        client.send(request, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                error.append(e.getMessage());
                latch.countDown();
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (response; ResponseBody body = response.body()) {
                    if (!response.isSuccessful()) {
                        error.append(response.message());
                        return;
                    }
                    if (body == null) {
                        error.append("empty response body");
                        return;
                    }
                    readSearchStream(body, summary, trace);
                } catch (Exception e) {
                    error.append(e.getMessage());
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return SearchAugmentation.failed("managed web search timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SearchAugmentation.failed("managed web search interrupted");
        }

        if (error.length() > 0) {
            return SearchAugmentation.failed(error.toString());
        }

        JSONArray toolCalls = parseToolCalls(trace.toString());
        if (StringUtils.isBlank(summary.toString()) && toolCalls.isEmpty()) {
            return SearchAugmentation.failed("managed web search returned no results");
        }
        return new SearchAugmentation(summary.toString().trim(), JSON.toJSONString(toolCalls), false, null);
    }

    private SparkChatParam buildSearchRequest(String query, String userId) {
        SparkChatParam param = new SparkChatParam();
        param.setUserId(userId);
        param.setMessages(List.of(
                roleContent("system", SEARCH_SYSTEM_PROMPT),
                roleContent("user", query)));

        WebSearch webSearch = new WebSearch();
        webSearch.setEnable(true);
        webSearch.setSearchMode("deep");
        webSearch.setShowRefLabel(true);
        param.setWebSearch(webSearch);
        return param;
    }

    private RoleContent roleContent(String role, String content) {
        RoleContent roleContent = new RoleContent();
        roleContent.setRole(role);
        roleContent.setContent(content);
        return roleContent;
    }

    private void readSearchStream(ResponseBody body, StringBuffer summary, StringBuffer trace) throws IOException {
        BufferedSource source = body.source();
        while (true) {
            String line = source.readUtf8Line();
            if (line == null || line.contains("[DONE]")) {
                break;
            }
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring(5).trim();
            if (StringUtils.isBlank(payload)) {
                continue;
            }
            JSONObject dataObj = JSON.parseObject(payload);
            if (dataObj == null || dataObj.getInteger("code") != 0 || !dataObj.containsKey("choices")) {
                continue;
            }
            JSONArray choices = dataObj.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                continue;
            }
            JSONObject firstChoice = choices.getJSONObject(0);
            if (firstChoice != null) {
                JSONObject delta = firstChoice.getJSONObject("delta");
                if (delta != null && StringUtils.isNotBlank(delta.getString("content"))) {
                    summary.append(delta.getString("content"));
                }
            }
            if (choices.size() > 1) {
                JSONObject secondChoice = choices.getJSONObject(1);
                JSONObject delta = secondChoice == null ? null : secondChoice.getJSONObject("delta");
                JSONArray toolCalls = delta == null ? null : delta.getJSONArray("tool_calls");
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    if (!trace.isEmpty()) {
                        trace.append(",");
                    }
                    trace.append(toolCalls.toJSONString());
                }
            }
        }
    }

    private JSONArray parseToolCalls(String traceContent) {
        if (StringUtils.isBlank(traceContent)) {
            return new JSONArray();
        }
        String normalized = traceContent.trim();
        if (!normalized.startsWith("[")) {
            return new JSONArray();
        }
        normalized = normalized.replace("],[", ",");
        JSONArray toolCalls = JSON.parseArray(normalized);
        if (toolCalls == null) {
            return new JSONArray();
        }
        for (int i = 0; i < toolCalls.size(); i++) {
            JSONObject toolCall = toolCalls.getJSONObject(i);
            if (toolCall != null && !toolCall.containsKey("deskToolName")) {
                toolCall.put("deskToolName", "Web Search");
            }
        }
        return toolCalls;
    }

    public record SearchAugmentation(String summary, String traceJson, boolean failed, String errorMessage) {
        static SearchAugmentation empty() {
            return new SearchAugmentation("", "", false, null);
        }

        static SearchAugmentation failed(String errorMessage) {
            return new SearchAugmentation("", "", true, errorMessage);
        }
    }
}

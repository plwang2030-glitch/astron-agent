package com.iflytek.astron.console.hub.service.volcengine;

import com.iflytek.astron.console.toolkit.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * One-time tickets that authorize the browser's ASR WebSocket handshake. Browsers cannot attach the
 * Bearer token to a WebSocket handshake, so {@code /rtasr/rtasr-sign} (authenticated) issues a
 * short-lived ticket that {@code /rtasr/volc/ws} consumes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VolcAsrTicketService {

    private static final String KEY_PREFIX = "volc_asr_ticket:";
    static final long TTL_SECONDS = 60;

    private final RedisUtil redisUtil;

    public String issue(String uid) {
        String ticket = UUID.randomUUID().toString().replace("-", "");
        redisUtil.put(KEY_PREFIX + ticket, StringUtils.defaultString(uid), TTL_SECONDS);
        return ticket;
    }

    /**
     * Returns the uid bound to the ticket and invalidates it, or null when missing, expired or reused.
     */
    public String consume(String ticket) {
        if (StringUtils.isBlank(ticket) || !StringUtils.isAlphanumeric(ticket) || ticket.length() > 64) {
            return null;
        }
        String key = KEY_PREFIX + ticket;
        Object uid = redisUtil.get(key);
        if (uid == null || !redisUtil.remove(key)) {
            log.warn("Volcengine ASR ticket rejected (missing, expired or reused)");
            return null;
        }
        return String.valueOf(uid);
    }
}

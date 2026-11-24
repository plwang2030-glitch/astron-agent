package com.iflytek.astron.console.hub.websocket;

import com.iflytek.astron.console.hub.service.volcengine.VolcAsrTicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * Registers the Volcengine ASR relay at {@code /rtasr/volc/ws}. The endpoint is excluded from
 * bearer auth (see {@code WebMvcConfig.NO_AUTH_REQUIRED_APIS}); the handshake must carry a one-time
 * {@code ticket} issued by the authenticated {@code POST /rtasr/rtasr-sign}.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class VolcAsrWebSocketConfig implements WebSocketConfigurer {

    public static final String PATH = "/rtasr/volc/ws";

    private final VolcAsrRelayHandler relayHandler;
    private final VolcAsrTicketService ticketService;

    @Override
    public void registerWebSocketHandlers(@NotNull WebSocketHandlerRegistry registry) {
        registry.addHandler(relayHandler, PATH)
                .addInterceptors(new TicketHandshakeInterceptor(ticketService))
                .setAllowedOriginPatterns("*");
    }

    static final class TicketHandshakeInterceptor implements HandshakeInterceptor {
        private final VolcAsrTicketService ticketService;

        TicketHandshakeInterceptor(VolcAsrTicketService ticketService) {
            this.ticketService = ticketService;
        }

        @Override
        public boolean beforeHandshake(@NotNull ServerHttpRequest request, @NotNull ServerHttpResponse response,
                @NotNull WebSocketHandler wsHandler, @NotNull Map<String, Object> attributes) {
            String ticket = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("ticket");
            String uid = ticketService.consume(ticket);
            if (uid == null) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            attributes.put(VolcAsrRelayHandler.ATTR_UID, uid);
            return true;
        }

        @Override
        public void afterHandshake(@NotNull ServerHttpRequest request, @NotNull ServerHttpResponse response,
                @NotNull WebSocketHandler wsHandler, Exception exception) {
            // no-op
        }
    }
}

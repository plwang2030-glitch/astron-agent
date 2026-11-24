package com.iflytek.astron.console.hub.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.websocket.servlet.TomcatWebSocketServletWebServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enables JSR-356 WebSocket support on the embedded Tomcat.
 *
 * <p>
 * toolkit brings {@code spring-boot-starter-undertow} while hub runs on Tomcat. Boot's
 * {@code WebSocketServletAutoConfiguration} evaluates its Undertow branch first, registers the
 * Undertow customizer under the shared bean name {@code websocketServletWebServerCustomizer}, and
 * then skips the Tomcat branch. Tomcat therefore never installs its {@code ServerContainer}, and
 * every {@code @EnableWebSocket} endpoint fails the handshake with "No
 * 'jakarta.websocket.server.ServerContainer' ServletContext attribute". Registering Boot's own
 * Tomcat customizer under a different name fixes that.
 * </p>
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = {"org.apache.catalina.startup.Tomcat", "org.apache.tomcat.websocket.server.WsSci"})
public class TomcatWebSocketConfig {

    @Bean
    public TomcatWebSocketServletWebServerCustomizer tomcatJsr356WebSocketCustomizer() {
        log.info("Registering Tomcat JSR-356 WebSocket customizer");
        return new TomcatWebSocketServletWebServerCustomizer();
    }
}

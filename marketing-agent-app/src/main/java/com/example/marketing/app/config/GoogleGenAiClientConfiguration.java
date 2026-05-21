package com.example.marketing.app.config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
public class GoogleGenAiClientConfiguration {
    private static final Logger log = LoggerFactory.getLogger(GoogleGenAiClientConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(Client.class)
    public Client googleGenAiClient(
            @Value("${spring.ai.google.genai.api-key:}") String apiKey,
            @Value("${agent.llm.http-proxy.enabled:false}") boolean proxyEnabled,
            @Value("${agent.llm.http-proxy.host:}") String proxyHost,
            @Value("${agent.llm.http-proxy.port:0}") int proxyPort,
            @Value("${agent.llm.timeout-seconds:60}") long timeoutSeconds) throws IOException {
        if (proxyEnabled) {
            installProxySelector(proxyHost, proxyPort);
        }
        Client.Builder builder = Client.builder();
        if (StringUtils.hasText(apiKey)) {
            builder.apiKey(apiKey);
        }
        int timeoutMillis = Math.toIntExact(Math.max(1, timeoutSeconds) * 1000L);
        builder.httpOptions(HttpOptions.builder().timeout(timeoutMillis).build());
        return builder.build();
    }

    private void installProxySelector(String proxyHost, int proxyPort) {
        if (!StringUtils.hasText(proxyHost) || proxyPort <= 0) {
            log.warn("Google GenAI HTTP proxy is enabled but host/port is missing. Falling back to JVM defaults.");
            return;
        }
        ProxySelector delegate = ProxySelector.getDefault();
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost.trim(), proxyPort));
        ProxySelector.setDefault(new FixedHttpProxySelector(proxy, delegate));
        log.info("Google GenAI HTTP proxy configured at {}:{}", proxyHost.trim(), proxyPort);
    }

    private static final class FixedHttpProxySelector extends ProxySelector {
        private final Proxy proxy;
        private final ProxySelector delegate;

        private FixedHttpProxySelector(Proxy proxy, ProxySelector delegate) {
            this.proxy = proxy;
            this.delegate = delegate;
        }

        @Override
        public List<Proxy> select(URI uri) {
            if (uri != null && isHttp(uri.getScheme()) && !isLocalAddress(uri.getHost())) {
                return List.of(proxy);
            }
            if (delegate != null) {
                return delegate.select(uri);
            }
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            if (delegate != null) {
                delegate.connectFailed(uri, sa, ioe);
            }
        }

        private boolean isHttp(String scheme) {
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        }

        private boolean isLocalAddress(String host) {
            return host == null
                    || "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host);
        }
    }
}

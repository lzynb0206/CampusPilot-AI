package com.example.demo.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class HttpClientConfig {
    public static final int MAX_TOTAL_CONNECTIONS = 100;
    public static final int MAX_CONNECTIONS_PER_ROUTE = 20;
    public static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(5);
    public static final Timeout RESPONSE_TIMEOUT = Timeout.ofSeconds(30);

    @Bean
    public ConnectionConfig pooledConnectionConfig() {
        return ConnectionConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT)
                .setSocketTimeout(RESPONSE_TIMEOUT)
                .build();
    }

    @Bean
    public RequestConfig pooledRequestConfig() {
        return RequestConfig.custom()
                .setConnectionRequestTimeout(CONNECT_TIMEOUT)
                .setResponseTimeout(RESPONSE_TIMEOUT)
                .build();
    }

    @Bean(destroyMethod = "")
    public PoolingHttpClientConnectionManager pooledConnectionManager(
            ConnectionConfig pooledConnectionConfig) {
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(pooledConnectionConfig)
                .setMaxConnTotal(MAX_TOTAL_CONNECTIONS)
                .setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
                .build();
    }

    @Bean(destroyMethod = "close")
    public CloseableHttpClient pooledHttpClient(
            PoolingHttpClientConnectionManager pooledConnectionManager,
            RequestConfig pooledRequestConfig) {
        return HttpClients.custom()
                .setConnectionManager(pooledConnectionManager)
                .setDefaultRequestConfig(pooledRequestConfig)
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .build();
    }

    @Bean
    public RestTemplate restTemplate(CloseableHttpClient pooledHttpClient) {
        return new RestTemplate(
                new HttpComponentsClientHttpRequestFactory(pooledHttpClient));
    }
}

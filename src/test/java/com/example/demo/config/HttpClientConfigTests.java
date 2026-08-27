package com.example.demo.config;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class HttpClientConfigTests {
    @Test
    void buildsSharedPoolWithRequiredTimeouts() throws Exception {
        HttpClientConfig config = new HttpClientConfig();
        var connectionConfig = config.pooledConnectionConfig();
        var requestConfig = config.pooledRequestConfig();
        var manager = config.pooledConnectionManager(connectionConfig);

        try (CloseableHttpClient client = config.pooledHttpClient(manager, requestConfig)) {
            var restTemplate = config.restTemplate(client);

            assertEquals(5_000, connectionConfig.getConnectTimeout().toMilliseconds());
            assertEquals(30_000, connectionConfig.getSocketTimeout().toMilliseconds());
            assertEquals(5_000, requestConfig.getConnectionRequestTimeout().toMilliseconds());
            assertEquals(30_000, requestConfig.getResponseTimeout().toMilliseconds());
            assertEquals(100, manager.getMaxTotal());
            assertEquals(20, manager.getDefaultMaxPerRoute());
            assertInstanceOf(HttpComponentsClientHttpRequestFactory.class,
                    restTemplate.getRequestFactory());
        }
    }
}

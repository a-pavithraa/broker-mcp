package com.broker.gateway.zerodha;

import com.broker.config.ZerodhaConfig;
import com.broker.exception.BrokerApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

@Service
@ConditionalOnProperty(name = "zerodha.enabled", havingValue = "true")
class ZerodhaInstrumentDownloadClient implements ZerodhaInstrumentCache.CsvDownloader {

    private final RestClient restClient;
    private final ZerodhaSessionManager sessionManager;

    ZerodhaInstrumentDownloadClient(
            RestClient.Builder restClientBuilder,
            String baseUrl,
            ZerodhaSessionManager sessionManager) {
        this(restClientBuilder, new ZerodhaConfig(baseUrl, "free", null), sessionManager);
    }

    @Autowired
    ZerodhaInstrumentDownloadClient(
            RestClient.Builder restClientBuilder,
            ZerodhaConfig zerodhaConfig,
            ZerodhaSessionManager sessionManager) {
        this.restClient = restClientBuilder.clone()
                .baseUrl(zerodhaConfig.baseUrl())
                .build();
        this.sessionManager = sessionManager;
    }

    @Override
    public String download(String exchange) {
        if (!sessionManager.hasActiveSession()) {
            throw new BrokerApiException("Zerodha session is not initialized");
        }

        try {
            byte[] body = restClient.method(HttpMethod.GET)
                    .uri("/instruments/{exchange}", exchange)
                    .accept(MediaType.APPLICATION_OCTET_STREAM, MediaType.TEXT_PLAIN)
                    .header("Accept-Encoding", "gzip")
                    .header("X-Kite-Version", "3")
                    .header("Authorization", "token " + sessionManager.getApiKey() + ":" + sessionManager.getAccessToken())
                    .retrieve()
                    .body(byte[].class);
            return decodeBody(body == null ? new byte[0] : body);
        } catch (RestClientResponseException ex) {
            throw new BrokerApiException(
                    "Zerodha instruments download failed for " + exchange + ": HTTP " + ex.getStatusCode().value(),
                    ex.getStatusCode().value());
        } catch (RestClientException ex) {
            throw new BrokerApiException("Zerodha instruments download failed for " + exchange, ex);
        } catch (IOException ex) {
            throw new BrokerApiException("Failed to decode Zerodha instruments download for " + exchange, ex);
        }
    }

    private String decodeBody(byte[] bytes) throws IOException {
        try (InputStream inputStream = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (ZipException ex) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}

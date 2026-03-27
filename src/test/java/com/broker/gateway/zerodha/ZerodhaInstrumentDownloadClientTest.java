package com.broker.gateway.zerodha;

import com.broker.exception.BrokerApiException;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZerodhaInstrumentDownloadClientTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    void download_shouldSendAuthorizationHeaderAndDecodeGzipBody() throws Exception {
        wireMock.stubFor(get(urlEqualTo("/instruments/NSE"))
                .withHeader("Authorization", equalTo("token api_key:access_token"))
                .withHeader("X-Kite-Version", equalTo("3"))
                .withHeader("Accept", containing("application/octet-stream"))
                .withHeader("Accept", containing("text/plain"))
                .withHeader("Accept-Encoding", equalTo("gzip"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Encoding", "gzip")
                        .withBody(gzip("""
                                instrument_token,tradingsymbol,exchange
                                408065,INFY,NSE
                                """))));

        ZerodhaSessionManager sessionManager = new ZerodhaSessionManager("ZERODHA_USER");
        sessionManager.setSession("api_key", "access_token");
        ZerodhaInstrumentDownloadClient client =
                new ZerodhaInstrumentDownloadClient(RestClient.builder(), wireMock.baseUrl(), sessionManager);

        assertTrue(client.download("NSE").contains("INFY"));
    }

    @Test
    void download_shouldRejectMissingSession() {
        ZerodhaInstrumentDownloadClient client =
                new ZerodhaInstrumentDownloadClient(RestClient.builder(), wireMock.baseUrl(), new ZerodhaSessionManager("ZERODHA_USER"));

        assertThrows(BrokerApiException.class, () -> client.download("NSE"));
    }

    private byte[] gzip(String body) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(output)) {
            gzipOutputStream.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }
}

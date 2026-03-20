package com.broker.service;

import com.broker.model.AnalysisModels.ResolvedStock;
import com.broker.model.AnalysisModels.StockMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StockMetadataServiceTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void shouldPreferCsvSectorAndGroupWhileKeepingCuratedAliases() {
        StockMetadataService service = new StockMetadataService(
                resourceLoader(Map.of(
                        "classpath:stock-metadata.json", """
                                [
                                  {"code":"TATPOW","name":"Tata Power","sector":"Power","group":"Manual Tata","nseSymbol":"TATAPOWER","isin":"INE245A01021","aliases":["tata power","tata power company"]}
                                ]
                                """,
                        "classpath:stock-universe.csv", """
                                icici_code,nse_symbol,company_name,isin_code,industry,group
                                TATPOW,TATAPOWER,Tata Power Company Limited,INE245A01021,Utilities,Tata Group
                                """
                )),
                objectMapper,
                "classpath:stock-universe.csv"
        );

        StockMetadata metadata = service.getMetadata("TATPOW");
        ResolvedStock resolved = service.resolve("tata power company");

        assertEquals("Utilities", metadata.sector());
        assertEquals("Tata Group", metadata.group());
        assertEquals("Tata Power", metadata.name());
        assertEquals("TATAPOWER", metadata.nseSymbol());
        assertEquals("INE245A01021", metadata.isin());
        assertEquals("Utilities", resolved.sector());
        assertEquals("Tata Group", resolved.group());
        assertEquals("TATPOW", resolved.code());
        assertEquals("TATAPOWER", resolved.nseSymbol());
        assertEquals("INE245A01021", resolved.isin());
        assertEquals("TATPOW", service.resolveNseToIcici("TATAPOWER"));
        assertEquals("TATAPOWER", service.resolveIciciToNse("TATPOW"));
    }

    @Test
    void shouldLoadCsvOnlyRowsIntoPrimaryMetadataIndex() {
        StockMetadataService service = new StockMetadataService(
                resourceLoader(Map.of(
                        "classpath:stock-metadata.json", "[]",
                        "classpath:stock-universe.csv", """
                                icici_code,nse_symbol,company_name,isin_code,industry,group
                                EXAMPL,EXAMPLE,Example Industries Limited,INE123A01011,Chemicals,Example Group
                                """
                )),
                objectMapper,
                "classpath:stock-universe.csv"
        );

        StockMetadata metadata = service.getMetadata("EXAMPL");
        ResolvedStock resolved = service.resolve("Example Industries Limited");

        assertEquals("Example Group", metadata.group());
        assertEquals("Chemicals", metadata.sector());
        assertEquals("EXAMPL", resolved.code());
        assertEquals("EXAMPLE", resolved.nseSymbol());
        assertEquals("INE123A01011", resolved.isin());
        assertEquals("Example Group", resolved.group());
        assertEquals("EXAMPL", service.resolveNseToIcici("EXAMPLE"));
        assertEquals("EXAMPLE", service.resolveIciciToNse("EXAMPL"));
    }

    @Test
    void shouldFallBackToIciciCodeWhenNseSymbolIsMissing() {
        StockMetadataService service = new StockMetadataService(
                resourceLoader(Map.of(
                        "classpath:stock-metadata.json", "[]",
                        "classpath:stock-universe.csv", """
                                icici_code,nse_symbol,company_name,isin_code,industry,group
                                BSEONLY,,BSE Only Limited,INE999A01011,Industrials,Example Group
                                """
                )),
                objectMapper,
                "classpath:stock-universe.csv"
        );

        StockMetadata metadata = service.getMetadata("BSEONLY");
        ResolvedStock resolved = service.resolve("BSE Only Limited");

        assertEquals("BSEONLY", metadata.nseSymbol());
        assertEquals("BSEONLY", resolved.nseSymbol());
        assertEquals("BSEONLY", service.resolveIciciToNse("BSEONLY"));
        assertEquals("BSEONLY", service.resolveNseToIcici("BSEONLY"));
        assertEquals("INE999A01011", resolved.isin());
        assertNull(service.getMetadata("MISSING"));
    }

    private ResourceLoader resourceLoader(Map<String, String> contentByLocation) {
        return new ResourceLoader() {
            @Override
            public Resource getResource(String location) {
                String content = contentByLocation.get(location);
                if (content == null) {
                    return new ByteArrayResource(new byte[0]) {
                        @Override
                        public boolean exists() {
                            return false;
                        }
                    };
                }
                return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public ClassLoader getClassLoader() {
                return getClass().getClassLoader();
            }
        };
    }
}

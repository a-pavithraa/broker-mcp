package com.broker;

import com.broker.gateway.icici.BreezeGatewayService;
import com.broker.gateway.BrokerDataProvider;
import com.broker.analysis.CompoundToolService;
import com.broker.gateway.DemoBrokerDataProvider;
import com.broker.gateway.zerodha.ZerodhaGatewayService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SpringBootTest
@ActiveProfiles("demo")
class DemoProfileApplicationContextTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private BrokerDataProvider brokerDataProvider;

    @Autowired
    private CompoundToolService compoundToolService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void demoProfileShouldUseSyntheticProviderWithoutLiveBrokerBeans() {
        assertInstanceOf(DemoBrokerDataProvider.class, brokerDataProvider);
        assertEquals(0, applicationContext.getBeansOfType(BreezeGatewayService.class).size());
        assertEquals(0, applicationContext.getBeansOfType(ZerodhaGatewayService.class).size());
    }

    @Test
    void demoProfileShouldExposeStablePortfolioAndTaxOutputs() throws Exception {
        Map<String, Object> portfolio = objectMapper.readValue(compoundToolService.portfolioSnapshot(), Map.class);
        Map<String, Object> marketSession = (Map<String, Object>) portfolio.get("market_session");
        Map<String, Object> summary = (Map<String, Object>) portfolio.get("summary");
        Map<String, Object> tax = objectMapper.readValue(compoundToolService.taxHarvestReport(), Map.class);
        List<Map<String, Object>> harvestCandidates = (List<Map<String, Object>>) tax.get("harvest_candidates");

        assertEquals("OPEN", marketSession.get("status"));
        assertEquals(8, ((Number) summary.get("holdings_count")).intValue());
        assertEquals(102250.0, ((Number) summary.get("cash_available")).doubleValue(), 0.01);
        assertEquals("OK", tax.get("status"));
        assertFalse(harvestCandidates.isEmpty());
        assertEquals(8, brokerDataProvider.getPortfolioHoldings().size());
        assertEquals(15, brokerDataProvider.getTrades(null, null).size());
        assertEquals(4, brokerDataProvider.getGttOrders().size());
    }
}

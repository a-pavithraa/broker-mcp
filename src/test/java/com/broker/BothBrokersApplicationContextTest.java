package com.broker;

import com.broker.gateway.icici.BreezeSessionManager;
import com.broker.gateway.BrokerDataProvider;
import com.broker.gateway.CompositeBrokerGateway;
import com.broker.gateway.zerodha.ZerodhaSessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SpringBootTest(properties = {
        "breeze.enabled=true",
        "zerodha.enabled=true"
})
class BothBrokersApplicationContextTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private BrokerDataProvider brokerDataProvider;

    @Test
    void contextLoadsWithCompositeBrokerProvider() {
        assertEquals(1, applicationContext.getBeansOfType(BreezeSessionManager.class).size());
        assertEquals(1, applicationContext.getBeansOfType(ZerodhaSessionManager.class).size());
        assertEquals(1, applicationContext.getBeansOfType(CompositeBrokerGateway.class).size());
        assertInstanceOf(CompositeBrokerGateway.class, brokerDataProvider);
    }
}

package com.broker;

import com.broker.gateway.icici.BreezeSessionManager;
import com.broker.gateway.BrokerDataProvider;
import com.broker.gateway.CompositeBrokerGateway;
import com.broker.gateway.zerodha.ZerodhaSessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "breeze.enabled=true",
        "zerodha.enabled=false",
        "broker.tools.mode=full"
})
class IciciOnlyApplicationContextTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private BrokerDataProvider brokerDataProvider;

    @Test
    void contextLoadsWithOnlyIciciBeans() {
        assertEquals(1, applicationContext.getBeansOfType(BreezeSessionManager.class).size());
        assertEquals(0, applicationContext.getBeansOfType(ZerodhaSessionManager.class).size());
        assertTrue(applicationContext.getBeansOfType(CompositeBrokerGateway.class).isEmpty());
        assertFalse(brokerDataProvider instanceof CompositeBrokerGateway);
        assertFalse(applicationContext.containsBeanDefinition("fundsWriteTools"));
    }
}

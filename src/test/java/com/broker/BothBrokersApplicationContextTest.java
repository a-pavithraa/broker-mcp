package com.broker;

import com.broker.service.BreezeSessionManager;
import com.broker.service.BrokerDataProvider;
import com.broker.service.CompositeBrokerGateway;
import com.broker.service.ZerodhaSessionManager;
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

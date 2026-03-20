package com.broker;

import com.broker.service.BrokerDataProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "breeze.enabled=true",
        "zerodha.enabled=true"
})
class BrokerMcpApplicationTests {

    @Autowired
    private BrokerDataProvider brokerDataProvider;

    @Test
    void contextLoads() {
        Assertions.assertNotNull(brokerDataProvider);
    }

}

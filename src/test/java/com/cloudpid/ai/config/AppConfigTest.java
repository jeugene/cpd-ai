package com.cloudpid.ai.config;

import org.aeonbits.owner.ConfigFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    private static AppConfig config;

    @BeforeAll
    static void setUp() {
        config = ConfigFactory.create(AppConfig.class, System.getProperties());
    }

    @Test
    void awsRegion_hasDefault() {
        assertNotNull(config.awsRegion());
        assertFalse(config.awsRegion().isBlank());
    }

    @Test
    void sparkAppName_resolvedFromSystemProperty() {
        Properties props = new Properties();
        props.setProperty("spark.app.name", "cpd-app");
        assertEquals("cpd-app", ConfigFactory.create(AppConfig.class, props).sparkAppName());
    }

    @Test
    void sparkMaster_resolvedFromSystemProperty() {
        Properties props = new Properties();
        props.setProperty("spark.master", "local[4]");
        assertEquals("local[4]", ConfigFactory.create(AppConfig.class, props).sparkMaster());
    }

    @Test
    void sparkWarehousePath_resolvedFromSystemProperty() {
        String path = config.sparkWarehousePath();
        assertNotNull(path);
        assertFalse(path.isBlank());
    }

    @Test
    void icebergCatalogName_defaultsToLocal() {
        assertEquals("local", config.icebergCatalogName());
    }

    @Test
    void icebergNamespace_defaultsToCpd() {
        assertEquals("cpd", config.icebergNamespace());
    }

    @Test
    void config_canBeCreated() {
        assertNotNull(config);
    }
}

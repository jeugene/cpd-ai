package com.cloudpid.ai;

import com.cloudpid.ai.config.AppConfig;
import com.cloudpid.ai.config.AppConfigFactory;
import com.cloudpid.ai.spark.SparkFactory;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SparkDriver {

    private static final Logger log = LoggerFactory.getLogger(SparkDriver.class);

    public static void main(String[] args) {
        log.info("start: cpd-app");
        AppConfig config = AppConfigFactory.get();

        try (SparkSession spark = SparkFactory.create(config)) {
            log.info("SparkSession active version={}", spark.version());
            // ETL entry point
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to close SparkSession", e);
        }

        log.info("end: cpd-app");
    }
}

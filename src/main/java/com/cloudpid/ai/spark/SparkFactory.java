package com.cloudpid.ai.spark;

import com.cloudpid.ai.config.AppConfig;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SparkFactory {

    private static final Logger log = LoggerFactory.getLogger(SparkFactory.class);

    private SparkFactory() {
    }

    public static SparkSession create(AppConfig config) {
        String catalog = config.icebergCatalogName();
        log.info("Building SparkSession app={} master={} warehouse={}",
                config.sparkAppName(), config.sparkMaster(), config.sparkWarehousePath());

        SparkSession spark = SparkSession.builder()
                .appName(config.sparkAppName())
                .master(config.sparkMaster())
                .config("spark.sql.extensions",
                        "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
                .config("spark.sql.catalog." + catalog,
                        "org.apache.iceberg.spark.SparkCatalog")
                .config("spark.sql.catalog." + catalog + ".type", "hadoop")
                .config("spark.sql.catalog." + catalog + ".warehouse", config.sparkWarehousePath())
                .config("spark.sql.shuffle.partitions", "2")
                .config("spark.ui.enabled", "false")
                .config("spark.driver.bindAddress", "127.0.0.1")
                .config("spark.driver.host", "127.0.0.1")
                .getOrCreate();

        // spark.sparkContext().setLogLevel("INFO");
        log.info("SparkSession ready version={}", spark.version());
        return spark;
    }
}

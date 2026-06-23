package com.cloudpid.ai.spark;

import com.cloudpid.ai.config.AppConfig;
import org.aeonbits.owner.ConfigFactory;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SparkFactoryTest {

    private SparkSession spark;
    private AppConfig config;

    @BeforeAll
    void setUp() {
        config = ConfigFactory.create(AppConfig.class, System.getProperties());
        spark = SparkFactory.create(config);
    }

    @AfterAll
    void tearDown() {
        if (spark != null) {
            spark.stop();
        }
    }

    @Test
    void sparkSession_isNotNull() {
        assertNotNull(spark);
    }

    @Test
    void sparkSession_appNameMatchesConfig() {
        assertEquals(config.sparkAppName(), spark.conf().get("spark.app.name"));
    }

    @Test
    void sparkSession_uiIsDisabled() {
        assertEquals("false", spark.conf().get("spark.ui.enabled"));
    }

    @Test
    void icebergExtension_isRegistered() {
        String extensions = spark.conf().get("spark.sql.extensions");
        assertTrue(extensions.contains("IcebergSparkSessionExtensions"),
            "Expected IcebergSparkSessionExtensions in spark.sql.extensions, got: " + extensions);
    }

    @Test
    void icebergCatalog_typeIsHadoop() {
        String catalog = config.icebergCatalogName();
        String type = spark.conf().get("spark.sql.catalog." + catalog + ".type");
        assertEquals("hadoop", type);
    }

    @Test
    void icebergCatalog_classIsSparkCatalog() {
        String catalog = config.icebergCatalogName();
        String catalogClass = spark.conf().get("spark.sql.catalog." + catalog);
        assertEquals("org.apache.iceberg.spark.SparkCatalog", catalogClass);
    }

    @Test
    void icebergCatalog_warehouseMatchesConfig() {
        String catalog = config.icebergCatalogName();
        String warehouse = spark.conf().get("spark.sql.catalog." + catalog + ".warehouse");
        assertEquals(config.sparkWarehousePath(), warehouse);
    }

    @Test
    void spark_canRunSql() {
        Dataset<Row> df = spark.sql("SELECT 1 AS id, 'hello' AS name");
        assertEquals(1, df.count());
        assertArrayEquals(new String[]{"id", "name"}, df.columns());
    }

    @Test
    void spark_canCreateDataset() {
        Dataset<Row> df = spark.range(10).toDF("id");
        assertEquals(10, df.count());
        assertEquals("id", df.columns()[0]);
    }

    @Test
    void iceberg_canCreateNamespace() {
        String catalog = config.icebergCatalogName();
        String ns = config.icebergNamespace();
        assertDoesNotThrow(() ->
            spark.sql("CREATE NAMESPACE IF NOT EXISTS " + catalog + "." + ns)
        );
    }
}

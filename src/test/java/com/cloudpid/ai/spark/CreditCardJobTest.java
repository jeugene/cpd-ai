package com.cloudpid.ai.spark;

import com.cloudpid.ai.config.AppConfig;
import org.aeonbits.owner.ConfigFactory;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.*;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CreditCardJobTest {

    private static final Path TEST_DATA = classpathResource("/credit_cards.json");

    private static Path classpathResource(String name) {
        try {
            return Path.of(CreditCardJobTest.class.getResource(name).toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
    private static final String TABLE = "credit_cards";
    private static final Set<String> VALID_STATUSES = Set.of("ACTIVE", "INACTIVE", "BLOCKED", "EXPIRED");

    private SparkSession spark;
    private CreditCardJob job;

    @BeforeAll
    void setUp() {
        AppConfig config = ConfigFactory.create(AppConfig.class, System.getProperties());
        spark = SparkFactory.create(config);
        job = new CreditCardJob(spark, config.icebergCatalogName(), config.icebergNamespace());
    }

    @AfterAll
    void tearDown() {
        if (spark != null) spark.stop();
    }

    // ── Schema ──────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void schema_hasExpectedFieldCount() {
        assertEquals(21, CreditCardJob.SCHEMA.fields().length);
    }

    @Test
    @Order(2)
    void schema_containsRequiredFields() {
        Set<String> fields = Set.of(CreditCardJob.SCHEMA.fieldNames());
        assertTrue(fields.containsAll(Set.of(
            "card_id", "cardholder_name", "credit_limit",
            "current_balance", "card_status", "currency"
        )));
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void readJson_loadsAllRecords() {
        Dataset<Row> df = job.readJson(TEST_DATA);
        assertTrue(df.count() >= 5);
    }

    @Test
    @Order(4)
    void readJson_appliesSchema() {
        Dataset<Row> df = job.readJson(TEST_DATA);
        Set<String> columns = Set.of(df.columns());
        assertTrue(columns.contains("card_id"));
        assertTrue(columns.contains("credit_limit"));
    }

    // ── Upsert ───────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void upsert_createsTableOnFirstRun() {
        Dataset<Row> df = job.readJson(TEST_DATA);
        long count = job.upsert(df, TABLE);
        assertTrue(count >= 5);
    }

    @Test
    @Order(6)
    void upsert_isMergeIdempotent() {
        Dataset<Row> df = job.readJson(TEST_DATA);
        long first  = job.upsert(df, TABLE);
        long second = job.upsert(df, TABLE);
        assertEquals(first, second, "Merging same data twice should not change row count");
    }

    // ── Data integrity ───────────────────────────────────────────────────────

    @Test
    @Order(7)
    void data_cardStatusValuesAreValid() {
        Dataset<Row> result = spark.table("local.cpd." + TABLE);
        Set<String> statuses = result.select("card_status")
            .collectAsList()
            .stream()
            .map(r -> r.getString(0))
            .collect(Collectors.toSet());
        assertTrue(VALID_STATUSES.containsAll(statuses),
            "Unexpected statuses: " + statuses);
    }

    @Test
    @Order(8)
    void data_creditLimitIsPositive() {
        Dataset<Row> result = spark.table("local.cpd." + TABLE);
        long invalid = result.filter("credit_limit <= 0").count();
        assertEquals(0, invalid, "All credit limits must be positive");
    }

    @Test
    @Order(9)
    void data_cardIdsAreUnique() {
        Dataset<Row> result = spark.table("local.cpd." + TABLE);
        long total    = result.count();
        long distinct = result.select("card_id").distinct().count();
        assertEquals(total, distinct, "card_id must be unique");
    }

    // ── Validation ───────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void upsert_rejectsInvalidTableName() {
        Dataset<Row> df = job.readJson(TEST_DATA);
        assertThrows(IllegalArgumentException.class,
            () -> job.upsert(df, "bad-name!"));
    }

    @Test
    @Order(11)
    void constructor_rejectsInvalidCatalog() {
        assertThrows(IllegalArgumentException.class,
            () -> new CreditCardJob(spark, "bad catalog", "ns"));
    }
}

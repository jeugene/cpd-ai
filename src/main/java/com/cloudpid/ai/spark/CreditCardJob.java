package com.cloudpid.ai.spark;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.regex.Pattern;

public final class CreditCardJob {

    private static final Logger log = LoggerFactory.getLogger(CreditCardJob.class);

    private static final Pattern IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    public static final StructType SCHEMA = DataTypes.createStructType(new org.apache.spark.sql.types.StructField[]{
        DataTypes.createStructField("card_id",               DataTypes.StringType,  true),
        DataTypes.createStructField("card_number_masked",    DataTypes.StringType,  true),
        DataTypes.createStructField("cardholder_name",       DataTypes.StringType,  true),
        DataTypes.createStructField("account_id",            DataTypes.StringType,  true),
        DataTypes.createStructField("credit_limit",          DataTypes.DoubleType,  true),
        DataTypes.createStructField("current_balance",       DataTypes.DoubleType,  true),
        DataTypes.createStructField("available_credit",      DataTypes.DoubleType,  true),
        DataTypes.createStructField("card_status",           DataTypes.StringType,  true),
        DataTypes.createStructField("card_type",             DataTypes.StringType,  true),
        DataTypes.createStructField("issue_date",            DataTypes.StringType,  true),
        DataTypes.createStructField("expiry_date",           DataTypes.StringType,  true),
        DataTypes.createStructField("billing_cycle_day",     DataTypes.IntegerType, true),
        DataTypes.createStructField("minimum_payment",       DataTypes.DoubleType,  true),
        DataTypes.createStructField("annual_fee",            DataTypes.DoubleType,  true),
        DataTypes.createStructField("interest_rate",         DataTypes.DoubleType,  true),
        DataTypes.createStructField("last_transaction_date", DataTypes.StringType,  true),
        DataTypes.createStructField("last_payment_date",     DataTypes.StringType,  true),
        DataTypes.createStructField("last_payment_amount",   DataTypes.DoubleType,  true),
        DataTypes.createStructField("reward_points",         DataTypes.LongType,    true),
        DataTypes.createStructField("is_primary",            DataTypes.BooleanType, true),
        DataTypes.createStructField("currency",              DataTypes.StringType,  true),
    });

    private final SparkSession spark;
    private final String catalog;
    private final String namespace;

    public CreditCardJob(SparkSession spark, String catalog, String namespace) {
        validateIdentifier(catalog);
        validateIdentifier(namespace);
        this.spark = spark;
        this.catalog = catalog;
        this.namespace = namespace;
    }

    /**
     * Reads credit card records from a multiline JSON file.
     */
    public Dataset<Row> readJson(Path path) {
        log.info("Reading credit card JSON from {}", path);
        Dataset<Row> df = spark.read()
            .schema(SCHEMA)
            .option("multiline", true)
            .json(path.toString());
        log.info("Loaded {} records", df.count());
        return df;
    }

    /**
     * Upserts records into an Iceberg table using MERGE ON card_id.
     * Creates the table on first run; merges on subsequent runs.
     *
     * @return row count of the target table after the operation
     */
    public long upsert(Dataset<Row> df, String table) {
        validateIdentifier(table);
        String qualified = qualified(table);

        spark.sql("CREATE NAMESPACE IF NOT EXISTS " + catalog + "." + namespace);

        if (!spark.catalog().tableExists(qualified)) {
            log.info("Creating Iceberg table {}", qualified);
            try {
                df.writeTo(qualified).create();
            } catch (org.apache.spark.sql.catalyst.analysis.TableAlreadyExistsException e) {
                log.warn("Table {} already exists (concurrent creation), merging instead", qualified);
                mergeInto(df, qualified);
            }
        } else {
            log.info("Merging into Iceberg table {}", qualified);
            mergeInto(df, qualified);
        }

        long count = spark.table(qualified).count();
        log.info("Table {} contains {} records", qualified, count);
        return count;
    }

    private void mergeInto(Dataset<Row> df, String qualified) {
        df.createOrReplaceTempView("cc_upsert_source");
        spark.sql("""
            MERGE INTO %s AS target
            USING cc_upsert_source AS source
            ON target.card_id = source.card_id
            WHEN MATCHED THEN UPDATE SET *
            WHEN NOT MATCHED THEN INSERT *
            """.formatted(qualified));
    }

    private String qualified(String table) {
        return catalog + "." + namespace + "." + table;
    }

    private static void validateIdentifier(String id) {
        if (id == null || !IDENTIFIER.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: '" + id + "'");
        }
    }
}

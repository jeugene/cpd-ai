package com.cloudpid.ai.aws;

import com.cloudpid.ai.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.GetJobRunRequest;
import software.amazon.awssdk.services.glue.model.GetTablesRequest;
import software.amazon.awssdk.services.glue.model.StartCrawlerRequest;
import software.amazon.awssdk.services.glue.model.Table;

import java.util.List;

public class GlueService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GlueService.class);

    private final GlueClient client;
    private final String database;

    public GlueService(AppConfig config) {
        this.client = GlueClient.builder()
            .region(Region.of(config.awsRegion()))
            .build();
        this.database = config.glueDatabaseName();
        log.info("GlueService initialized database={}", database);
    }

    public List<Table> getTables() {
        log.debug("Glue getTables database={}", database);
        return client.getTables(
            GetTablesRequest.builder().databaseName(database).build()
        ).tableList();
    }

    public void startCrawler(String crawlerName) {
        log.info("Glue startCrawler name={}", crawlerName);
        client.startCrawler(
            StartCrawlerRequest.builder().name(crawlerName).build()
        );
    }

    public String getJobRunState(String jobName, String runId) {
        log.debug("Glue getJobRun name={} runId={}", jobName, runId);
        return client.getJobRun(
            GetJobRunRequest.builder().jobName(jobName).runId(runId).build()
        ).jobRun().jobRunStateAsString();
    }

    @Override
    public void close() {
        client.close();
    }
}

package com.cloudpid.ai.aws;

import com.cloudpid.ai.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;

public class S3Service implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(S3Service.class);

    private final S3Client client;
    private final String bucket;

    public S3Service(AppConfig config) {
        this.client = S3Client.builder()
            .region(Region.of(config.awsRegion()))
            .build();
        this.bucket = config.s3Bucket();
        log.info("S3Service initialized bucket={} region={}", bucket, config.awsRegion());
    }

    public void put(String key, byte[] data) {
        log.debug("S3 put bucket={} key={} bytes={}", bucket, key, data.length);
        client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).build(),
            RequestBody.fromBytes(data)
        );
    }

    public InputStream get(String key) {
        log.debug("S3 get bucket={} key={}", bucket, key);
        return client.getObject(
            GetObjectRequest.builder().bucket(bucket).key(key).build()
        );
    }

    public void delete(String key) {
        log.debug("S3 delete bucket={} key={}", bucket, key);
        client.deleteObject(
            DeleteObjectRequest.builder().bucket(bucket).key(key).build()
        );
    }

    @Override
    public void close() {
        client.close();
    }
}

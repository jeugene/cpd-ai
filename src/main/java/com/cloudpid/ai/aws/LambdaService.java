package com.cloudpid.ai.aws;

import com.cloudpid.ai.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

public class LambdaService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LambdaService.class);

    private final LambdaClient client;
    private final String functionName;

    public LambdaService(AppConfig config) {
        this.client = LambdaClient.builder()
            .region(Region.of(config.awsRegion()))
            .build();
        this.functionName = config.lambdaFunctionName();
        log.info("LambdaService initialized function={}", functionName);
    }

    public String invoke(String payload) {
        log.debug("Lambda invoke function={} payloadBytes={}", functionName, payload.length());
        InvokeResponse response = client.invoke(
            InvokeRequest.builder()
                .functionName(functionName)
                .payload(SdkBytes.fromUtf8String(payload))
                .build()
        );
        log.debug("Lambda response statusCode={}", response.statusCode());
        return response.payload() == null ? "" : response.payload().asUtf8String();
    }

    @Override
    public void close() {
        client.close();
    }
}

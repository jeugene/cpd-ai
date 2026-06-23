package com.cloudpid.ai.aws;

import com.cloudpid.ai.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.List;

public class SqsService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqsService.class);

    private final SqsClient client;
    private final String queueUrl;

    public SqsService(AppConfig config) {
        this.client = SqsClient.builder()
            .region(Region.of(config.awsRegion()))
            .build();
        this.queueUrl = config.sqsQueueUrl();
        log.info("SqsService initialized queueUrl={}", queueUrl);
    }

    public String send(String body) {
        log.debug("SQS send queueUrl={}", queueUrl);
        String messageId = client.sendMessage(
            SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(body)
                .build()
        ).messageId();
        log.debug("SQS sent messageId={}", messageId);
        return messageId;
    }

    public List<Message> receive(int maxMessages) {
        log.debug("SQS receive queueUrl={} max={}", queueUrl, maxMessages);
        return client.receiveMessage(
            ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(maxMessages)
                .build()
        ).messages();
    }

    public void delete(String receiptHandle) {
        log.debug("SQS delete queueUrl={}", queueUrl);
        client.deleteMessage(
            DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .build()
        );
    }

    @Override
    public void close() {
        client.close();
    }
}

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.s3.model.S3Object;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class EC2WorkerApp {
    private static final String BUCKET_NAME = "bucket-image-resizer-storage";
    private static final String INBOX_QUEUE_URL = "https://sqs.us-east-1.amazonaws.com/533267249214/inbox_sqs_image_resizer.fifo";
    private static final String OUTBOX_QUEUE_URL = "https://sqs.us-east-1.amazonaws.com/533267249214/outbox_sqs_image_resizer.fifo";
    private static final String REGION = "us-east-1";

    private static AmazonS3 s3Client;
    private static AmazonSQS sqsClient;
    private static Set<String> expectedProcessedKeys = new HashSet<>();

    public static void main(String[] args) {
        System.out.println("Initializing AWS clients...");

        // Initialize AWS clients with explicit region
        s3Client = AmazonS3ClientBuilder.standard()
                .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
                .withRegion(REGION)  // explicitly set the region
                .build();

        sqsClient = AmazonSQSClientBuilder.standard()
                .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
                .withRegion(REGION)  // explicitly set the region
                .build();

        System.out.println("AWS clients initialized.");

        Thread inboxThread = new Thread(() -> {
            while (true) {
                processInboxMessages();
                try {
                    System.out.println("Sleeping for 10 seconds...");
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    System.err.println("Sleep interrupted: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        });

        Thread outboxThread = new Thread(() -> {
            while (true) {
                processOutboxMessages();
                try {
                    System.out.println("Sleeping for 10 seconds...");
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    System.err.println("Sleep interrupted: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        });

        inboxThread.start();
        outboxThread.start();
    }

    private static void processInboxMessages() {
        System.out.println("Receiving messages from the inbox queue...");

        // Receive messages from the inbox queue
        List<Message> messages = sqsClient.receiveMessage(INBOX_QUEUE_URL).getMessages();
        System.out.println("Received " + messages.size() + " messages.");

        for (Message message : messages) {
            String s3Key = message.getBody();
            System.out.println("Processing message with S3 key: " + s3Key);

            String localFilePath = "/tmp/" + UUID.randomUUID() + "-" + new File(s3Key).getName();
            String processedFilePath = localFilePath.replace("/tmp/", "/tmp/processed-");

            // Download the image from S3
            System.out.println("Downloading image from S3...");
            try (S3Object s3Object = s3Client.getObject(BUCKET_NAME, s3Key);
                 InputStream s3InputStream = s3Object.getObjectContent()) {

                Files.copy(s3InputStream, new File(localFilePath).toPath(), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Image downloaded to " + localFilePath);
            } catch (IOException e) {
                System.err.println("Failed to download image from S3: " + e.getMessage());
                e.printStackTrace();
                continue;
            }

            // Process the image (e.g., resize)
            System.out.println("Processing image...");
            String command = "convert " + localFilePath + " -resize 50% " + processedFilePath;
            try {
                Process process = Runtime.getRuntime().exec(command);
                process.waitFor();
                System.out.println("Image processed and saved to " + processedFilePath);
            } catch (Exception e) {
                System.err.println("Failed to process image: " + e.getMessage());
                e.printStackTrace();
                continue;
            }

            // Upload the processed image to S3
            System.out.println("Uploading processed image to S3...");
            String processedS3Key = "processed/" + new File(s3Key).getName();
            s3Client.putObject(BUCKET_NAME, processedS3Key, new File(processedFilePath));
            System.out.println("Processed image uploaded with S3 key: " + processedS3Key);

            // Add the original key to the expected set
            expectedProcessedKeys.add(s3Key);

            // Send the processed image key to the outbox queue
            System.out.println("Sending processed image key to the outbox queue...");
            sqsClient.sendMessage(OUTBOX_QUEUE_URL, s3Key);
            System.out.println("Processed image key sent to the outbox queue.");

            // Delete the message from the inbox queue
            System.out.println("Deleting message from the inbox queue...");
            sqsClient.deleteMessage(INBOX_QUEUE_URL, message.getReceiptHandle());
            System.out.println("Message deleted from the inbox queue.");

            // Clean up local files
            System.out.println("Cleaning up local files...");
            new File(localFilePath).delete();
            new File(processedFilePath).delete();
            System.out.println("Local files cleaned up.");
        }
    }

    private static void processOutboxMessages() {
        System.out.println("Polling the outbox queue for messages...");

        // Receive messages from the outbox queue
        List<Message> messages = sqsClient.receiveMessage(OUTBOX_QUEUE_URL).getMessages();
        System.out.println("Received " + messages.size() + " messages.");

        for (Message message : messages) {
            String processedS3Key = message.getBody();
            System.out.println("Received message from outbox queue with key: " + processedS3Key);

            // Check if the processed key matches an expected key
            if (expectedProcessedKeys.contains(processedS3Key)) {
                System.out.println("Processed message matches the sent request key.");

                // Remove the key from the expected set
                expectedProcessedKeys.remove(processedS3Key);

                // Further processing of the matched message can be done here
            } else {
                System.out.println("The processed message does not match the sent request key. Ignoring this message.");
            }

            // Delete the message from the outbox queue
            System.out.println("Deleting message from the outbox queue...");
            sqsClient.deleteMessage(OUTBOX_QUEUE_URL, message.getReceiptHandle());
            System.out.println("Message deleted from the outbox queue.");
        }

        if (expectedProcessedKeys.isEmpty()) {
            System.out.println("No matching processed message found. Continuing to poll...");
        }
    }
}

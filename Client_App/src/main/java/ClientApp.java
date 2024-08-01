import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;

import java.io.File;
import java.util.UUID;

public class ClientApp {

    private static final String BUCKET_NAME = "bucket-image-resizer-storage";
    private static final String INBOX_QUEUE_URL = "https://sqs.us-east-1.amazonaws.com/533267249214/inbox_sqs_image_resizer.fifo";
    private static final String OUTBOX_QUEUE_URL = "https://sqs.us-east-1.amazonaws.com/533267249214/outbox_sqs_image_resizer.fifo";
    private static final String LOCAL_UPLOAD_PATH = "src/main/resources/white_image.jpg"; // Change this to your upload image path
    private static final String LOCAL_DOWNLOAD_PATH = "/src/main/resources/compressed_white_image.jpg"; // Change this to your download image path

    private final AmazonS3 s3Client;
    private final AmazonSQS sqsClient;

    public ClientApp() {
        try {
            System.out.println("Initializing AWS S3 and SQS clients...");
            s3Client = AmazonS3ClientBuilder.standard()
                    .withCredentials(new DefaultAWSCredentialsProviderChain())
                    .withRegion("us-east-1")
                    .build();

            sqsClient = AmazonSQSClientBuilder.standard()
                    .withCredentials(new DefaultAWSCredentialsProviderChain())
                    .withRegion("us-east-1")
                    .build();
            System.out.println("AWS clients initialized successfully.");
        } catch (Exception e) {
            System.err.println("Error initializing AWS clients: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    public void uploadImageAndProcess() {
        String imageKey = null;
        try {
            // Generate a unique key for the image
            imageKey = "uploads/" + UUID.randomUUID() + ".jpg";
            System.out.println("Generated unique key for the image: " + imageKey);

            // Upload the image to S3
            System.out.println("Uploading image to S3...");
            s3Client.putObject(new PutObjectRequest(BUCKET_NAME, imageKey, new File(LOCAL_UPLOAD_PATH)));
            System.out.println("Image uploaded to S3 with key: " + imageKey);
        } catch (Exception e) {
            System.err.println("Error uploading image to S3: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        try {
            // Place the key to the uploaded image in the inbox queue
            System.out.println("Sending image key to the inbox queue...");
            sqsClient.sendMessage(new SendMessageRequest(INBOX_QUEUE_URL, imageKey));
            System.out.println("Image key sent to inbox queue.");
        } catch (Exception e) {
            System.err.println("Error sending image key to inbox queue: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        try {
            // Wait for the message to be processed and placed in the outbox queue
            System.out.println("Waiting for the message to be processed...");
            boolean messageProcessed = false;
            while (!messageProcessed) {
                System.out.println("Polling the outbox queue for messages...");
                ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(OUTBOX_QUEUE_URL)
                        .withMaxNumberOfMessages(1)
                        .withWaitTimeSeconds(10); // Long polling

                for (Message message : sqsClient.receiveMessage(receiveMessageRequest).getMessages()) {
                    String processedKey = message.getBody();
                    System.out.println("Received message from outbox queue with key: " + processedKey);

                    // Check if the processed message is the response to the request we sent
                    if (processedKey.contains(imageKey)) {
                        try {
                            System.out.println("The processed message matches the sent request key. Downloading resized image from S3...");

                            // Download the resized image from S3
                            s3Client.getObject(new GetObjectRequest(BUCKET_NAME, processedKey), new File(LOCAL_DOWNLOAD_PATH));
                            System.out.println("Resized image downloaded from S3 with key: " + processedKey);

                            // Delete the message from the outbox queue
                            System.out.println("Deleting the processed message from the outbox queue...");
                            sqsClient.deleteMessage(OUTBOX_QUEUE_URL, message.getReceiptHandle());
                            System.out.println("Processed message deleted from the outbox queue.");

                            messageProcessed = true;
                            break;
                        } catch (Exception e) {
                            System.err.println("Error downloading resized image from S3: " + e.getMessage());
                            e.printStackTrace();
                            return;
                        }
                    } else {
                        System.out.println("The processed message does not match the sent request key. Ignoring this message.");
                    }
                }

                if (!messageProcessed) {
                    System.out.println("No matching processed message found. Continuing to poll...");
                }
            }
            System.out.println("Image processing completed successfully.");
        } catch (Exception e) {
            System.err.println("Error processing messages from outbox queue: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        try {
            System.out.println("Starting ClientApp...");
            ClientApp clientApp = new ClientApp();
            clientApp.uploadImageAndProcess();
            System.out.println("ClientApp finished execution.");
        } catch (Exception e) {
            System.err.println("Error running ClientApp: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

Project Overview: Image Processing System
Objective: Create an image processing system leveraging AWS services for handling image uploads, processing, and distribution.

1. Components:

1.1 Amazon S3 (Simple Storage Service)

Stores original images and processed images.
Bucket Name: bucket-image-resizer-storage
1.2 Amazon SQS (Simple Queue Service - FIFO)

Manages and coordinates messages for processing.
Queue URLs:
Inbox Queue: https://sqs.us-east-1.amazonaws.com/533267249214/inbox_sqs_image_resizer.fifo
Receives messages containing S3 keys of images to be processed.
Outbox Queue: https://sqs.us-east-1.amazonaws.com/533267249214/outbox_sqs_image_resizer.fifo
Receives messages indicating processed images ready for further actions.
1.3 Amazon EC2 (Elastic Compute Cloud)

Instance Type: t2.medium (or suitable based on requirements)
Hosts the application handling image processing tasks.
Operating System: Amazon Linux 2 (or another suitable Linux distribution)
2. Workflow:

2.1 Image Upload and Processing

Image Upload:
Images are uploaded to the bucket-image-resizer-storage bucket in S3.
S3 event triggers a message in the SQS inbox queue.
Message Handling:
EC2 instance polls the inbox_sqs_image_resizer.fifo queue.
Retrieves the S3 key from each message.
Image Processing:
EC2 instance downloads the image from S3 using the provided key.
Processes the image (e.g., resizing).
Uploads the processed image to S3 in the processed/ directory.
Post-Processing:
EC2 instance sends a message to the outbox_sqs_image_resizer.fifo queue with the S3 key of the processed image.
2.2 Monitoring and Maintenance

EC2 instances continuously poll the SQS queues for messages.
Errors in processing or messaging are logged.
3. Configuration:

3.1 IAM Roles and Policies:

EC2 IAM Role:
Permissions:
s3:* for access to the S3 bucket.
sqs:* for access to the SQS queues.
Policies:
Ensure EC2 instance can read from and write to S3 and SQS.
3.2 Security Settings:

Bucket Policy: Authorizes access to the S3 bucket.
Queue Policy: Authorizes access to SQS queues.
3.3 Environment Configuration:

AWS Region: us-east-1
Local Storage: Temporary paths for downloaded and processed images (e.g., /tmp/).


package org.uwrf.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.uwrf.services.BedrockQuizGenerator;
import org.uwrf.services.MockQuizGenerator;
import org.uwrf.services.QuizGenerator;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.transcribe.TranscribeClient;
import software.amazon.awssdk.services.transcribe.model.GetTranscriptionJobRequest;
import software.amazon.awssdk.services.transcribe.model.Media;
import software.amazon.awssdk.services.transcribe.model.MediaFormat;
import software.amazon.awssdk.services.transcribe.model.StartTranscriptionJobRequest;
import software.amazon.awssdk.services.transcribe.model.TranscriptionJobStatus;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

public class VideoHandler implements RequestHandler<S3Event, String> {

    private final QuizGenerator quizGenerator;
    private final S3Client s3Client;
    private final TranscribeClient transcribeClient;
    private final ObjectMapper objectMapper;

    public VideoHandler() {
        this("true".equalsIgnoreCase(System.getenv("MOCK_BEDROCK"))
                ? new MockQuizGenerator()
                : new BedrockQuizGenerator());
    }

    VideoHandler(QuizGenerator quizGenerator) {
        this.quizGenerator = quizGenerator;
        this.s3Client = S3Client.create();
        this.transcribeClient = TranscribeClient.create();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String handleRequest(S3Event s3Event, Context context) {
        System.out.println("=== Lambda Function Triggered ===");
        System.out.println("Received S3 event with " + s3Event.getRecords().size() + " record(s)");

        for (S3EventNotification.S3EventNotificationRecord record : s3Event.getRecords()) {
            try {
                String bucketName = record.getS3().getBucket().getName();
                String objectKey = URLDecoder.decode(
                        record.getS3().getObject().getKey(),
                        StandardCharsets.UTF_8
                );

                long objectSize = record.getS3().getObject().getSizeAsLong();

                System.out.println("--- S3 Event Details ---");
                System.out.println("Bucket: " + bucketName);
                System.out.println("File: " + objectKey);
                System.out.println("Size: " + objectSize + " bytes");
                System.out.println("------------------------");

                String jobName = "quiz-job-" + UUID.randomUUID();
                String transcriptKey = "transcripts/" + jobName + ".json";

                String transcript;

                boolean mockTranscribe = "true".equalsIgnoreCase(
                        System.getenv().getOrDefault("MOCK_TRANSCRIBE", "true")
                );

                if (mockTranscribe) {
                    System.out.println("MOCK_TRANSCRIBE=true, using fake transcript for local testing.");
                    transcript = "This is a sample lecture transcript about AWS Lambda, S3, Transcribe, and Bedrock.";
                } else {
                    startTranscriptionJob(bucketName, objectKey, jobName, transcriptKey);
                    waitForTranscription(jobName);
                    transcript = readTranscriptFromS3(bucketName, transcriptKey);
                }

                String quizQuestionsJson = quizGenerator.generateQuiz(transcript);

                String finalQuizJson = """
                        {
                          "sourceVideo": "%s",
                          "generatedAt": "%s",
                          "questions": %s
                        }
                        """.formatted(objectKey, Instant.now(), quizQuestionsJson);

                String quizKey = buildQuizOutputKey(objectKey);

                boolean mockS3 = "true".equalsIgnoreCase(
                        System.getenv().getOrDefault("MOCK_S3", "true")
                );

                if (mockS3) {
                    System.out.println("MOCK_S3=true, skipping real S3 upload.");
                    System.out.println("Would save quiz to: s3://" + bucketName + "/" + quizKey);
                    System.out.println(finalQuizJson);
                } else {
                    saveQuizToS3(bucketName, quizKey, finalQuizJson);
                }

                System.out.println("Quiz saved to: " + quizKey);

            } catch (Exception e) {
                System.err.println("Error processing video: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }

        return "Processed " + s3Event.getRecords().size() + " record(s)";
    }

    private void startTranscriptionJob(String bucketName, String objectKey, String jobName, String transcriptKey) {
        String mediaUri = "s3://" + bucketName + "/" + objectKey;

        StartTranscriptionJobRequest request = StartTranscriptionJobRequest.builder()
                .transcriptionJobName(jobName)
                .languageCode("en-US")
                .mediaFormat(MediaFormat.MP4)
                .media(Media.builder()
                        .mediaFileUri(mediaUri)
                        .build())
                .outputBucketName(bucketName)
                .outputKey(transcriptKey)
                .build();

        transcribeClient.startTranscriptionJob(request);

        System.out.println("Started Transcribe job: " + jobName);
    }

    private void waitForTranscription(String jobName) throws InterruptedException {
        while (true) {
            GetTranscriptionJobRequest request = GetTranscriptionJobRequest.builder()
                    .transcriptionJobName(jobName)
                    .build();

            TranscriptionJobStatus status = transcribeClient
                    .getTranscriptionJob(request)
                    .transcriptionJob()
                    .transcriptionJobStatus();

            System.out.println("Transcription status: " + status);

            if (status == TranscriptionJobStatus.COMPLETED) {
                return;
            }

            if (status == TranscriptionJobStatus.FAILED) {
                throw new RuntimeException("Transcription job failed: " + jobName);
            }

            Thread.sleep(10000);
        }
    }

    private String readTranscriptFromS3(String bucketName, String transcriptKey) throws Exception {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(transcriptKey)
                .build();

        String transcriptJson = new String(
                s3Client.getObject(request).readAllBytes(),
                StandardCharsets.UTF_8
        );

        JsonNode transcriptNode = objectMapper.readTree(transcriptJson);

        return transcriptNode
                .get("results")
                .get("transcripts")
                .get(0)
                .get("transcript")
                .asText();
    }

    private void saveQuizToS3(String bucketName, String quizKey, String quizJson) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(quizKey)
                .contentType("application/json")
                .build();

        s3Client.putObject(request, RequestBody.fromString(quizJson));

        System.out.println("Saved quiz JSON to S3");
    }

    private String buildQuizOutputKey(String objectKey) {
        String fileName = objectKey.substring(objectKey.lastIndexOf("/") + 1);
        String baseName = fileName.replace(".mp4", "");
        return "quizzes/" + baseName + "-quiz.json";
    }
}
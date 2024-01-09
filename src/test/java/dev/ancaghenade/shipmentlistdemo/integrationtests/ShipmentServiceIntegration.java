package dev.ancaghenade.shipmentlistdemo.integrationtests;

import dev.ancaghenade.shipmentlistdemo.entity.Shipment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
public class ShipmentServiceIntegration {

    @Container
    protected static LocalStackContainer localStack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack-pro:3.0.1"))
                    .withEnv("LOCALSTACK_AUTH_TOKEN", System.getenv("LOCALSTACK_AUTH_TOKEN"))
                    .withFileSystemBind("src/test/java/resources/shipment-list-demo-cloud-pod",
                            "/etc/localstack/init-pods.d/shipment-list-demo-cloud-pod")
                    .withEnv("DEBUG", "1")
                    .waitingFor(Wait.forLogMessage(".*Loaded services from local state file.*\\n", 1));


    private static final Logger LOGGER = LoggerFactory.getLogger(ShipmentServiceIntegration.class);
    protected static Slf4jLogConsumer logConsumer = new Slf4jLogConsumer(LOGGER);
    protected TestRestTemplate restTemplate = new TestRestTemplate();

    protected static final String BUCKET_NAME = "shipment-picture-bucket-lenient-crab";
    protected static String BASE_URL = "http://localhost:8081";
    protected static ObjectMapper objectMapper = new ObjectMapper();
    protected static URI localStackEndpoint;

    @BeforeAll()
    protected static void setupConfig() {
        localStackEndpoint = localStack.getEndpoint();
    }

    @DynamicPropertySource
    static void overrideConfigs(DynamicPropertyRegistry registry) {
        registry.add("aws.s3.endpoint",
                () -> localStackEndpoint);
        registry.add(
                "aws.dynamodb.endpoint", () -> localStackEndpoint);
        registry.add(
                "aws.sqs.endpoint", () -> localStackEndpoint);
        registry.add(
                "aws.sns.endpoint", () -> localStackEndpoint);
        registry.add("aws.credentials.secret-key", localStack::getSecretKey);
        registry.add("aws.credentials.access-key", localStack::getAccessKey);
        registry.add("aws.region", localStack::getRegion);
        registry.add("shipment-picture-bucket", () -> BUCKET_NAME);
    }


    @Test
    @Order(1)
    void testFileUploadToS3() throws Exception {
        // Prepare the file to upload
        var imageData = new byte[0];
        try {
            imageData = Files.readAllBytes(Path.of("src/test/java/resources/cat.jpg"));
        } catch (IOException e) {
            e.printStackTrace();
        }
        var resource = new ByteArrayResource(imageData) {
            @Override
            public String getFilename() {
                return "cat.jpg";
            }
        };
        var shipmentId = "dc3b6668-45ba-4c10-9860-95bbffaebfc1";
        // build the URL with the id as a path variable
        var url = "/api/shipment/" + shipmentId + "/image/upload";
        // set the request headers
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        // request body with the file resource and headers
        MultiValueMap<String, Object> requestBody = new LinkedMultiValueMap<>();
        requestBody.add("file", resource);
        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(requestBody,
                headers);

        ResponseEntity<String> responseEntity = restTemplate.exchange(BASE_URL + url,
                HttpMethod.POST, requestEntity, String.class);

        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        var execResult = executeInContainer(
                "awslocal s3api list-objects --bucket shipment-picture-bucket-lenient-crab --query length(Contents[])");
        assertEquals(String.valueOf(1), execResult.getStdout().trim());
    }

    @Test
    @Order(2)
    void testFileDownloadFromS3() {

        var shipmentId = "dc3b6668-45ba-4c10-9860-95bbffaebfc1";
        // build the URL with the id as a path variable
        var url = "/api/shipment/" + shipmentId + "/image/download";

        ResponseEntity<byte[]> responseEntity = restTemplate.exchange(BASE_URL + url,
                HttpMethod.GET, null, byte[].class);

        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        // object is not empty
        assertNotNull(responseEntity.getBody());
    }

    @Test
    @Order(3)
    void testFileDownloadFromS3FailsOnWrongId() {

        var shipmentId = "3317ac4f-1f9b-4bab-a974-4aa987wrong";
        // build the URL with the id as a path variable
        var url = "/api/shipment/" + shipmentId + "/image/download";
        ResponseEntity<byte[]> responseEntity = restTemplate.exchange(BASE_URL + url,
                HttpMethod.GET, null, byte[].class);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, responseEntity.getStatusCode());
    }

    @Test
    @Order(4)
    void testGetShipmentFromDynamoDB() throws IOException {

        var url = "/api/shipment";
        // set the request headers
        ResponseEntity<List<Shipment>> responseEntity = restTemplate.exchange(BASE_URL + url,
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {
                });

        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        assertNotNull(responseEntity.getBody());

        if (responseEntity.getStatusCode().is2xxSuccessful()) {
            var json = new File("src/test/java/resources/shipment.json");
            var shipment = objectMapper.readValue(json, Shipment.class);
            List<Shipment> shipmentList = responseEntity.getBody();
            var shipmentWithoutLink = shipmentList.get(0);
            shipmentWithoutLink.setImageLink(null);
            assertEquals(shipment.getShipmentId(), shipmentWithoutLink.getShipmentId());
        }
    }

    @Test
    @Order(5)
    void testAddShipmentToDynamoDB() throws IOException {

        var url = "/api/shipment";
        // set the request headers

        var json = new File("src/test/java/resources/shipmentToUpload.json");
        var shipment = objectMapper.readValue(json, Shipment.class);

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf(MediaType.APPLICATION_JSON_VALUE));

        HttpEntity<Shipment> requestEntity = new HttpEntity<>(shipment,
                headers);

        ResponseEntity<String> responseEntity = restTemplate.exchange(BASE_URL + url,
                HttpMethod.POST, requestEntity, String.class);

        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());

    }

    @Test
    @Order(6)
    void testGetSeveralShipmentsFromDynamoDB() {

        var url = "/api/shipment";
        // set the request headers
        ResponseEntity<List<Shipment>> responseEntity = restTemplate.exchange(BASE_URL + url,
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {
                });

        if (responseEntity.getStatusCode().is2xxSuccessful()) {
            List<Shipment> shipmentList = responseEntity.getBody();
            assertEquals(5, shipmentList.size());
        }
    }

    @Test
    @Order(7)
    void testDeleteShipmentFromDynamoDB() {

        var url = "/api/shipment";
        var shipmentId = "/3317ac4f-1f9b-4bab-a974-4aa9876d5547";

        // set the request headers
        ResponseEntity<String> deleteResponseEntity = restTemplate.exchange(BASE_URL + url + shipmentId,
                HttpMethod.DELETE, null, String.class);

        assertEquals(HttpStatus.OK, deleteResponseEntity.getStatusCode());
        assertEquals("Shipment has been deleted", deleteResponseEntity.getBody());

        ResponseEntity<List<Shipment>> getResponseEntity = restTemplate.exchange(BASE_URL + url,
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {
                });

        if (getResponseEntity.getStatusCode().is2xxSuccessful()) {
            List<Shipment> shipmentList = getResponseEntity.getBody();
            assertEquals(5, shipmentList.size());
        }
    }

    protected static org.testcontainers.containers.Container.ExecResult executeInContainer(String command) throws Exception {

        final var execResult = localStack.execInContainer(formatCommand(command));
        // assertEquals(0, execResult.getExitCode());

        final var logs = execResult.getStdout() + execResult.getStderr();
        LOGGER.info(logs);
        LOGGER.error(execResult.getExitCode() != 0 ? execResult + " - DOES NOT WORK" : "");
        return execResult;
    }

    private static String[] formatCommand(String command) {
        return command.split(" ");
    }
}


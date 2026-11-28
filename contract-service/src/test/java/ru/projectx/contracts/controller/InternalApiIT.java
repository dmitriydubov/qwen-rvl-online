package ru.projectx.contracts.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.projectx.contracts.service.RequestIds;
import ru.projectx.contracts.support.IntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class InternalApiIT {

    private static final String BOUNDARY = "contracts-test-boundary";
    private static final String TRACE = "a41f8c02-7d3b-4e19-9a55-2c7e0b6f1d84";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int ELEVEN_MEGABYTES = 11 * 1024 * 1024;
    private static final int FIFTEEN_MEGABYTES = 15 * 1024 * 1024;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @ParameterizedTest
    @ValueSource(ints = {ELEVEN_MEGABYTES, FIFTEEN_MEGABYTES})
    void fileLargerThanTheContainerAccepts_isValidationErrorOfTheFilePart(int size)
            throws IOException, InterruptedException {
        HttpResponse<String> response = upload(UUID.randomUUID(), new byte[size]);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue(RequestIds.HEADER)).contains(TRACE);
        JsonNode error = json.readTree(response.body()).path("error");
        assertThat(error.path("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.path("details").path(0).path("field").asString()).isEqualTo("file");
        assertThat(error.path("request_id").asString()).isEqualTo(TRACE);
    }

    @Test
    void unknownRequestOnPackageEndpoint_isAnsweredInTheCommonErrorFormatInSnakeCase()
            throws IOException, InterruptedException {
        URI uri = URI.create("http://localhost:" + port + "/internal/v1/requests/" + UUID.randomUUID() + "/package");
        HttpRequest call = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .header(RequestIds.HEADER, TRACE)
                .GET()
                .build();
        HttpResponse<String> response = http.send(call, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(404);
        JsonNode error = json.readTree(response.body()).path("error");
        assertThat(error.propertyNames()).containsExactlyInAnyOrder("code", "message", "details", "request_id");
        assertThat(error.path("code").asString()).isEqualTo("REQUEST_NOT_FOUND");
        assertThat(error.path("request_id").asString()).isEqualTo(TRACE);
    }

    @ParameterizedTest
    @CsvSource({
        "'; filename=\"id.txt\"', '; filename=\"certificate.jpg\"', attachment_id",
        "'; filename=\"\"', '; filename=\"certificate.jpg\"', attachment_id",
        "'', '', file",
        "'', '; filename=\"\"', file",
        "'', '; filename=\"   \"', file"})
    void malformedMultipartHeadersOnRealTomcat_areValidationErrorsWithoutInternalError(
            String attachmentFilename, String fileFilename, String expectedField)
            throws IOException, InterruptedException {
        byte[] raw = customBody(attachmentFilename, fileFilename, "СТЕНД".getBytes(StandardCharsets.UTF_8));
        URI uri = URI.create("http://localhost:" + port + "/internal/v1/requests/" + UUID.randomUUID()
                + "/participants/6778e8c2-985a-4d54-9bd3-0f6403b104d6/attachments");
        HttpRequest call = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header(RequestIds.HEADER, TRACE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(raw))
                .build();

        HttpResponse<String> response = http.send(call, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode error = json.readTree(response.body()).path("error");
        assertThat(error.path("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.path("details").size()).isEqualTo(1);
        assertThat(error.path("details").path(0).path("field").asString()).isEqualTo(expectedField);
        assertThat(error.path("request_id").asString()).isEqualTo(TRACE);
    }

    private HttpResponse<String> upload(UUID request, byte[] content) throws IOException, InterruptedException {
        URI uri = URI.create("http://localhost:" + port + "/internal/v1/requests/" + request
                + "/participants/6778e8c2-985a-4d54-9bd3-0f6403b104d6/attachments");
        HttpRequest call = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header(RequestIds.HEADER, TRACE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(
                        customBody("", "; filename=\"certificate.jpg\"", content)))
                .build();
        return http.send(call, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static byte[] customBody(String attachmentFilename, String fileFilename, byte[] content) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        write(body, "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"attachment_id\""
                + attachmentFilename + "\r\n\r\n3c2f8e10-5a7b-4d21-9f6e-1b0a7c4d8e21\r\n--" + BOUNDARY
                + "\r\nContent-Disposition: form-data; name=\"file\"" + fileFilename
                + "\r\nContent-Type: image/jpeg\r\n\r\n");
        body.writeBytes(content);
        write(body, "\r\n--" + BOUNDARY + "--\r\n");
        return body.toByteArray();
    }

    private static void write(ByteArrayOutputStream body, String text) {
        body.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}

package ru.projectx.contracts.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import ru.projectx.contracts.dto.AttachmentResponse;
import ru.projectx.contracts.exception.AttachmentConflictException;
import ru.projectx.contracts.exception.AttachmentDefect;
import ru.projectx.contracts.exception.AttachmentRejectedException;
import ru.projectx.contracts.exception.RequestCancelledException;
import ru.projectx.contracts.exception.RequestNotFoundException;
import ru.projectx.contracts.exception.StorageUnavailableException;
import ru.projectx.contracts.service.RequestIds;
import ru.projectx.contracts.service.storage.AttachmentMetrics;
import ru.projectx.contracts.service.storage.AttachmentUploads;
import ru.projectx.contracts.service.storage.CertificateUpload;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class AttachmentControllerTest {

    private static final UUID REQUEST = UUID.fromString("61a7982c-a0b4-4bbf-9527-60b40b752803");
    private static final UUID PARTICIPANT = UUID.fromString("6778e8c2-985a-4d54-9bd3-0f6403b104d6");
    private static final UUID ATTACHMENT = UUID.fromString("3c2f8e10-5a7b-4d21-9f6e-1b0a7c4d8e21");
    private static final String PATH = "/internal/v1/requests/{request}/participants/{participant}/attachments";
    private static final byte[] CONTENT = "СТЕНД: вымышленная справка 070/у".getBytes(StandardCharsets.UTF_8);
    private static final String TRACE = "a41f8c02-7d3b-4e19-9a55-2c7e0b6f1d84";

    private final AttachmentUploads uploads = mock(AttachmentUploads.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AttachmentMetrics metrics = new AttachmentMetrics(meters);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AttachmentController(uploads))
            .setControllerAdvice(new RequestFormatAdvice(metrics), new DomainErrorAdvice(), new UnexpectedErrorAdvice())
            .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()))
            .addFilters(new RequestIdFilter())
            .build();

    @Test
    void acceptedUpload_answersTheRegistryRecordInSnakeCase() throws Exception {
        when(uploads.receive(any())).thenReturn(new AttachmentResponse(UUID.fromString(
                "0b6c1d2e-7f3a-4b5c-9d8e-1f2a3b4c5d6e"), ATTACHMENT, REQUEST, PARTICIPANT, "image/jpeg", 76, "ab",
                Instant.parse("2026-02-10T07:05:00Z")));

        mvc.perform(upload(REQUEST.toString(), PARTICIPANT.toString(), ATTACHMENT.toString(), file("image/jpeg")))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIds.HEADER, TRACE))
                .andExpect(jsonPath("$.document_id").value("0b6c1d2e-7f3a-4b5c-9d8e-1f2a3b4c5d6e"))
                .andExpect(jsonPath("$.attachment_id").value(ATTACHMENT.toString()))
                .andExpect(jsonPath("$.individual_request_id").value(REQUEST.toString()))
                .andExpect(jsonPath("$.participant_id").value(PARTICIPANT.toString()))
                .andExpect(jsonPath("$.content_type").value("image/jpeg"))
                .andExpect(jsonPath("$.file_size").value(76))
                .andExpect(jsonPath("$.file_hash").value("ab"))
                .andExpect(jsonPath("$.created_at").value("2026-02-10T07:05:00Z"));
    }

    @Test
    void upload_passesPathPartsAndTheFileToTheService() throws Exception {
        mvc.perform(upload(REQUEST.toString(), PARTICIPANT.toString(), ATTACHMENT.toString(), file("image/png")));

        ArgumentCaptor<CertificateUpload> received = ArgumentCaptor.forClass(CertificateUpload.class);
        verify(uploads).receive(received.capture());
        CertificateUpload upload = received.getValue();
        assertThat(upload.individualRequestId()).isEqualTo(REQUEST);
        assertThat(upload.participantId()).isEqualTo(PARTICIPANT);
        assertThat(upload.attachmentId()).isEqualTo(ATTACHMENT);
        assertThat(upload.file().contentType()).isEqualTo("image/png");
        assertThat(upload.file().size()).isEqualTo(CONTENT.length);
        assertThat(readAll(upload)).isEqualTo(CONTENT);
    }

    @ParameterizedTest
    @CsvSource({
        "not-a-guid, 6778e8c2-985a-4d54-9bd3-0f6403b104d6, 3c2f8e10-5a7b-4d21-9f6e-1b0a7c4d8e21, individual_request_id, неверный формат",
        "61a7982c-a0b4-4bbf-9527-60b40b752803, not-a-guid, 3c2f8e10-5a7b-4d21-9f6e-1b0a7c4d8e21, participant_id, неверный формат",
        "61a7982c-a0b4-4bbf-9527-60b40b752803, 6778e8c2-985a-4d54-9bd3-0f6403b104d6, not-a-guid, attachment_id, неверный формат"})
    void malformedIdentifier_isValidationErrorNamingTheField(String request, String participant, String attachment,
            String field, String reason) throws Exception {
        mvc.perform(upload(request, participant, attachment, file("image/jpeg")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message").value("запрос не соответствует формату"))
                .andExpect(jsonPath("$.error.details.length()").value(1))
                .andExpect(jsonPath("$.error.details[0].field").value(field))
                .andExpect(jsonPath("$.error.details[0].reason").value(reason))
                .andExpect(jsonPath("$.error.request_id").value(TRACE));
        verifyNoInteractions(uploads);
        assertThat(meters.find("contracts.attachments.rejected").counter()).isNull();
    }

    @Test
    void missingOrBlankAttachmentId_isValidationErrorNamingThePartWithoutRejectionMetric() throws Exception {
        mvc.perform(multipart(PATH, REQUEST, PARTICIPANT).file(file("image/jpeg")).header(RequestIds.HEADER, TRACE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("attachment_id"))
                .andExpect(jsonPath("$.error.details[0].reason").value("обязательное поле не передано"));

        mvc.perform(upload(REQUEST.toString(), PARTICIPANT.toString(), "", file("image/jpeg")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("attachment_id"));

        assertThat(meters.find("contracts.attachments.rejected").counter()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"id.txt", ""})
    void attachmentIdSentAsFilePartWithFilename_isValidationErrorWithoutRejectionMetric(String filename)
            throws Exception {
        MockMultipartFile attachmentPart = new MockMultipartFile("attachment_id", filename, MediaType.TEXT_PLAIN_VALUE,
                ATTACHMENT.toString().getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart(PATH, REQUEST, PARTICIPANT).file(attachmentPart).file(file("image/jpeg"))
                        .header(RequestIds.HEADER, TRACE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.length()").value(1))
                .andExpect(jsonPath("$.error.details[0].field").value("attachment_id"))
                .andExpect(jsonPath("$.error.details[0].reason").value("неверный формат"))
                .andExpect(jsonPath("$.error.request_id").value(TRACE));
        verifyNoInteractions(uploads);
        assertThat(meters.find("contracts.attachments.rejected").counter()).isNull();
    }

    @Test
    void missingFileOrFileWithoutFilename_isValidationErrorNamingThePartWithoutRejectionMetric() throws Exception {
        mvc.perform(multipart(PATH, REQUEST, PARTICIPANT).param("attachment_id", ATTACHMENT.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("file"))
                .andExpect(jsonPath("$.error.details[0].reason").value("обязательное поле не передано"));

        mvc.perform(multipart(PATH, REQUEST, PARTICIPANT).param("attachment_id", ATTACHMENT.toString())
                        .param("file", "plain-text"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("file"));

        assertThat(meters.find("contracts.attachments.rejected").counter()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void fileWithBlankFilename_isValidationErrorWithoutRejectionMetric(String filename) throws Exception {
        MockMultipartFile nameless = new MockMultipartFile("file", filename, "image/jpeg", CONTENT);

        mvc.perform(upload(REQUEST.toString(), PARTICIPANT.toString(), ATTACHMENT.toString(), nameless))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.length()").value(1))
                .andExpect(jsonPath("$.error.details[0].field").value("file"))
                .andExpect(jsonPath("$.error.details[0].reason").value("неверный формат"));
        verifyNoInteractions(uploads);
        assertThat(meters.find("contracts.attachments.rejected").counter()).isNull();
    }

    @Test
    void multipartParts_rejectsNullOriginalFilename() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(null);

        assertThatThrownBy(() -> MultipartParts.requireFile(file)).isInstanceOf(MissingFileNameException.class);
    }

    @Test
    void bodyThatIsNotMultipart_isValidationErrorWithoutFields() throws Exception {
        mvc.perform(post(PATH, REQUEST, PARTICIPANT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachment_id\":\"" + ATTACHMENT + "\"}").header(RequestIds.HEADER, TRACE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message").value("тело запроса не в ожидаемом формате"))
                .andExpect(jsonPath("$.error.details").isEmpty())
                .andExpect(jsonPath("$.error.request_id").value(TRACE));
    }

    @ParameterizedTest
    @CsvSource({
        "CONTENT_TYPE, тип файла не допускается",
        "SIZE, размер файла вне допустимого"})
    void rejectedFile_isValidationErrorOfTheFilePart(AttachmentDefect defect, String reason) throws Exception {
        when(uploads.receive(any())).thenThrow(new AttachmentRejectedException(defect));

        mvc.perform(upload(REQUEST.toString(), PARTICIPANT.toString(), ATTACHMENT.toString(), file("image/gif")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.length()").value(1))
                .andExpect(jsonPath("$.error.details[0].field").value("file"))
                .andExpect(jsonPath("$.error.details[0].reason").value(reason));
    }

    @Test
    void unknownRequest_isNotFound() throws Exception {
        expectError(new RequestNotFoundException(REQUEST), 404, "REQUEST_NOT_FOUND", "задачи на выпуск по заявке нет");
    }

    @Test
    void cancelledRequest_isInvalidState() throws Exception {
        expectError(new RequestCancelledException(REQUEST), 409, "INVALID_STATE", "работа по заявке прекращена");
    }

    @Test
    void conflictingAttachment_isAttachmentConflictWithNeutralMessage() throws Exception {
        expectError(new AttachmentConflictException(ATTACHMENT), 409, "ATTACHMENT_CONFLICT",
                "вложение с таким идентификатором уже зарегистрировано с другими реквизитами или удалено");
    }

    @Test
    void unavailableStorage_isServiceUnavailable() throws Exception {
        expectError(new StorageUnavailableException(new IOException("refused")), 503, "STORAGE_UNAVAILABLE",
                "хранилище файлов недоступно");
    }

    @Test
    void unexpectedFailure_isInternalErrorWithoutItsText() throws Exception {
        expectError(new IllegalStateException("текст чужого исключения"), 500, "INTERNAL_ERROR",
                "внутренняя ошибка сервиса");
    }

    @Test
    void uploadTooLargeForTheContainer_isValidationErrorOfTheFilePart() {
        assertThat(new RequestFormatAdvice(metrics).tooLarge(
                new MaxUploadSizeExceededException(10_485_760)).getBody())
                .satisfies(body -> {
                    assertThat(body.error().code()).isEqualTo("VALIDATION_ERROR");
                    assertThat(body.error().details()).singleElement().satisfies(violation -> {
                        assertThat(violation.field()).isEqualTo("file");
                        assertThat(violation.reason()).isEqualTo("размер файла больше допустимого");
                    });
                });
        assertThat(meters.get("contracts.attachments.rejected").tag("reason", "size").counter().count()).isEqualTo(1);
    }

    private void expectError(RuntimeException failure, int status, String code, String message) throws Exception {
        when(uploads.receive(any())).thenThrow(failure);

        mvc.perform(upload(REQUEST.toString(), PARTICIPANT.toString(), ATTACHMENT.toString(), file("image/jpeg")))
                .andExpect(status().is(status))
                .andExpect(header().string(RequestIds.HEADER, TRACE))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.message").value(message))
                .andExpect(jsonPath("$.error.details").isEmpty())
                .andExpect(jsonPath("$.error.request_id").value(TRACE));
    }

    private static MockMultipartHttpServletRequestBuilder upload(String request, String participant,
            String attachment, MockMultipartFile file) {
        MockMultipartHttpServletRequestBuilder builder = multipart(PATH, request, participant).file(file);
        builder.param("attachment_id", attachment).header(RequestIds.HEADER, TRACE);
        return builder;
    }

    private static MockMultipartFile file(String contentType) {
        return new MockMultipartFile("file", "Тестова_Анна_070у.jpg", contentType, CONTENT);
    }

    private static byte[] readAll(CertificateUpload upload) throws IOException {
        try (InputStream stream = upload.file().content().open()) {
            return stream.readAllBytes();
        }
    }
}

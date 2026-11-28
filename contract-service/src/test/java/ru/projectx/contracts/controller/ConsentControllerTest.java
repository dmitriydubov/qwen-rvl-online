package ru.projectx.contracts.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
import ru.projectx.contracts.dto.ConsentResponse;
import ru.projectx.contracts.exception.AttachmentConflictException;
import ru.projectx.contracts.exception.AttachmentDefect;
import ru.projectx.contracts.exception.AttachmentRejectedException;
import ru.projectx.contracts.exception.StorageUnavailableException;
import ru.projectx.contracts.service.RequestIds;
import ru.projectx.contracts.service.storage.AttachmentMetrics;
import ru.projectx.contracts.service.storage.ConsentUpload;
import ru.projectx.contracts.service.storage.ConsentUploads;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class ConsentControllerTest {

    private static final UUID POLICY = UUID.fromString("a23ba061-f742-4b20-8b95-36a0e525c087");
    private static final UUID FAMILY_MEMBER = UUID.fromString("c45dc283-1964-4d42-8d17-58c2a747e209");
    private static final UUID ATTACHMENT = UUID.fromString("4d3a9f21-6b8c-4e32-8a7f-2c1b8d5e9f32");
    private static final String PATH = "/internal/v1/policies/{policy}/family-members/{familyMember}/consents";
    private static final byte[] CONTENT = "СТЕНД: вымышленное согласие ПДн".getBytes(StandardCharsets.UTF_8);
    private static final String TRACE = "a41f8c02-7d3b-4e19-9a55-2c7e0b6f1d84";

    private final ConsentUploads uploads = mock(ConsentUploads.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AttachmentMetrics metrics = new AttachmentMetrics(meters);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ConsentController(uploads))
            .setControllerAdvice(new RequestFormatAdvice(metrics), new DomainErrorAdvice(), new UnexpectedErrorAdvice())
            .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()))
            .addFilters(new RequestIdFilter())
            .build();

    @Test
    void acceptedConsent_answersTheRegistryRecordInSnakeCase() throws Exception {
        when(uploads.receive(any())).thenReturn(new ConsentResponse(UUID.fromString(
                "0b6c1d2e-7f3a-4b5c-9d8e-1f2a3b4c5d6e"), ATTACHMENT, POLICY, FAMILY_MEMBER, "application/pdf", 89, "cd",
                Instant.parse("2026-02-10T07:05:00Z")));

        mvc.perform(upload(POLICY.toString(), FAMILY_MEMBER.toString(), ATTACHMENT.toString(), file("application/pdf")))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIds.HEADER, TRACE))
                .andExpect(jsonPath("$.document_id").value("0b6c1d2e-7f3a-4b5c-9d8e-1f2a3b4c5d6e"))
                .andExpect(jsonPath("$.attachment_id").value(ATTACHMENT.toString()))
                .andExpect(jsonPath("$.policy_id").value(POLICY.toString()))
                .andExpect(jsonPath("$.family_member_id").value(FAMILY_MEMBER.toString()))
                .andExpect(jsonPath("$.content_type").value("application/pdf"))
                .andExpect(jsonPath("$.file_size").value(89))
                .andExpect(jsonPath("$.file_hash").value("cd"))
                .andExpect(jsonPath("$.created_at").value("2026-02-10T07:05:00Z"));
    }

    @Test
    void upload_passesPolicyFamilyMemberAttachmentAndFileToService() throws Exception {
        mvc.perform(upload(POLICY.toString(), FAMILY_MEMBER.toString(), ATTACHMENT.toString(), file("image/png")));

        ArgumentCaptor<ConsentUpload> received = ArgumentCaptor.forClass(ConsentUpload.class);
        verify(uploads).receive(received.capture());
        ConsentUpload upload = received.getValue();
        assertThat(upload.policyId()).isEqualTo(POLICY);
        assertThat(upload.familyMemberId()).isEqualTo(FAMILY_MEMBER);
        assertThat(upload.attachmentId()).isEqualTo(ATTACHMENT);
        assertThat(upload.file().contentType()).isEqualTo("image/png");
        assertThat(upload.file().size()).isEqualTo(CONTENT.length);
        assertThat(readAll(upload)).isEqualTo(CONTENT);
    }

    @ParameterizedTest
    @CsvSource({
        "not-a-guid, c45dc283-1964-4d42-8d17-58c2a747e209, 4d3a9f21-6b8c-4e32-8a7f-2c1b8d5e9f32, policy_id",
        "a23ba061-f742-4b20-8b95-36a0e525c087, not-a-guid, 4d3a9f21-6b8c-4e32-8a7f-2c1b8d5e9f32, family_member_id",
        "a23ba061-f742-4b20-8b95-36a0e525c087, c45dc283-1964-4d42-8d17-58c2a747e209, not-a-guid, attachment_id"})
    void malformedIdentifier_isValidationErrorNamingTheField(String policy, String familyMember, String attachment,
            String field) throws Exception {
        mvc.perform(upload(policy, familyMember, attachment, file("application/pdf")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.length()").value(1))
                .andExpect(jsonPath("$.error.details[0].field").value(field))
                .andExpect(jsonPath("$.error.details[0].reason").value("неверный формат"))
                .andExpect(jsonPath("$.error.request_id").value(TRACE));
        verifyNoInteractions(uploads);
        assertThat(meters.find("contracts.attachments.rejected").counter()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"id.txt", ""})
    void attachmentIdWithFilename_isValidationErrorWithoutRejectionMetric(String filename) throws Exception {
        MockMultipartFile attachmentPart = new MockMultipartFile("attachment_id", filename, MediaType.TEXT_PLAIN_VALUE,
                ATTACHMENT.toString().getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart(PATH, POLICY, FAMILY_MEMBER).file(attachmentPart).file(file("application/pdf"))
                        .header(RequestIds.HEADER, TRACE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.length()").value(1))
                .andExpect(jsonPath("$.error.details[0].field").value("attachment_id"));
        verifyNoInteractions(uploads);
        assertThat(meters.find("contracts.attachments.rejected").counter()).isNull();
    }

    @Test
    void missingAttachmentIdOrMissingFile_isValidationErrorWithoutRejectionMetric() throws Exception {
        mvc.perform(multipart(PATH, POLICY, FAMILY_MEMBER).file(file("application/pdf")).header(RequestIds.HEADER, TRACE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("attachment_id"));

        mvc.perform(multipart(PATH, POLICY, FAMILY_MEMBER).param("attachment_id", ATTACHMENT.toString())
                        .header(RequestIds.HEADER, TRACE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("file"));

        assertThat(meters.find("contracts.attachments.rejected").counter()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void fileWithBlankFilename_isValidationErrorWithoutRejectionMetric(String filename) throws Exception {
        MockMultipartFile nameless = new MockMultipartFile("file", filename, "application/pdf", CONTENT);

        mvc.perform(upload(POLICY.toString(), FAMILY_MEMBER.toString(), ATTACHMENT.toString(), nameless))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("file"))
                .andExpect(jsonPath("$.error.details[0].reason").value("неверный формат"));
        verifyNoInteractions(uploads);
        assertThat(meters.find("contracts.attachments.rejected").counter()).isNull();
    }

    @Test
    void nonMultipartBody_isValidationErrorWithoutDetails() throws Exception {
        mvc.perform(post(PATH, POLICY, FAMILY_MEMBER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachment_id\":\"" + ATTACHMENT + "\"}").header(RequestIds.HEADER, TRACE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details").isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
        "CONTENT_TYPE, тип файла не допускается",
        "SIZE, размер файла вне допустимого"})
    void rejectedFile_isValidationErrorOfTheFilePart(AttachmentDefect defect, String reason) throws Exception {
        when(uploads.receive(any())).thenThrow(new AttachmentRejectedException(defect));

        mvc.perform(upload(POLICY.toString(), FAMILY_MEMBER.toString(), ATTACHMENT.toString(), file("image/gif")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("file"))
                .andExpect(jsonPath("$.error.details[0].reason").value(reason));
    }

    @Test
    void conflictAndUnavailableStorage_returnExpectedStatuses() throws Exception {
        doThrow(new AttachmentConflictException(ATTACHMENT)).when(uploads).receive(any());
        mvc.perform(upload(POLICY.toString(), FAMILY_MEMBER.toString(), ATTACHMENT.toString(), file("application/pdf")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ATTACHMENT_CONFLICT"));

        doThrow(new StorageUnavailableException(new IOException("down"))).when(uploads).receive(any());
        mvc.perform(upload(POLICY.toString(), FAMILY_MEMBER.toString(), ATTACHMENT.toString(), file("application/pdf")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("STORAGE_UNAVAILABLE"));
    }

    private static MockMultipartHttpServletRequestBuilder upload(String policy, String familyMember, String attachment,
            MockMultipartFile file) {
        MockMultipartHttpServletRequestBuilder builder = multipart(PATH, policy, familyMember).file(file);
        builder.param("attachment_id", attachment).header(RequestIds.HEADER, TRACE);
        return builder;
    }

    private static MockMultipartFile file(String contentType) {
        return new MockMultipartFile("file", "Тестова_Ольга_согласие.pdf", contentType, CONTENT);
    }

    private static byte[] readAll(ConsentUpload upload) throws IOException {
        try (InputStream stream = upload.file().content().open()) {
            return stream.readAllBytes();
        }
    }
}

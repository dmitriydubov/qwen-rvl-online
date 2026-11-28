package ru.projectx.contracts.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentConversionNotSupportedException;
import ru.projectx.contracts.dto.ErrorResponse;
import ru.projectx.contracts.service.storage.AttachmentMetrics;

class RequestFormatAdviceTest {

    @Test
    void unsupportedArgumentConversion_isValidationErrorNamingTheFieldWithoutRejectionMetric() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RequestFormatAdvice advice = new RequestFormatAdvice(new AttachmentMetrics(meters));
        MethodArgumentConversionNotSupportedException failure = new MethodArgumentConversionNotSupportedException(
                "file-part", UUID.class, "attachment_id", mock(MethodParameter.class),
                new IllegalStateException("no converter"));

        ResponseEntity<ErrorResponse> response = advice.unsupportedValue(failure);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull().satisfies(body -> {
            assertThat(body.error().code()).isEqualTo("VALIDATION_ERROR");
            assertThat(body.error().message()).isEqualTo("запрос не соответствует формату");
            assertThat(body.error().details()).singleElement().satisfies(violation -> {
                assertThat(violation.field()).isEqualTo("attachment_id");
                assertThat(violation.reason()).isEqualTo("неверный формат");
            });
        });
        assertThat(meters.find("contracts.attachments.rejected").counter()).isNull();
    }
}

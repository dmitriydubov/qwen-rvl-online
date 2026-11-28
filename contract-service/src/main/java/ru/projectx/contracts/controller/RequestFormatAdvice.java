package ru.projectx.contracts.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentConversionNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import ru.projectx.contracts.dto.ErrorResponse;
import ru.projectx.contracts.exception.AttachmentDefect;
import ru.projectx.contracts.service.storage.AttachmentMetrics;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestFormatAdvice {

    static final String INVALID_FORMAT = "неверный формат";
    static final String MISSING = "обязательное поле не передано";
    static final String TOO_LARGE = "размер файла больше допустимого";

    private final AttachmentMetrics metrics;

    public RequestFormatAdvice(AttachmentMetrics metrics) {
        this.metrics = metrics;
    }

    @ExceptionHandler
    public ResponseEntity<ErrorResponse> invalidValue(MethodArgumentTypeMismatchException invalid) {
        return ApiErrors.invalidField(invalid.getName(), INVALID_FORMAT);
    }

    @ExceptionHandler
    public ResponseEntity<ErrorResponse> unsupportedValue(MethodArgumentConversionNotSupportedException invalid) {
        return ApiErrors.invalidField(invalid.getName(), INVALID_FORMAT);
    }

    @ExceptionHandler
    public ResponseEntity<ErrorResponse> missingParameter(MissingServletRequestParameterException missing) {
        return ApiErrors.invalidField(missing.getParameterName(), MISSING);
    }

    @ExceptionHandler
    public ResponseEntity<ErrorResponse> missingPart(MissingServletRequestPartException missing) {
        return ApiErrors.invalidField(missing.getRequestPartName(), MISSING);
    }

    @ExceptionHandler
    public ResponseEntity<ErrorResponse> missingFileName(MissingFileNameException missing) {
        return ApiErrors.invalidField(ApiErrors.FILE_FIELD, INVALID_FORMAT);
    }

    @ExceptionHandler
    public ResponseEntity<ErrorResponse> tooLarge(MaxUploadSizeExceededException tooLarge) {
        metrics.countRejected(AttachmentDefect.SIZE);
        return ApiErrors.invalidField(ApiErrors.FILE_FIELD, TOO_LARGE);
    }

    @ExceptionHandler({HttpMediaTypeNotSupportedException.class, MultipartException.class})
    public ResponseEntity<ErrorResponse> unreadableBody(Exception unreadable) {
        return ApiErrors.of(HttpStatus.BAD_REQUEST, ApiErrors.VALIDATION_ERROR, "тело запроса не в ожидаемом формате");
    }
}

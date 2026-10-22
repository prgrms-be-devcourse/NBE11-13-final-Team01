package com.zipick.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zipick.global.response.ApiResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessExceptionUsesItsErrorCodeAndMessage() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "공고가 없습니다."));

        assertError(response, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "공고가 없습니다.");
    }

    @Test
    void validationCollectsOnlyTheFirstMessageForEachField() {
        BindException exception = new BindException(new Object(), "request");
        exception.addError(new FieldError("request", "page", "1 이상이어야 합니다."));
        exception.addError(new FieldError("request", "page", "숫자여야 합니다."));
        exception.addError(new FieldError("request", "size", "100 이하여야 합니다."));

        ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error().fields())
                .containsEntry("page", "1 이상이어야 합니다.")
                .containsEntry("size", "100 이하여야 합니다.")
                .hasSize(2);
    }

    @Test
    void constraintViolationCollectsFieldMessages() {
        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("getNotices.pageNo");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("1 이상이어야 합니다.");

        ResponseEntity<ApiResponse<Void>> response = handler.handleConstraintViolation(
                new ConstraintViolationException(Set.of(violation)));

        assertThat(response.getBody().error().fields())
                .containsEntry("getNotices.pageNo", "1 이상이어야 합니다.");
    }

    @Test
    void invalidAndIllegalRequestsReturnBadRequest() {
        assertError(handler.handleInvalidRequest(new Exception("invalid")),
                HttpStatus.BAD_REQUEST, "INVALID_INPUT", ErrorCode.INVALID_INPUT.getDefaultMessage());
        assertError(handler.handleIllegalArgument(new IllegalArgumentException("잘못된 값")),
                HttpStatus.BAD_REQUEST, "INVALID_INPUT", "잘못된 값");
    }

    @Test
    void frameworkAndUnknownExceptionsUseCommonErrorResponses() {
        assertError(handler.handleNotFound(new NoResourceFoundException(HttpMethod.GET, "/missing", "")),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", ErrorCode.RESOURCE_NOT_FOUND.getDefaultMessage());
        assertError(handler.handleMethodNotAllowed(new HttpRequestMethodNotSupportedException("POST")),
                HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", ErrorCode.METHOD_NOT_ALLOWED.getDefaultMessage());
        assertError(handler.handleDataConflict(new DataIntegrityViolationException("duplicate")),
                HttpStatus.CONFLICT, "DATA_CONFLICT", ErrorCode.DATA_CONFLICT.getDefaultMessage());
        assertError(handler.handleUnknown(new RuntimeException("unexpected")),
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", ErrorCode.INTERNAL_ERROR.getDefaultMessage());
    }

    private void assertError(
            ResponseEntity<ApiResponse<Void>> response,
            HttpStatus status,
            String code,
            String message
    ) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo(code);
        assertThat(response.getBody().error().message()).isEqualTo(message);
    }
}

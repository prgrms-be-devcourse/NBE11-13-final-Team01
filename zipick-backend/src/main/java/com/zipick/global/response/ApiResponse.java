package com.zipick.global.response;

import com.zipick.global.error.ErrorCode;
import java.util.Map;

/**
 * 공통 응답 형식.
 * { "success": true, "data": {...}, "error": null }
 * { "success": false, "data": null, "error": { "code": "...", "message": "..." } }
 */
public record ApiResponse<T>(boolean success, T data, ErrorBody error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null);
    }

    public static ApiResponse<Void> fail(ErrorCode code, String message) {
        return new ApiResponse<>(false, null, new ErrorBody(code.name(), message, null));
    }

    public static ApiResponse<Void> fail(ErrorCode code, String message, Map<String, String> fields) {
        return new ApiResponse<>(false, null, new ErrorBody(code.name(), message, fields));
    }

    public record ErrorBody(String code, String message, Map<String, String> fields) {}
}

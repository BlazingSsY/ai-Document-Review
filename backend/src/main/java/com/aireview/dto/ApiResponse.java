package com.aireview.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    private int code;
    private String msg;
    private T data;

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    public static <T> ApiResponse<T> success(String msg, T data) {
        return new ApiResponse<>(200, msg, data);
    }

    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(200, "success", null);
    }

    public static <T> ApiResponse<T> error(int code, String msg) {
        return new ApiResponse<>(code, msg, null);
    }

    public static <T> ApiResponse<T> error(String msg) {
        return new ApiResponse<>(500, msg, null);
    }

    public static <T> ApiResponse<T> badRequest(String msg) {
        return new ApiResponse<>(400, msg, null);
    }

    public static <T> ApiResponse<T> unauthorized(String msg) {
        return new ApiResponse<>(401, msg, null);
    }

    public static <T> ApiResponse<T> notFound(String msg) {
        return new ApiResponse<>(404, msg, null);
    }
}

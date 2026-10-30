package com.github.coldgust.ygoplayserver.web;

import com.github.coldgust.ygoplayserver.duel.DuelExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ApiError(String error) {
    }

    @ExceptionHandler(DuelExceptions.ValidationException.class)
    public ResponseEntity<ApiError> validation(DuelExceptions.ValidationException e) {
        return ResponseEntity.badRequest().body(new ApiError(e.getMessage()));
    }

    @ExceptionHandler(DuelExceptions.NotFoundException.class)
    public ResponseEntity<ApiError> notFound(DuelExceptions.NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(e.getMessage()));
    }

    @ExceptionHandler(DuelExceptions.ConflictException.class)
    public ResponseEntity<ApiError> conflict(DuelExceptions.ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        log.warn("对局服务异常: {}", e.toString());
        return ResponseEntity.internalServerError().body(new ApiError("内部错误: " + e.getMessage()));
    }
}

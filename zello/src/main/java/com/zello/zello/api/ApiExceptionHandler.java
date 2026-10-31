package com.zello.zello.api;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Error> handle(ApiException exception) {
        return response(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<Error> invalidInput() {
        return response(400, "INVALID_INPUT", "입력한 값을 확인해 주세요.");
    }

    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    ResponseEntity<Error> staleVersion() {
        return response(409, "ISSUE_CHANGED", "다른 변경이 있어요. 최신 내용을 확인한 뒤 다시 저장해 주세요.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Error> conflict() {
        return response(409, "DATA_CONFLICT", "다른 요청과 충돌했습니다. 목록을 새로고침한 뒤 다시 시도해 주세요.");
    }

    private ResponseEntity<Error> response(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new Error(code, message));
    }

    public record Error(String code, String message) { }
}

package com.decisionrail.api;

import com.decisionrail.payments.PaymentException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(PaymentException.class)
    ProblemDetail payment(PaymentException error, HttpServletRequest request) {
        return ApiProblems.problem(request, error.status(), error.code(), error.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingRequestHeaderException.class})
    ProblemDetail validation(Exception error, HttpServletRequest request) {
        return ApiProblems.problem(request, 400, "INVALID_REQUEST", "Check required headers, fields and types. Amounts must be positive integer minor units.");
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail database(DataAccessException error, HttpServletRequest request) {
        log.error("Database operation failed requestId={} category={}", request.getAttribute(RequestIdFilter.ATTRIBUTE), error.getClass().getSimpleName());
        return ApiProblems.problem(request, 503, "STORAGE_UNAVAILABLE", "The request could not be completed. Retry with the same Idempotency-Key.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail notFound(HttpServletRequest request) { return ApiProblems.problem(request, 404, "NOT_FOUND", "Resource was not found."); }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ProblemDetail method(HttpServletRequest request) { return ApiProblems.problem(request, 405, "METHOD_NOT_ALLOWED", "This HTTP method is not supported."); }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail media(HttpServletRequest request) { return ApiProblems.problem(request, 415, "UNSUPPORTED_MEDIA_TYPE", "Use application/json for authorization requests."); }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception error, HttpServletRequest request) {
        log.error("Unexpected request failure requestId={} category={}", request.getAttribute(RequestIdFilter.ATTRIBUTE), error.getClass().getSimpleName());
        return ApiProblems.problem(request, 500, "INTERNAL_ERROR", "The request could not be completed. Use the request ID when reporting this error.");
    }
}

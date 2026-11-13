package com.teamflow.common;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ProblemHandler {
  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  ResponseEntity<ProblemDetail> notFound(Exception e, HttpServletRequest request) {
    return response(HttpStatus.NOT_FOUND, "Resource not found", request);
  }

  @ExceptionHandler(org.springframework.mail.MailException.class)
  ResponseEntity<ProblemDetail> mail(Exception e, HttpServletRequest request) {
    return response(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Email delivery is unavailable. Please try again later",
        request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest request) {
    org.slf4j.LoggerFactory.getLogger(ProblemHandler.class)
        .error(
            "Unhandled server failure: {} requestId={}",
            e.getClass().getSimpleName(),
            MDC.get("requestId"));
    return response(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "An unexpected error occurred. Contact support with the request ID",
        request);
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> api(ApiException e, HttpServletRequest request) {
    return response(e.status(), e.getMessage(), request);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> validation(
      MethodArgumentNotValidException e, HttpServletRequest request) {
    var result = response(HttpStatus.BAD_REQUEST, "Please check the submitted fields", request);
    var fields = new LinkedHashMap<String, String>();
    e.getBindingResult()
        .getFieldErrors()
        .forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
    result.getBody().setProperty("errors", fields);
    return result;
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    HandlerMethodValidationException.class
  })
  ResponseEntity<ProblemDetail> malformed(Exception e, HttpServletRequest request) {
    return response(HttpStatus.BAD_REQUEST, "Invalid request format", request);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ProblemDetail> integrity(Exception e, HttpServletRequest request) {
    return response(HttpStatus.CONFLICT, "This change conflicts with existing records", request);
  }

  private ResponseEntity<ProblemDetail> response(
      HttpStatus status, String detail, HttpServletRequest request) {
    var problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("requestId", MDC.get("requestId"));
    return ResponseEntity.status(status).body(problem);
  }
}

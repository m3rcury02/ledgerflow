package com.ledgerflow.orders.internal.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

  private final ProblemDetailsFactory problemDetailsFactory;
  private final ObjectMapper objectMapper;

  public SecurityProblemHandler(
      ProblemDetailsFactory problemDetailsFactory, ObjectMapper objectMapper) {
    this.problemDetailsFactory = problemDetailsFactory;
    this.objectMapper = objectMapper;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authenticationException)
      throws IOException, ServletException {
    write(
        response,
        problemDetailsFactory.create(
            HttpStatus.UNAUTHORIZED,
            "unauthorized",
            "Unauthorized",
            "A valid bearer token is required.",
            "unauthorized",
            request));
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException, ServletException {
    write(
        response,
        problemDetailsFactory.create(
            HttpStatus.FORBIDDEN,
            "forbidden",
            "Forbidden",
            "The authenticated subject is not allowed to perform this operation.",
            "forbidden",
            request));
  }

  private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
    response.setStatus(problem.getStatus());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(response.getOutputStream(), problem);
  }
}

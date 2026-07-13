package com.ledgerflow.orders.internal.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

@Component
public class ProblemDetailsFactory {

  public ProblemDetail create(
      HttpStatus status,
      String type,
      String title,
      String detail,
      String code,
      HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create("https://ledgerflow.example/problems/" + type));
    problem.setTitle(title);
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", code);
    problem.setProperty("correlationId", request.getAttribute(CorrelationIdFilter.ATTRIBUTE));
    return problem;
  }
}

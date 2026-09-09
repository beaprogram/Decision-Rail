package com.decisionrail.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

public final class ApiProblems {
    private ApiProblems() {}

    public static ProblemDetail problem(HttpServletRequest request, int status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail);
        problem.setType(URI.create("urn:decisionrail:error:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setProperty("code", code);
        problem.setProperty("requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE));
        return problem;
    }

    public static void write(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response,
                             int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem(request, status, code, detail));
    }
}

package com.zello.zello.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApiSecurityErrors {
    private final ObjectMapper mapper;

    public ApiSecurityErrors(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getWriter(), new ApiError(code, message));
    }

    public record ApiError(String code, String message) { }
}

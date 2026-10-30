package com.zello.zello.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "zello.auth.github.enabled=false")
@AutoConfigureMockMvc
class AuthDisabledTests {
    @Autowired MockMvc mvc;

    @Test
    void missingConfigurationDoesNotPretendToAuthenticate() throws Exception {
        mvc.perform(get("/api/auth/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.githubLoginEnabled").value(false));
        mvc.perform(get("/oauth2/authorization/github")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void csrfEndpointIsAvailableBeforeLogin() throws Exception {
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.token").isNotEmpty());
    }
}

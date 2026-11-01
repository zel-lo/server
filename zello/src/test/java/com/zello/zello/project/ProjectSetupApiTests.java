package com.zello.zello.project;

import com.jayway.jsonpath.JsonPath;
import com.zello.zello.auth.*;
import com.zello.zello.domain.project.ProjectRepository;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ProjectSetupApiTests {
    static final AtomicLong IDS=new AtomicLong(810_000_000);
    @Autowired MockMvc mvc;
    @Autowired GitHubLoginService login;
    @Autowired ProjectRepository projects;
    ZelloPrincipal owner;
    @BeforeEach void setup() { long id=IDS.incrementAndGet();owner=login.login(new GitHubProfile(id,"setup-"+id,null,null,null)); }
    String body(String key,String start,String end) {
        return "{\"name\":\"새 팀 공간\",\"startDate\":\""+start+"\",\"endDate\":\""+end+"\",\"requestKey\":\""+key+"\"}";
    }
    ResultActions create(String body) throws Exception { return mvc.perform(post("/api/projects").with(as(owner)).with(csrf()).contentType("application/json").content(body)); }
    org.springframework.test.web.servlet.request.RequestPostProcessor as(ZelloPrincipal user) { return authentication(new UsernamePasswordAuthenticationToken(user,"unused",user.getAuthorities())); }
    @Test void savesPeriodAndDerivesWeeksFromAnyStartingDay() throws Exception {
        var result=create(body(UUID.randomUUID().toString(),"2026-10-28","2026-11-10")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.startDate").value("2026-10-28")).andExpect(jsonPath("$.endDate").value("2026-11-10"))
                .andExpect(jsonPath("$.totalWeeks").value(2)).andExpect(jsonPath("$.role").value("OWNER")).andReturn();
        String id=JsonPath.read(result.getResponse().getContentAsString(),"$.id");
        var project=projects.findById(Long.parseLong(id)).orElseThrow();
        assertThat(ProjectWeeks.containing(project,LocalDate.parse("2026-10-01"))).isEqualTo(LocalDate.parse("2026-10-28"));
        assertThat(ProjectWeeks.containing(project,LocalDate.parse("2026-11-03"))).isEqualTo(LocalDate.parse("2026-10-28"));
        assertThat(ProjectWeeks.containing(project,LocalDate.parse("2026-11-04"))).isEqualTo(LocalDate.parse("2026-11-04"));
        assertThat(ProjectWeeks.containing(project,LocalDate.parse("2027-01-01"))).isEqualTo(LocalDate.parse("2026-11-04"));
        assertThat(ProjectWeeks.begins(project,project.getStartDate())).isEqualTo(LocalDateTime.parse("2026-10-27T15:00:00"));
        assertThat(ProjectWeeks.ends(project,LocalDate.parse("2026-11-04"))).isNull();
        mvc.perform(get("/api/projects/"+id+"/issues?week=2026-11-04").with(as(owner))).andExpect(status().isOk());
        mvc.perform(get("/api/projects/"+id+"/issues?week=2026-11-02").with(as(owner))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/projects/"+id+"/issues?week=2026-11-11").with(as(owner))).andExpect(status().isBadRequest());
    }
    @Test void replayOfTheSameCreationNeverDuplicatesTheProject() throws Exception {
        var request=body(UUID.randomUUID().toString(),"2026-11-01","2026-11-30");
        String first=JsonPath.read(create(request).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"$.id");
        create(request).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(first));
        mvc.perform(get("/api/projects").with(as(owner))).andExpect(jsonPath("$.length()").value(1));
        create(request.replace("새 팀 공간","다른 이름")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_KEY_CONFLICT"));
    }
    @Test void invalidOrIncompletePeriodsAreRejectedWithoutCreatingAnything() throws Exception {
        create(body(UUID.randomUUID().toString(),"2026-11-10","2026-11-01")).andExpect(status().isBadRequest());
        create(body(UUID.randomUUID().toString(),"2026-11-01","2037-01-01")).andExpect(status().isBadRequest());
        create("{\"name\":\"잘못된 기간\",\"startDate\":\"2026-11-01\"}").andExpect(status().isBadRequest());
        create("{\"name\":\"기간 누락\",\"requestKey\":\""+UUID.randomUUID()+"\"}").andExpect(status().isBadRequest());
        mvc.perform(get("/api/projects").with(as(owner))).andExpect(jsonPath("$.length()").value(0));
    }
    @Test void creationRequiresAuthenticationAndCsrf() throws Exception {
        var request=body(UUID.randomUUID().toString(),"2026-11-01","2026-11-03");
        mvc.perform(post("/api/projects").with(as(owner)).contentType("application/json").content(request)).andExpect(status().isForbidden());
        mvc.perform(post("/api/projects").with(csrf()).contentType("application/json").content(request)).andExpect(status().isUnauthorized());
    }
    @Test void existingPeriodlessProjectsKeepCalendarWeeks() throws Exception {
        var created=create("{\"name\":\"기존 API 호환\"}").andExpect(status().isCreated()).andExpect(jsonPath("$.totalWeeks").value(0)).andReturn();
        String id=JsonPath.read(created.getResponse().getContentAsString(),"$.id");
        var project=projects.findById(Long.parseLong(id)).orElseThrow();
        assertThat(ProjectWeeks.containing(project,LocalDate.parse("2026-11-01"))).isEqualTo(LocalDate.parse("2026-10-26"));
    }
}

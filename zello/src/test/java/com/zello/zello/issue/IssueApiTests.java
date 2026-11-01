package com.zello.zello.issue;

import com.jayway.jsonpath.JsonPath;
import com.zello.zello.api.ApiException;
import com.zello.zello.auth.*;
import com.zello.zello.domain.issue.*;
import com.zello.zello.domain.notification.NotificationRepository;
import com.zello.zello.domain.project.*;
import com.zello.zello.domain.user.UserRepository;
import com.zello.zello.integration.github.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="zello.auth.github.app-type=OAUTH")
@AutoConfigureMockMvc
class IssueApiTests {
    static final AtomicLong IDS=new AtomicLong(310_000_000);
    @Autowired MockMvc mvc;
    @Autowired GitHubLoginService login;
    @Autowired ProjectRepository projects;
    @Autowired ProjectMemberRepository members;
    @Autowired UserRepository users;
    @Autowired IssueRepository issues;
    @Autowired com.zello.zello.domain.repository.RepositorySyncRunRepository syncRuns;
    @Autowired NotificationRepository notifications;
    @MockitoBean GitHubIssues github;
    @MockitoBean GitHubOAuthRepositories oauth;
    @MockitoBean GitHubTokenProvider tokens;
    ZelloPrincipal owner;
    long projectId,repositoryId,sourceId,githubId;
    @BeforeEach void setup() throws Exception {
        githubId=IDS.incrementAndGet();owner=login.login(new GitHubProfile(githubId,"issue-owner-"+githubId,null,null,null));
        sourceId=IDS.incrementAndGet();
        when(tokens.oauthToken(any(),any())).thenReturn("test-session-token");
        when(oauth.repository(anyString(),eq(sourceId))).thenReturn(new GitHubGateway.RemoteRepository(sourceId,
                new GitHubGateway.Owner(45,"team","Organization",null),"server","team/server","https://github.com/team/server",null,"Java","main",true,false));
        projectId=Long.parseLong(JsonPath.read(mvc.perform(post("/api/projects").with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"name\":\"이슈 프로젝트\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"$.id"));
        repositoryId=Long.parseLong(JsonPath.read(mvc.perform(post(base("/repositories")).with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"githubRepositoryId\":\""+sourceId+"\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"$.id"));
    }
    String details(String assignee) {
        return "{\"title\":\"로그인 개선\",\"description\":\"설명\",\"acceptanceCriteria\":\"- [ ] 로그인 확인\",\"status\":\"TODO\",\"priority\":\"HIGH\",\"source\":\"DAILY\",\"meetingDate\":\"2026-10-31\",\"meetingContext\":\"회의 결정\",\"assignee\":"+(assignee==null ? "null" : "\""+assignee+"\"")+"}";
    }
    ResultActions create(String key,String assignee) throws Exception {
        return mvc.perform(post(base("/issues")).with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"repositoryId\":\""+repositoryId+"\",\"requestKey\":\""+key+"\",\"details\":"+details(assignee)+"}"));
    }
    String createId() throws Exception {return JsonPath.read(create(UUID.randomUUID().toString(),null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"$.id");}
    long version(String id) throws Exception {return ((Number)JsonPath.read(detail(id).andReturn().getResponse().getContentAsString(),"$.version")).longValue();}
    ResultActions detail(String id) throws Exception {return mvc.perform(get(base("/issues/"+id)).with(as(owner)));}
    ResultActions publish(String id) throws Exception {return mvc.perform(post(base("/issues/"+id+"/publish")).with(as(owner)).with(csrf()).contentType("application/json").content("{\"version\":"+version(id)+"}"));}
    GitHubIssues.Remote remote(int number,String body,String state) {
        return new GitHubIssues.Remote(sourceId*100+number,number,"https://github.com/team/server/issues/"+number,"로그인 개선",body,state,
                new GitHubIssues.Person(githubId,"issue-owner-"+githubId,"User",null),List.of(),Instant.parse("2026-10-31T12:00:00Z"),null,List.of(new GitHubIssues.Label("priority:high")));
    }
    @Test void savesLocallyBeforePublishingAndReplayDoesNotDuplicate() throws Exception {
        String key=UUID.randomUUID().toString();String id=JsonPath.read(create(key,null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.publishStatus").value("LOCAL")).andExpect(jsonPath("$.number").isEmpty())
                .andReturn().getResponse().getContentAsString(),"$.id");
        create(key,null).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(get(base("/issues")).with(as(owner))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        verifyNoInteractions(github);
        when(github.create(anyString(),eq("team"),eq("server"),anyString(),anyString(),anyList(),anyString())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat((String)call.getArgument(4)).contains("## 완료 조건","- [ ] 로그인 확인","<!-- zello:issue:");
            assertThat((String)call.getArgument(4)).doesNotContain("회의 결정");
            return remote(7,call.getArgument(4),"open");
        });
        publish(id).andExpect(status().isOk()).andExpect(jsonPath("$.publishStatus").value("PUBLISHED"))
                .andExpect(jsonPath("$.number").value(7)).andExpect(jsonPath("$.description").value("설명"))
                .andExpect(jsonPath("$.acceptanceCriteria").value("- [ ] 로그인 확인"));
        publish(id).andExpect(status().isOk());
        verify(github,times(1)).create(anyString(),anyString(),anyString(),anyString(),anyString(),anyList(),anyString());
    }
    @Test void changedCreatePayloadWithTheSameKeyIsRejected() throws Exception {
        String key=UUID.randomUUID().toString();create(key,null).andExpect(status().isCreated());
        mvc.perform(post(base("/issues")).with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"repositoryId\":\""+repositoryId+"\",\"requestKey\":\""+key+"\",\"details\":"+details(null).replace("로그인 개선","다른 요청")+"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_KEY_CONFLICT"));
    }
    @Test void requestFingerprintsDistinguishFieldBoundariesInsideUserText() throws Exception {
        String key=UUID.randomUUID().toString();
        String first=details(null).replace("로그인 개선","x, description=y").replace("설명","z");
        String second=details(null).replace("로그인 개선","x").replace("설명","y, description=z");
        mvc.perform(post(base("/issues")).with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"repositoryId\":\""+repositoryId+"\",\"requestKey\":\""+key+"\",\"details\":"+first+"}")) .andExpect(status().isCreated());
        mvc.perform(post(base("/issues")).with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"repositoryId\":\""+repositoryId+"\",\"requestKey\":\""+key+"\",\"details\":"+second+"}")) .andExpect(status().isConflict());
    }
    @Test void editsLocalIssuesWithoutGithubWritesAndRejectsStaleVersions() throws Exception {
        var id=createId();long version=version(id);
        update(id,version,details(null).replace("로그인 개선","수정한 작업"),"OPEN").andExpect(status().isOk()).andExpect(jsonPath("$.title").value("수정한 작업"));
        update(id,version,details(null),"OPEN").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ISSUE_CHANGED"));
        verifyNoInteractions(github);
    }
    @Test void archivesWithoutDeletingGithubAndRestores() throws Exception {
        var id=createId();
        mvc.perform(delete(base("/issues/"+id)+"?version="+version(id)).with(as(owner)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get(base("/issues")).with(as(owner))).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(base("/issues")+"?archived=true").with(as(owner))).andExpect(jsonPath("$.total").value(1));
        mvc.perform(post(base("/issues/"+id+"/restore")+"?version="+version(id)).with(as(owner)).with(csrf())).andExpect(status().isNoContent());
        detail(id).andExpect(jsonPath("$.archived").value(false));verifyNoInteractions(github);
    }
    @Test void clearPublishRejectionCanRetryWhileUnknownResultsNeverPostAgain() throws Exception {
        var id=createId();
        when(github.create(anyString(),anyString(),anyString(),anyString(),anyString(),anyList(),anyString())).thenThrow(new ApiException(422,"ISSUE_REJECTED","거부됨"));
        publish(id).andExpect(status().isUnprocessableContent());detail(id).andExpect(jsonPath("$.publishStatus").value("FAILED"));
        doThrow(new ApiException(503,"GITHUB_RESULT_UNKNOWN","확인 필요")).when(github).create(anyString(),anyString(),anyString(),anyString(),anyString(),anyList(),anyString());
        publish(id).andExpect(status().isServiceUnavailable());detail(id).andExpect(jsonPath("$.publishStatus").value("UNKNOWN"));
        // A later retry can reconcile; it must never send another POST after an uncertain result.
        var row=issues.findById(Long.parseLong(id)).orElseThrow();row.claim(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));issues.saveAndFlush(row);
        var recovered=remote(8,IssueService.body("설명","- [ ] 로그인 확인",row.getPublishRequestKey()),"open");
        when(github.fetch(anyString(),anyString(),anyString())).thenReturn(new GitHubIssues.Fetched(List.of(recovered),false));
        publish(id).andExpect(status().isOk()).andExpect(jsonPath("$.number").value(8));
        verify(github,times(2)).create(anyString(),anyString(),anyString(),anyString(),anyString(),anyList(),anyString());
    }
    @Test void syncKeepsMeetingContextAndDoesNotResurrectArchivedRowsOrOverwriteNewerData() throws Exception {
        when(github.page(anyString(),anyString(),anyString(),eq(1))).thenReturn(new GitHubGateway.Page<>(List.of(remote(9,"기존 GitHub 본문","closed")),false,1));
        sync().andExpect(status().isOk());sync().andExpect(status().isOk());
        var list=mvc.perform(get(base("/issues")).with(as(owner))).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].status").value("DONE")).andReturn();
        String id=JsonPath.read(list.getResponse().getContentAsString(),"$.items[0].id");
        mvc.perform(delete(base("/issues/"+id)+"?version="+version(id)).with(as(owner)).with(csrf())).andExpect(status().isNoContent());
        sync().andExpect(status().isOk());detail(id).andExpect(jsonPath("$.archived").value(true));
    }
    @Test void assignmentNotifiesOnlyOtherActiveProjectMembersAndReplaysDoNotNotifyTwice() throws Exception {
        var other=member(ProjectMember.Role.MEMBER);var otherLogin=users.findWithGithubAccountById(other.userId()).orElseThrow().getGithubAccount().getLogin();
        String key=UUID.randomUUID().toString();long count=notifications.count();
        create(key,otherLogin).andExpect(status().isCreated()).andExpect(jsonPath("$.assignee").value(otherLogin));
        create(key,otherLogin).andExpect(status().isCreated());assertThat(notifications.count()).isEqualTo(count+1);
        create(UUID.randomUUID().toString(),"outsider").andExpect(status().isBadRequest());
        create(UUID.randomUUID().toString(),"issue-owner-"+githubId).andExpect(status().isCreated());assertThat(notifications.count()).isEqualTo(count+1);
    }
    @Test void deniesViewersOutsidersCsrfAndLostSourceAccess() throws Exception {
        var id=createId();var viewer=member(ProjectMember.Role.VIEWER);
        mvc.perform(patch(base("/issues/"+id)).with(as(viewer)).with(csrf()).contentType("application/json")
                .content("{\"version\":0,\"details\":"+details(null)+",\"githubState\":\"OPEN\"}")) .andExpect(status().isForbidden());
        var outsider=login.login(new GitHubProfile(IDS.incrementAndGet(),"issue-outsider",null,null,null));
        mvc.perform(get(base("/issues/"+id)).with(as(outsider))).andExpect(status().isNotFound());
        mvc.perform(delete(base("/issues/"+id)+"?version=0").with(as(owner))).andExpect(status().isForbidden());
        when(oauth.repository(anyString(),eq(sourceId))).thenThrow(new ApiException(404,"GITHUB_RESOURCE_NOT_FOUND","원본 접근 취소"));
        detail(id).andExpect(status().isNotFound());mvc.perform(get(base("/issues")).with(as(owner))).andExpect(jsonPath("$.total").value(0));
        verifyNoInteractions(github);
    }
    @Test void publishedUpdatePreservesOtherLabelsAssigneesAndMeetingContext() throws Exception {
        String id=createId();
        when(github.create(anyString(),anyString(),anyString(),anyString(),anyString(),anyList(),anyString()))
                .thenAnswer(call -> remote(11,call.getArgument(4),"open"));
        publish(id).andExpect(status().isOk());
        var current=remote(11,IssueService.body("설명","- [ ] 로그인 확인",issues.findById(Long.parseLong(id)).orElseThrow().getPublishRequestKey()),"open");
        var withLabels=new GitHubIssues.Remote(current.id(),current.number(),current.htmlUrl(),current.title(),current.body(),current.state(),current.user(),
                List.of(new GitHubIssues.Person(900,"external-collaborator","User",null)),current.updatedAt(),null,
                List.of(new GitHubIssues.Label("type:bug"),new GitHubIssues.Label("priority:low")));
        when(github.detail(anyString(),anyString(),anyString(),eq(11))).thenReturn(withLabels);
        when(github.update(anyString(),anyString(),anyString(),eq(11),anyMap())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Map<String,Object> fields=call.getArgument(4);
            assertThat((List<String>)fields.get("labels")).containsExactly("type:bug","priority:high");
            assertThat((List<String>)fields.get("assignees")).containsExactly("external-collaborator");
            assertThat(fields).containsEntry("state","closed").containsEntry("state_reason","completed");
            return remote(11,(String)fields.get("body"),"closed");
        });
        update(id,version(id),details(null).replace("로그인 개선","작업 완료"),"CLOSED").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE")).andExpect(jsonPath("$.meetingContext").value("회의 결정"));
    }
    @Test void sourceEditsAreRefreshedAndRejectedBeforeOverwritingGithub() throws Exception {
        String id=createId();
        when(github.create(anyString(),anyString(),anyString(),anyString(),anyString(),anyList(),anyString())).thenAnswer(call -> remote(12,call.getArgument(4),"open"));
        publish(id).andExpect(status().isOk());
        when(github.detail(anyString(),anyString(),anyString(),eq(12))).thenReturn(remote(12,"누군가 GitHub에서 바꾼 내용","open"));
        update(id,version(id),details(null).replace("로그인 개선","내 수정"),"OPEN").andExpect(status().isConflict());
        detail(id).andExpect(jsonPath("$.description").value("누군가 GitHub에서 바꾼 내용"));
        verify(github,never()).update(anyString(),anyString(),anyString(),anyInt(),anyMap());
    }
    @Test void partialSyncCommitsCompletedPagesAndRecordsTheFailure() throws Exception {
        when(github.page(anyString(),anyString(),anyString(),eq(1))).thenReturn(new GitHubGateway.Page<>(List.of(remote(10,"첫 페이지","open")),true,1));
        when(github.page(anyString(),anyString(),anyString(),eq(2))).thenThrow(new ApiException(502,"GITHUB_UNAVAILABLE","두 번째 페이지 실패"));
        sync().andExpect(status().isBadGateway());
        mvc.perform(get(base("/issues")).with(as(owner))).andExpect(jsonPath("$.total").value(1));
        var run=syncRuns.findAll().stream().filter(r -> r.getRepositoryId()==repositoryId).findFirst().orElseThrow();
        assertThat(run.getStatus()).isEqualTo(com.zello.zello.domain.repository.RepositorySyncRun.Status.FAILED);
        assertThat(run.getItemsProcessed()).isEqualTo(1);
        assertThat(run.getCheckpoint()).containsEntry("nextPage",2);
        assertThat(run.getEmitNotifications()).isFalse();
    }
    @Test void rejectsMeetingsWithoutDateInvalidFiltersAndRepositoryIds() throws Exception {
        mvc.perform(post(base("/issues")).with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"repositoryId\":\""+repositoryId+"\",\"requestKey\":\""+UUID.randomUUID()+"\",\"details\":"+details(null).replace("\"2026-10-31\"","null")+"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(base("/issues")+"?status=WRONG").with(as(owner))).andExpect(status().isBadRequest());
        mvc.perform(get(base("/issues")+"?size=1000").with(as(owner))).andExpect(status().isBadRequest());verifyNoInteractions(github);
    }

    @Test void boardPlacementPersistsBetweenNeighborsAndRejectsStaleWrites() throws Exception {
        var first=createId();var second=createId();var third=createId();
        long before=version(first);
        placement(first,before,"TODO",second).andExpect(status().isOk());
        mvc.perform(get(base("/issues")).with(as(owner))).andExpect(jsonPath("$.items[0].id").value(third)).andExpect(jsonPath("$.items[1].id").value(first)).andExpect(jsonPath("$.items[2].id").value(second));
        placement(first,before,"TODO",null).andExpect(status().isConflict());
        placement(second,version(second),"IN_PROGRESS",null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mvc.perform(get(base("/issues")+"?status=IN_PROGRESS").with(as(owner))).andExpect(jsonPath("$.items[0].id").value(second));
        // An anchor in a different column is rejected before any update.
        placement(first,version(first),"TODO",second).andExpect(status().isConflict());
        detail(first).andExpect(jsonPath("$.status").value("TODO"));
        verifyNoInteractions(github);
    }
    @Test void weeklyWorkCarriesWithoutCopiesAndCompletionStaysInItsWeek() throws Exception {
        var week=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).with(java.time.DayOfWeek.MONDAY);
        var previous=week.minusWeeks(1);
        var id=createId();
        String plan=details(null).replace("\"status\":\"TODO\"","\"status\":\"TODO\",\"plannedWeekStart\":\""+previous+"\"");
        update(id,version(id),plan,"OPEN").andExpect(status().isOk());
        mvc.perform(get(base("/issues")+"?week="+week).with(as(owner))).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id)).andExpect(jsonPath("$.items[0].plannedWeekStart").value(previous.toString()));
        mvc.perform(get(base("/issues")+"?week="+week.plusWeeks(1)).with(as(owner))).andExpect(jsonPath("$.items[0].id").value(id));
        mvc.perform(get(base("/issues")+"?week="+week+"&assignee=unassigned&repositoryId="+repositoryId).with(as(owner))).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(base("/issues")+"?week="+week+"&assignee=nobody").with(as(owner))).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(base("/issues")+"?week="+week+"&repositoryId=999999999").with(as(owner))).andExpect(jsonPath("$.total").value(0));
        assertThat(issues.findByRepositoryId(repositoryId)).hasSize(1);
        update(id,version(id),plan.replace("\"status\":\"TODO\"","\"status\":\"DONE\""),"CLOSED").andExpect(status().isOk()).andExpect(jsonPath("$.completedAt").isNotEmpty());
        mvc.perform(get(base("/issues")+"?week="+week).with(as(owner))).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(base("/issues")+"?week="+week.plusWeeks(1)).with(as(owner))).andExpect(jsonPath("$.total").value(0));
        update(id,version(id),plan,"OPEN").andExpect(status().isOk()).andExpect(jsonPath("$.completedAt").isEmpty());
        mvc.perform(get(base("/issues")+"?week="+week.plusWeeks(1)).with(as(owner))).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(base("/issues")+"?week="+week.plusDays(1)).with(as(owner))).andExpect(status().isBadRequest());
        verifyNoInteractions(github);
    }
    @Test void boardPlacementRequiresWriterCsrfAndUnarchivedWork() throws Exception {
        var id=createId();var viewer=member(ProjectMember.Role.VIEWER);
        String body="{\"version\":"+version(id)+",\"details\":"+details(null)+",\"githubState\":\"OPEN\",\"placement\":{\"beforeId\":null}}";
        mvc.perform(patch(base("/issues/"+id)).with(as(viewer)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(patch(base("/issues/"+id)).with(as(owner)).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(delete(base("/issues/"+id)+"?version="+version(id)).with(as(owner)).with(csrf())).andExpect(status().isNoContent());
        placement(id,version(id),"TODO",null).andExpect(status().isConflict());
        verifyNoInteractions(github);
    }
    ResultActions placement(String id,long version,String state,String beforeId) throws Exception {
        return mvc.perform(patch(base("/issues/"+id)).with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"version\":"+version+",\"details\":"+details(null).replace("\"TODO\"","\""+state+"\"")
                +",\"githubState\":\"OPEN\",\"placement\":{\"beforeId\":"+(beforeId==null ? "null" : "\""+beforeId+"\"")+"}}"));
    }

    ResultActions update(String id,long version,String details,String state) throws Exception {
        return mvc.perform(patch(base("/issues/"+id)).with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"version\":"+version+",\"details\":"+details+",\"githubState\":\""+state+"\"}"));
    }
    ZelloPrincipal member(ProjectMember.Role role) {
        long gid=IDS.incrementAndGet();var user=login.login(new GitHubProfile(gid,"issue-member-"+gid,null,null,null));
        members.saveAndFlush(ProjectMember.builder().project(projects.findById(projectId).orElseThrow()).user(users.findById(user.userId()).orElseThrow()).role(role).build());return user;
    }
    ResultActions sync() throws Exception {return mvc.perform(post(base("/issues/sync")).with(as(owner)).with(csrf()));}
    String base(String suffix) {return "/api/projects/"+projectId+suffix;}
    org.springframework.test.web.servlet.request.RequestPostProcessor as(ZelloPrincipal principal) {return authentication(new UsernamePasswordAuthenticationToken(principal,"unused",principal.getAuthorities()));}
}

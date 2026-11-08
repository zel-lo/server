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
    @Test void workspaceSummaryAndNotificationReadsAreScopedToRecipient() throws Exception {
        var other=member(ProjectMember.Role.MEMBER);
        var otherLogin=users.findWithGithubAccountById(other.userId()).orElseThrow().getGithubAccount().getLogin();
        create(UUID.randomUUID().toString(),otherLogin).andExpect(status().isCreated());
        mvc.perform(get(base("/summary")).with(as(other))).andExpect(status().isOk()).andExpect(jsonPath("$.issues.TODO").value(1)).andExpect(jsonPath("$.mine").value(1)).andExpect(jsonPath("$.unread").value(1)).andExpect(jsonPath("$.contributions[0].count").value(1));
        String nid=JsonPath.read(mvc.perform(get(base("/notifications?unread=true")).with(as(other))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),"$.items[0].id");
        mvc.perform(patch(base("/notifications/"+nid)).with(as(owner)).with(csrf()).contentType("application/json").content("{\"read\":true}")).andExpect(status().isNotFound());
        mvc.perform(patch(base("/notifications/"+nid)).with(as(other)).contentType("application/json").content("{\"read\":true}")).andExpect(status().isForbidden());
        mvc.perform(patch(base("/notifications/"+nid)).with(as(other)).with(csrf()).contentType("application/json").content("{\"read\":true}")).andExpect(status().isNoContent());
        mvc.perform(get(base("/summary")).with(as(other))).andExpect(jsonPath("$.unread").value(0));
        mvc.perform(get(base("/activities" )).with(as(other))).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].type").value("ISSUE_ASSIGNED"));
        var outsider=login.login(new GitHubProfile(IDS.incrementAndGet(),"outsider",null,null,null));
        mvc.perform(get(base("/summary")).with(as(outsider))).andExpect(status().isNotFound());
    }
    @Test void weeklyDashboardCountsThePlanAndCarryOverWithoutBacklogFutureOrOldCompletions() throws Exception {
        var week=LocalDate.now(ZoneId.of("Asia/Seoul")).with(DayOfWeek.MONDAY);
        var ownerLogin=users.findWithGithubAccountById(owner.userId()).orElseThrow().getGithubAccount().getLogin();
        createId(); // Unassigned task in the current plan.
        String carry=JsonPath.read(create(UUID.randomUUID().toString(),ownerLogin).andReturn().getResponse().getContentAsString(),"$.id");
        String carrying=details(ownerLogin).replace("TODO","IN_PROGRESS");
        carrying=carrying.substring(0,carrying.length()-1)+",\"plannedWeekStart\":\""+week.minusWeeks(1)+"\"}";
        update(carry,version(carry),carrying,"OPEN").andExpect(status().isOk());
        String done=JsonPath.read(create(UUID.randomUUID().toString(),ownerLogin).andReturn().getResponse().getContentAsString(),"$.id");
        mvc.perform(patch(base("/issues/"+done)).with(as(owner)).with(csrf()).contentType("application/json")
            .content("{\"version\":"+version(done)+",\"details\":"+details(ownerLogin).replace("TODO","DONE")+",\"githubState\":\"CLOSED\",\"patchNoteTitle\":\"사용자에게 전달할 완료 제목\"}"))
            .andExpect(status().isOk());
        String backlog=createId();update(backlog,version(backlog),details(null).replace("TODO","BACKLOG"),"OPEN").andExpect(status().isOk());
        String future=createId(), planned=details(null);planned=planned.substring(0,planned.length()-1)+",\"plannedWeekStart\":\""+week.plusWeeks(1)+"\"}";
        update(future,version(future),planned,"OPEN").andExpect(status().isOk());
        String archived=createId();issueService.archive(projectId,owner.userId(),Long.parseLong(archived),version(archived),true);
        var closed=remote(55,"오래전에 완료한 기록","closed");
        var old=week.minusDays(2).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        issueService.sync(projectId,owner.userId(),repositoryId,List.of(new GitHubIssues.Remote(closed.id(),closed.number(),closed.htmlUrl(),closed.title(),closed.body(),closed.state(),closed.user(),closed.assignees(),old,null,closed.labels(),old)));
        mvc.perform(get(base("/dashboard")).with(as(owner))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.weekStart").value(week.toString())).andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.completed").value(1))
            .andExpect(jsonPath("$.percentage").value(33)).andExpect(jsonPath("$.carriedOver").value(1)).andExpect(jsonPath("$.completedThisWeek").value(1))
            .andExpect(jsonPath("$.states.TODO").doesNotExist()).andExpect(jsonPath("$.states.IN_PROGRESS").value(2)).andExpect(jsonPath("$.states.DONE").value(1))
            .andExpect(jsonPath("$.attentionCounts.unassigned").value(1)).andExpect(jsonPath("$.people[0].inProgress").value(1))
            .andExpect(jsonPath("$.people[0].completed").value(1)).andExpect(jsonPath("$.people[0].tasks[0].id").value(carry))
            .andExpect(jsonPath("$.recent[0].title").value("사용자에게 전달할 완료 제목")).andExpect(jsonPath("$.recent[0].needsTitle").value(false));
    }
    @Test void weeklyDashboardHandlesEmptyProjectsAndMembershipScope() throws Exception {
        var viewer=member(ProjectMember.Role.VIEWER);
        mvc.perform(get(base("/dashboard")).with(as(viewer))).andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.percentage").value(0)).andExpect(jsonPath("$.attention.length()").value(0))
            .andExpect(jsonPath("$.recent.length()").value(0)).andExpect(jsonPath("$.people.length()").value(2));
        var outsider=login.login(new GitHubProfile(IDS.incrementAndGet(),"dashboard-outsider",null,null,null));
        mvc.perform(get(base("/dashboard")).with(as(outsider))).andExpect(status().isNotFound());
        mvc.perform(get(base("/dashboard"))).andExpect(status().isUnauthorized());
    }
    @Test void dashboardWeekSelectionUsesTheChosenCompletionWindowAndKeepsTheCurrentWeek() throws Exception {
        var current=LocalDate.now(ZoneId.of("Asia/Seoul")).with(DayOfWeek.MONDAY);
        var previous=current.minusWeeks(1);
        String active=createId(), plan=details(null).replace("TODO","IN_PROGRESS");
        plan=plan.substring(0,plan.length()-1)+",\"plannedWeekStart\":\""+previous+"\"}";
        update(active,version(active),plan,"OPEN").andExpect(status().isOk());
        createId(); // A current-week plan must not enter the previous week.
        var old=remote(70,"지난 주 완료 기록","closed");
        var date=previous.plusDays(2).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        issueService.sync(projectId,owner.userId(),repositoryId,List.of(new GitHubIssues.Remote(old.id(),old.number(),old.htmlUrl(),old.title(),old.body(),old.state(),old.user(),old.assignees(),date,null,old.labels(),date)));
        var row=issues.findByRepositoryId(repositoryId).stream().filter(i -> Objects.equals(i.getGithubNumber(),70)).findFirst().orElseThrow();
        row.schedule(previous);issues.saveAndFlush(row);
        mvc.perform(get(base("/dashboard")+"?week="+previous).with(as(owner))).andExpect(status().isOk())
            .andExpect(jsonPath("$.weekStart").value(previous.toString())).andExpect(jsonPath("$.weekEnd").value(previous.plusDays(6).toString()))
            .andExpect(jsonPath("$.currentWeek").value(current.toString())).andExpect(jsonPath("$.periodLabel").value("지난 주"))
            .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.percentage").value(50))
            .andExpect(jsonPath("$.completedThisWeek").value(1)).andExpect(jsonPath("$.recent[0].id").value(row.getId().toString()));
        mvc.perform(get(base("/dashboard")).with(as(owner))).andExpect(status().isOk())
            .andExpect(jsonPath("$.weekStart").value(current.toString())).andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.completedThisWeek").value(0)).andExpect(jsonPath("$.recent.length()").value(0));
        mvc.perform(get(base("/dashboard")+"?week="+previous.plusDays(1)).with(as(owner))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_WEEK"));
        mvc.perform(get(base("/dashboard")+"?week=not-a-date").with(as(owner))).andExpect(status().isBadRequest());
    }
    @Autowired org.springframework.jdbc.core.JdbcTemplate dashboardJdbc;
    @Test void dashboardWeeksFollowProjectStartDaysAndClampTheLastWeekToTheProjectEnd() throws Exception {
        var start=LocalDate.now(ZoneId.of("Asia/Seoul")).with(DayOfWeek.MONDAY).minusWeeks(1).plusDays(2);
        var end=start.plusDays(16);
        dashboardJdbc.update("update projects set start_date=?,end_date=? where id=?",java.sql.Date.valueOf(start),java.sql.Date.valueOf(end),projectId);
        mvc.perform(get(base("/dashboard")+"?week="+start.plusWeeks(2)).with(as(owner))).andExpect(status().isOk())
            .andExpect(jsonPath("$.weekStart").value(start.plusWeeks(2).toString())).andExpect(jsonPath("$.weekEnd").value(end.toString()))
            .andExpect(jsonPath("$.projectStartDate").value(start.toString())).andExpect(jsonPath("$.projectEndDate").value(end.toString()));
        mvc.perform(get(base("/dashboard")+"?week="+start.minusWeeks(1)).with(as(owner))).andExpect(status().isBadRequest());
        mvc.perform(get(base("/dashboard")+"?week="+start.plusWeeks(3)).with(as(owner))).andExpect(status().isBadRequest());
        mvc.perform(get(base("/dashboard")+"?week="+start.plusDays(1)).with(as(owner))).andExpect(status().isBadRequest());
    }
    @Test void patchNotesPersistPublishOnceAndRejectViewersAndStaleVersions() throws Exception {
        String body="{\"title\":\"Update\",\"summary\":\"Details\",\"publish\":false}";
        var viewer=member(ProjectMember.Role.VIEWER);
        mvc.perform(post(base("/patch-notes")).with(as(viewer)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        String nid=JsonPath.read(mvc.perform(post(base("/patch-notes")).with(as(owner)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),"$.id");
        String edit="{\"title\":\"Update\",\"summary\":\"Published\",\"version\":0,\"publish\":true}";
        mvc.perform(patch(base("/patch-notes/"+nid)).with(as(owner)).with(csrf()).contentType("application/json").content(edit)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
        mvc.perform(patch(base("/patch-notes/"+nid)).with(as(owner)).with(csrf()).contentType("application/json").content(edit)).andExpect(status().isConflict());
        mvc.perform(get(base("/patch-notes")).with(as(viewer))).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].summary").value("Published"));
        mvc.perform(get(base("/activities")).with(as(viewer))).andExpect(jsonPath("$.items[0].type").value("PATCH_NOTE_PUBLISHED"));
        mvc.perform(get(base("/activities?page=0")).with(as(owner))).andExpect(status().isBadRequest());
    }
    @Test void directCompletionCollectsOneDraftAndReopeningIsVisibleWithoutDeletingHistory() throws Exception {
        String id=createId();
        update(id,version(id),details(null).replace("TODO","DONE"),"CLOSED").andExpect(status().isOk());
        update(id,version(id),details(null).replace("TODO","DONE"),"CLOSED").andExpect(status().isOk());
        var response=mvc.perform(get(base("/patch-notes")).with(as(owner))).andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].automatic").value(true))
            .andExpect(jsonPath("$.items[0].changes.length()").value(1)).andExpect(jsonPath("$.items[0].changes[0].status").value("COMPLETED"))
            .andReturn().getResponse().getContentAsString();
        String note=JsonPath.read(response,"$.items[0].id");
        long version=((Number)JsonPath.read(response,"$.items[0].version")).longValue();
        mvc.perform(patch(base("/patch-notes/"+note)).with(as(owner)).with(csrf()).contentType("application/json")
            .content("{\"title\":\"직접 정리한 제목\",\"summary\":\"편집 내용 보존\",\"version\":"+version+",\"publish\":false}")).andExpect(status().isOk());
        update(id,version(id),details(null),"OPEN").andExpect(status().isOk());
        mvc.perform(get(base("/patch-notes/"+note)).with(as(owner))).andExpect(jsonPath("$.title").value("직접 정리한 제목"))
            .andExpect(jsonPath("$.summary").value("편집 내용 보존")).andExpect(jsonPath("$.changes[0].status").value("REOPENED"));
        mvc.perform(get(base("/activities")).with(as(owner))).andExpect(jsonPath("$.items.length()").value(1));
    }
    @Test void collectingExistingCompletionsRequiresWriterAndCsrfAndIsIdempotent() throws Exception {
        String id=createId();
        // Simulate a pre-feature row without calling the completion workflow.
        var old=issues.findById(Long.parseLong(id)).orElseThrow();old.plan(Issue.WorkStatus.DONE,old.getPriority(),old.getSourceType(),old.getMeetingDate(),old.getMeetingContext(),old.getAcceptanceCriteria());issues.saveAndFlush(old);
        mvc.perform(get(base("/patch-notes")).with(as(owner))).andExpect(jsonPath("$.items.length()").value(0));
        var viewer=member(ProjectMember.Role.VIEWER);
        mvc.perform(post(base("/patch-notes/collect")).with(as(viewer)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(base("/patch-notes/collect")).with(as(owner))).andExpect(status().isForbidden());
        mvc.perform(post(base("/patch-notes/collect")).with(as(owner)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(post(base("/patch-notes/collect")).with(as(owner)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get(base("/patch-notes")).with(as(owner))).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].changes.length()").value(1));
    }
    @Test void completionStoresAnAuthoredPatchTitleWithoutRenamingTheIssueAndCollectionPreservesIt() throws Exception {
        String id=createId();
        String input="{\"version\":"+version(id)+",\"details\":"+details(null).replace("TODO","DONE")+",\"githubState\":\"CLOSED\",\"patchNoteTitle\":\"  로그인 화면을 더 편하게 개선했어요  \"}";
        mvc.perform(patch(base("/issues/"+id)).with(as(owner)).with(csrf()).contentType("application/json").content(input))
            .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("로그인 개선")).andExpect(jsonPath("$.status").value("DONE"));
        mvc.perform(post(base("/patch-notes/collect")).with(as(owner)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(post(base("/patch-notes/collect")).with(as(owner)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get(base("/patch-notes")).with(as(owner)))
            .andExpect(jsonPath("$.items[0].changes.length()").value(1))
            .andExpect(jsonPath("$.items[0].changes[0].title").value("로그인 화면을 더 편하게 개선했어요"))
            .andExpect(jsonPath("$.items[0].changes[0].needsTitle").value(false));
        String another=createId();
        mvc.perform(patch(base("/issues/"+another)).with(as(owner)).with(csrf()).contentType("application/json")
            .content("{\"version\":"+version(another)+",\"details\":"+details(null).replace("TODO","DONE")+",\"githubState\":\"CLOSED\",\"patchNoteTitle\":\"   \"}"))
            .andExpect(status().isBadRequest());
        detail(another).andExpect(jsonPath("$.status").value("TODO"));
    }
    @Test void githubCompletionNeedsAnAuthoredTitleBeforePublicationAndRejectsStaleOrUnauthorizedEdits() throws Exception {
        issueService.sync(projectId,owner.userId(),repositoryId,List.of(remote(33,"GitHub 본문","closed")));
        var response=mvc.perform(get(base("/patch-notes")).with(as(owner))).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].changes[0].needsTitle").value(true)).andReturn().getResponse().getContentAsString();
        String note=JsonPath.read(response,"$.items[0].id"), change=JsonPath.read(response,"$.items[0].changes[0].id");
        long noteVersion=((Number)JsonPath.read(response,"$.items[0].version")).longValue();
        String publication="{\"title\":\"이번 주 소식\",\"summary\":\"팀 변경 사항\",\"publish\":true,\"version\":"+noteVersion+"}";
        mvc.perform(patch(base("/patch-notes/"+note)).with(as(owner)).with(csrf()).contentType("application/json").content(publication))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHANGE_TITLE_REQUIRED"));
        String path=base("/patch-notes/"+note+"/changes/"+change);
        String edit="{\"title\":\"GitHub에서 개선한 로그인 흐름\",\"version\":"+noteVersion+"}";
        var viewer=member(ProjectMember.Role.VIEWER);
        mvc.perform(patch(path).with(as(viewer)).with(csrf()).contentType("application/json").content(edit)).andExpect(status().isForbidden());
        mvc.perform(patch(path).with(as(owner)).contentType("application/json").content(edit)).andExpect(status().isForbidden());
        mvc.perform(patch(path).with(as(owner)).with(csrf()).contentType("application/json").content(edit)).andExpect(status().isOk())
            .andExpect(jsonPath("$.changes[0].title").value("GitHub에서 개선한 로그인 흐름")).andExpect(jsonPath("$.changes[0].needsTitle").value(false));
        mvc.perform(patch(path).with(as(owner)).with(csrf()).contentType("application/json").content(edit)).andExpect(status().isConflict());
        issueService.sync(projectId,owner.userId(),repositoryId,List.of(remote(33,"GitHub 본문 변경","closed")));
        var latest=mvc.perform(get(base("/patch-notes/"+note)).with(as(owner))).andExpect(jsonPath("$.changes[0].title").value("GitHub에서 개선한 로그인 흐름"))
            .andExpect(jsonPath("$.changes[0].needsTitle").value(false)).andReturn().getResponse().getContentAsString();
        long next=((Number)JsonPath.read(latest,"$.version")).longValue();
        mvc.perform(patch(base("/patch-notes/"+note)).with(as(owner)).with(csrf()).contentType("application/json")
            .content(publication.replace("\"version\":"+noteVersion,"\"version\":"+next))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
        var published=mvc.perform(get(base("/patch-notes/"+note)).with(as(owner))).andReturn().getResponse().getContentAsString();
        long publishedVersion=((Number)JsonPath.read(published,"$.version")).longValue();
        mvc.perform(patch(path).with(as(owner)).with(csrf()).contentType("application/json").content(edit.replace("\"version\":"+noteVersion,"\"version\":"+publishedVersion)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOTE_PUBLISHED"));
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
        mvc.perform(get(base("/issues")+"?status=IN_PROGRESS").with(as(owner)))
            .andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.items[0].id").value(third)).andExpect(jsonPath("$.items[2].id").value(second));
        // Legacy TODO and IN_PROGRESS now share one column and support ordering across the two values.
        placement(first,version(first),"IN_PROGRESS",second).andExpect(status().isOk());
        placement(second,version(second),"BACKLOG",null).andExpect(status().isOk());
        // An anchor in a genuinely different column is rejected before any update.
        placement(first,version(first),"TODO",second).andExpect(status().isConflict());
        detail(first).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
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


    @Test void projectPeriodUsesItsOwnWeekAnchorForPlanningAndIssueQueries() throws Exception {
        projectId=Long.parseLong(JsonPath.read(mvc.perform(post("/api/projects").with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"name\":\"기간이 있는 프로젝트\",\"startDate\":\"2026-10-28\",\"endDate\":\"2026-11-10\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"$.id"));
        repositoryId=Long.parseLong(JsonPath.read(mvc.perform(post(base("/repositories")).with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"githubRepositoryId\":\""+sourceId+"\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"$.id"));
        var id=createId();
        var current=com.zello.zello.project.ProjectWeeks.current(projects.findById(projectId).orElseThrow()).toString();
        detail(id).andExpect(jsonPath("$.plannedWeekStart").value(current));
        var planned=details(null).replace("\"status\":\"TODO\"","\"status\":\"TODO\",\"plannedWeekStart\":\"2026-11-04\"");
        update(id,version(id),planned,"OPEN").andExpect(status().isOk());
        mvc.perform(get(base("/issues")+"?week=2026-11-04").with(as(owner))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(base("/issues")+"?week=2026-10-28").with(as(owner))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(base("/issues")+"?week=2026-11-02").with(as(owner))).andExpect(status().isBadRequest());
        verifyNoInteractions(github);
    }

    @MockitoBean GitHubBranches branches;
    @Autowired IssueService issueService;

    @Test void startsPublishedWorkFromTheCurrentDefaultBranchAndPreservesItOnReload() throws Exception {
        String id=createId();
        issueService.publish(projectId,owner.userId(),Long.parseLong(id),remote(7,"설명","open"));
        long before=version(id);
        mvc.perform(get(base("/issues/"+id+"/branch")).with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("feat/7")).andExpect(jsonPath("$.ready").value(false));
        verifyNoInteractions(branches);
        when(branches.ensure(eq("test-session-token"),eq("team"),eq("server"),eq("main"),eq("feat/7"))).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new GitHubBranches.Result("feat/7",true);
        });
        start(id,before,owner).andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.created").value(true)).andExpect(jsonPath("$.issue.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.commands").value("git fetch origin\ngit switch feat/7"));
        detail(id).andExpect(jsonPath("$.workBranch").value("feat/7")).andExpect(jsonPath("$.workBranchBase").value("main"));
        // Lost responses can be replayed with the old version once the same branch is attached.
        when(branches.ensure(anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(new GitHubBranches.Result("feat/7",false));
        start(id,before,owner).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(false));
        mvc.perform(get(base("/issues/"+id+"/branch")).with(as(owner))).andExpect(jsonPath("$.ready").value(true));
    }
    @Test void refusesLocalArchivedDoneAndStaleWorkBeforeCreatingABranch() throws Exception {
        String id=createId();long before=version(id);
        start(id,before,owner).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ISSUE_NOT_PUBLISHED"));
        issueService.publish(projectId,owner.userId(),Long.parseLong(id),remote(7,"설명","open"));
        start(id,before,owner).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ISSUE_CHANGED"));
        issueService.archive(projectId,owner.userId(),Long.parseLong(id),version(id),true);
        start(id,version(id),owner).andExpect(status().isConflict());
        issueService.archive(projectId,owner.userId(),Long.parseLong(id),version(id),false);
        issueService.publish(projectId,owner.userId(),Long.parseLong(id),remote(7,"설명","closed"));
        start(id,version(id),owner).andExpect(status().isConflict());
        verifyNoInteractions(branches);
    }
    @Test void branchCreationRequiresWriterCsrfAndCurrentGithubAccess() throws Exception {
        String id=createId();issueService.publish(projectId,owner.userId(),Long.parseLong(id),remote(7,"설명","open"));
        var viewer=member(ProjectMember.Role.VIEWER);
        start(id,version(id),viewer).andExpect(status().isForbidden());
        mvc.perform(post(base("/issues/"+id+"/branch")).with(as(owner)).contentType("application/json").content("{\"version\":"+version(id)+"}"))
                .andExpect(status().isForbidden());
        long latestVersion=version(id);
        when(oauth.repository(anyString(),eq(sourceId))).thenThrow(new ApiException(404,"GITHUB_RESOURCE_NOT_FOUND","접근 취소"));
        start(id,latestVersion,owner).andExpect(status().isNotFound());
        verifyNoInteractions(branches);
    }
    @Test void aFailedBranchCreateDoesNotMoveTheIssueToInProgress() throws Exception {
        String id=createId();issueService.publish(projectId,owner.userId(),Long.parseLong(id),remote(7,"설명","open"));
        when(branches.ensure(anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenThrow(new ApiException(503,"GITHUB_BRANCH_RESULT_UNKNOWN","다시 확인해 주세요."));
        start(id,version(id),owner).andExpect(status().isServiceUnavailable());
        detail(id).andExpect(jsonPath("$.status").value("TODO")).andExpect(jsonPath("$.workBranch").isEmpty());
    }
    ResultActions start(String id,long version,ZelloPrincipal actor) throws Exception {
        return mvc.perform(post(base("/issues/"+id+"/branch")).with(as(actor)).with(csrf()).contentType("application/json").content("{\"version\":"+version+"}"));
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

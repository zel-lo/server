package com.zello.zello.issue;

import com.zello.zello.api.ApiException;
import com.zello.zello.domain.common.*;
import com.zello.zello.domain.github.*;
import com.zello.zello.domain.issue.*;
import com.zello.zello.domain.project.*;
import com.zello.zello.domain.activity.*;
import com.zello.zello.domain.notification.*;
import com.zello.zello.domain.repository.*;
import com.zello.zello.domain.user.UserRepository;
import com.zello.zello.integration.github.GitHubIssues;
import com.zello.zello.project.ProjectService;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class IssueService {
    private final ProjectService projects;
    private final ProjectRepository projectRows;
    private final GitRepositoryRepository repositories;
    private final IssueRepository issues;
    private final UserRepository users;
    private final GitHubAccountRepository accounts;
    private final ProjectMemberRepository members;
    private final ActivityRepository activities;
    private final NotificationRepository notifications;
    private static final Pattern MARKER = Pattern.compile("\\n?<!-- zello:issue:([A-Za-z0-9-]{16,64}) -->");
    public IssueService(ProjectService projects, ProjectRepository projectRows, GitRepositoryRepository repositories,
                        IssueRepository issues, UserRepository users, GitHubAccountRepository accounts,
                        ProjectMemberRepository members,ActivityRepository activities,NotificationRepository notifications) {
        this.projects=projects; this.projectRows=projectRows; this.repositories=repositories;
        this.issues=issues; this.users=users; this.accounts=accounts;
        this.members=members;this.activities=activities;this.notifications=notifications;
    }
    public List<GitRepository> connected(long projectId,long userId) {
        projects.requireMember(projectId,userId,false);
        return repositories.findByProjectIdOrderByIdAsc(projectId).stream().filter(this::connected).toList();
    }
    public GitRepository repository(long projectId,long userId,long repositoryId,boolean write) {
        if(write) projects.requireWriter(projectId,userId); else projects.requireMember(projectId,userId,false);
        return repositories.findByIdAndProjectId(repositoryId,projectId).filter(this::connected)
                .orElseThrow(() -> new ApiException(404,"REPOSITORY_NOT_FOUND","연결된 저장소를 찾을 수 없어요."));
    }
    private boolean connected(GitRepository r) { return r.getConnectionStatus()!=GitRepository.ConnectionStatus.DISCONNECTED; }
    public Issue find(long projectId,long userId,long issueId,boolean write) {
        if(write) projects.requireWriter(projectId,userId); else projects.requireMember(projectId,userId,false);
        return issues.findDetail(issueId,projectId).filter(i -> connected(i.getRepository())).orElseThrow(this::notFound);
    }
    public View view(Issue i) {
        var repo=i.getRepository();
        return new View(i.getId().toString(),i.getVersion(),i.getIssueKey(),repo.getId().toString(),repo.getOwnerLogin(),repo.getName(),
                i.getGithubNumber(),i.getGithubUrl(),i.getTitle(),i.getDescription(),i.getWorkStatus(),i.getPriority(),i.getSourceType(),
                i.getMeetingDate(),i.getMeetingContext(),i.getAcceptanceCriteria(),i.getGithubState(),
                i.getAssigneeAccount()==null ? null : i.getAssigneeAccount().getLogin(),i.getArchivedAt()!=null,i.getPublishStatus(),i.getLastPublishError(),i.getBoardOrder(),i.getPlannedWeekStart(),i.getCompletedAt());
    }
    public Page list(long projectId,long userId,List<Long> visible,boolean archived,boolean activeOnly,Issue.WorkStatus status,boolean mine,String query,int page,int size,LocalDate week,Long repositoryFilter,String assigneeFilter) {
        projects.requireMember(projectId,userId,false);
        if(visible.isEmpty()) return new Page(List.of(),page,false,0);
        Long assignee=mine ? users.findById(userId).orElseThrow(ProjectService::notFound).getGithubAccountId() : null;
        var result=issues.search(projectId,visible,archived,activeOnly,status,assignee,query,repositoryFilter,"unassigned".equals(assigneeFilter),"unassigned".equals(assigneeFilter) ? "" : assigneeFilter,week,week==null ? null : week.atStartOfDay().minusHours(9),week==null ? null : week.plusWeeks(1).atStartOfDay().minusHours(9),PageRequest.of(page-1,size));
        return new Page(result.map(this::view).getContent(),page,result.hasNext(),result.getTotalElements());
    }
    @Transactional
    public View createLocal(long projectId,long userId,Create input) {
        lock(projectId); var repo=repository(projectId,userId,input.repositoryId(),true);
        validatePlan(input.details());
        if(input.details().source()==Issue.SourceType.GITHUB) throw new ApiException(400,"INVALID_ISSUE_SOURCE","새 작업의 등록 경로를 선택해 주세요.");
        String hash=fingerprint(input);
        var previous=issues.findByPublishRequestKey(input.requestKey());
        if(previous.isPresent()) {
            var row=previous.get();
            if(!row.getRepositoryId().equals(repo.getId()) || !Long.valueOf(userId).equals(row.getCreatedByUserId()) || !hash.equals(row.getCreationHash()))
                throw new ApiException(409,"REQUEST_KEY_CONFLICT","이미 다른 요청에 사용한 요청 키입니다.");
            return view(issues.findDetail(row.getId(),projectId).orElseThrow(this::notFound));
        }
        var user=users.findWithGithubAccountById(userId).orElseThrow(ProjectService::notFound);
        var d=input.details();var assignee=assignee(projectId,d.assignee());
        var row=Issue.builder().repository(repo).createdByUser(user).authorAccount(user.getGithubAccount()).assigneeAccount(assignee)
                .issueKey("Z-"+UUID.randomUUID().toString().replace("-", "").substring(0,28))
                .title(d.title().strip()).description(d.description()).workStatus(d.status()).priority(d.priority())
                .sourceType(d.source()).meetingDate(d.meetingDate()).meetingContext(d.meetingContext()).acceptanceCriteria(d.acceptanceCriteria())
                .publishStatus(PublishStatus.LOCAL).publishRequestKey(input.requestKey()).build();
        row.schedule(d.plannedWeekStart());row.creationHash(hash);issues.saveAndFlush(row);row.assignDisplayKey();issues.flush();
        notifyAssignment(projectId,userId,row,null);
        return view(row);
    }
    @Transactional
    public Reservation claimPublish(long projectId,long userId,long id,long version) {
        lock(projectId);var row=find(projectId,userId,id,true);version(row,version);
        if(row.getArchivedAt()!=null) throw new ApiException(409,"ISSUE_ARCHIVED","보관한 작업은 먼저 복원해 주세요.");
        if(row.getPublishStatus()==PublishStatus.PUBLISHED) return new Reservation(id,true,true);
        boolean retry=row.getPublishStatus()==PublishStatus.PENDING || row.getPublishStatus()==PublishStatus.UNKNOWN;
        if(retry && row.getLastFetchedAt()!=null && row.getLastFetchedAt().isAfter(EntitySupport.now().minusSeconds(30)))
            throw new ApiException(409,"ISSUE_IN_PROGRESS","GitHub 반영을 확인하고 있어요. 잠시 후 다시 확인해 주세요.");
        row.preparePublish(row.getPublishRequestKey()==null ? UUID.randomUUID().toString() : row.getPublishRequestKey());
        return new Reservation(id,retry,false);
    }
    public List<ProjectMember> assignableMembers(long projectId,long userId) {
        projects.requireMember(projectId,userId,false);return members.active(projectId);
    }
    public void validateAssignee(long projectId,String login) {assignee(projectId,login);}
    private GitHubAccount assignee(long projectId,String login) {
        if(login==null) return null;
        return members.active(projectId).stream().map(m -> m.getUser().getGithubAccount())
                .filter(a -> a.getLogin().equalsIgnoreCase(login)).findFirst()
                .orElseThrow(() -> new ApiException(400,"INVALID_ASSIGNEE","담당자는 이 프로젝트의 활성 멤버 중에서 선택해 주세요."));
    }
    private String fingerprint(Create input) {
        var d=input.details();var text=new StringBuilder();
        for(Object value:Arrays.asList(input.repositoryId(),d.title().strip(),d.description(),d.status(),d.priority(),d.source(),
                d.meetingDate(),d.meetingContext(),d.acceptanceCriteria(),d.assignee())) {
            String part=value==null ? null : value.toString();
            text.append(part==null ? -1 : part.length()).append(':');
            if(part!=null) text.append(part);
        }
        if(d.plannedWeekStart()!=null) text.append("week:").append(d.plannedWeekStart());
        return hash(text.toString());
    }
    private String hash(String text) {
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private void notifyAssignment(long projectId,long actorId,Issue row,Long before) {
        if(row.getAssigneeAccountId()==null || row.getAssigneeAccountId().equals(before)) return;
        var recipient=members.active(projectId).stream().filter(m -> m.getUser().getGithubAccountId().equals(row.getAssigneeAccountId())
                && !m.getId().getUserId().equals(actorId)).findFirst();
        if(recipient.isEmpty()) return;
        var actor=users.findWithGithubAccountById(actorId).orElseThrow(ProjectService::notFound);
        var activity=activities.saveAndFlush(Activity.builder().project(projectRows.findById(projectId).orElseThrow())
                .repository(row.getRepository()).actorAccount(actor.getGithubAccount()).issue(row)
                .eventKey("issue-assigned:"+row.getId()+":"+UUID.randomUUID()).eventType("ISSUE_ASSIGNED")
                .title("새 작업이 배정됐어요").summary(row.getTitle()).occurredAt(EntitySupport.now()).build());
        notifications.save(Notification.builder().recipientMember(recipient.get()).activity(activity)
                .title("새 작업이 배정됐어요").body(row.getTitle()).build());
    }
    @Transactional
    public View publish(long projectId,long userId,long id,GitHubIssues.Remote remote) {
        lock(projectId); projects.requireWriter(projectId,userId);
        var row=issues.findById(id).orElseThrow(this::notFound);
        repository(projectId,userId,row.getRepositoryId(),true);
        // sync adopts marker-bearing in-flight rows, so the same source never needs a second row.
        applyRemote(row,remote); issues.flush(); return view(issues.findDetail(row.getId(),projectId).orElseThrow(this::notFound));
    }
    @Transactional
    public void fail(long id,boolean unknown,String message) {
        issues.findById(id).ifPresent(i -> i.publicationFailed(unknown,message));
    }
    public void validatePlan(Details d) {
        if(d.plannedWeekStart()!=null && d.plannedWeekStart().getDayOfWeek()!=DayOfWeek.MONDAY)
            throw new ApiException(400,"INVALID_WEEK","계획 주차는 월요일 날짜로 지정해 주세요.");
        if((d.source()==Issue.SourceType.DAILY || d.source()==Issue.SourceType.WEEKLY) && d.meetingDate()==null)
            throw new ApiException(400,"MEETING_DATE_REQUIRED","회의에서 정한 작업에는 회의 날짜를 넣어주세요.");
    }
    public void version(Issue row,long expected) {
        if(!Objects.equals(row.getVersion(),expected)) throw new ApiException(409,"ISSUE_CHANGED","다른 변경이 있어요. 최신 내용을 확인한 뒤 다시 저장해 주세요.");
    }
    @Transactional
    public View edit(long projectId,long userId,long id,Update input,GitHubIssues.Remote remote) {
        lock(projectId); var row=find(projectId,userId,id,true); version(row,input.version());
        validatePlacement(projectId,userId,id,input.details().status(),input.placement());
        Long before=row.getAssigneeAccountId();
        if(remote!=null) applyRemote(row,remote);
        else {
            row.updateDetails(input.details().title().strip(),input.details().description(),input.details().acceptanceCriteria());
            if(!Objects.equals(row.getAssigneeAccount()==null ? null : row.getAssigneeAccount().getLogin(),input.details().assignee()))
                row.assignTo(assignee(projectId,input.details().assignee()));
        }
        var d=input.details(); row.plan(row.getGithubState()==Issue.GithubState.CLOSED ? Issue.WorkStatus.DONE : d.status(),d.priority(),d.source(),d.meetingDate(),d.meetingContext(),d.acceptanceCriteria());
        row.schedule(d.plannedWeekStart());
        if(input.placement()!=null) place(projectId,row,input.placement());
        issues.flush();notifyAssignment(projectId,userId,row,before);return view(row);
    }

    public Issue validatePlacement(long projectId,long userId,long id,Issue.WorkStatus status,Placement placement) {
        if(placement==null) return null;
        var row=find(projectId,userId,id,true);
        if(row.getArchivedAt()!=null) throw new ApiException(409,"ISSUE_ARCHIVED","보관한 작업은 먼저 복원해 주세요.");
        if(placement.beforeId()==null) return null;
        var target=find(projectId,userId,placement.beforeId(),false);
        if(target.getId()==id || target.getArchivedAt()!=null || target.getWorkStatus()!=status)
            throw new ApiException(409,"BOARD_CHANGED","보드가 변경됐어요. 최신 작업을 확인한 뒤 다시 옮겨주세요.");
        return target;
    }

    private void place(long projectId,Issue row,Placement placement) {
        var column=new ArrayList<>(issues.boardColumn(projectId,row.getWorkStatus()));
        column.removeIf(item -> item.getId().equals(row.getId()));
        int index=placement.beforeId()==null ? column.size() : java.util.stream.IntStream.range(0,column.size())
                .filter(i -> column.get(i).getId().equals(placement.beforeId())).findFirst()
                .orElseThrow(() -> new ApiException(409,"BOARD_CHANGED","보드가 변경됐어요. 다시 불러와 주세요."));
        long left=index==0 ? (column.isEmpty() ? 0 : column.get(0).getBoardOrder()-2048) : column.get(index-1).getBoardOrder();
        long right=index==column.size() ? left+2048 : column.get(index).getBoardOrder();
        // Initial rows share rank zero. Rebalance only when the insertion gap is exhausted.
        if(right-left<2 || left<Long.MIN_VALUE+4096 || right>Long.MAX_VALUE-4096) {
            for(int i=0;i<column.size();i++) column.get(i).placeOnBoard((i+1L)*1024);
            left=index==0 ? 0 : column.get(index-1).getBoardOrder();
            right=index==column.size() ? left+2048 : column.get(index).getBoardOrder();
        }
        row.placeOnBoard(left+(right-left)/2);
    }
    @Transactional
    public void archive(long projectId,long userId,long id,long version,boolean archived) {
        lock(projectId);var row=find(projectId,userId,id,true);version(row,version);
        if(archived) row.archive(EntitySupport.now()); else row.restore();
    }
    @Transactional
    public int sync(long projectId,long userId,long repositoryId,List<GitHubIssues.Remote> fetched) {
        lock(projectId); var repo=repository(projectId,userId,repositoryId,false);
        var rows=issues.findByRepositoryId(repositoryId);
        Map<Long,Issue> existing=new HashMap<>();for(var row:rows) if(row.getGithubIssueId()!=null) existing.put(row.getGithubIssueId(),row);
        for(var remote:fetched) {
            var row=existing.get(remote.id());
            if(row==null && remote.body()!=null) {
                var marker=MARKER.matcher(remote.body());
                if(marker.find()) row=rows.stream().filter(i -> marker.group(1).equals(i.getPublishRequestKey()) && i.getGithubIssueId()==null).findFirst().orElse(null);
            }
            if(row==null) row=issues.save(Issue.builder().repository(repo).issueKey("G-"+repositoryId+"-"+remote.number())
                    .title(remote.title()).sourceType(Issue.SourceType.GITHUB).build());
            if(row.getGithubIssueId()==null && row.getPublishRequestKey()==null) row.assignDisplayKey();
            if(row.getGithubUpdatedAt()!=null && utc(remote.updatedAt()).isBefore(row.getGithubUpdatedAt())) continue;
            applyRemote(row,remote);existing.put(remote.id(),row);
        }
        return fetched.size();
    }
    private void applyRemote(Issue row,GitHubIssues.Remote remote) {
        var assignee=remote.assignees()==null || remote.assignees().isEmpty() ? null : account(remote.assignees().getFirst());
        var content=content(remote.body());
        row.updateDetails(row.getTitle(),row.getDescription(),content.criteria());
        String body=content.description();
        if(remote.labels()!=null) for(var label:remote.labels()) {
            if(label.name()!=null && label.name().matches("priority:(high|medium|low)"))
                row.plan(row.getWorkStatus(),Issue.Priority.valueOf(label.name().substring(9).toUpperCase(Locale.ROOT)),row.getSourceType(),row.getMeetingDate(),row.getMeetingContext(),row.getAcceptanceCriteria());
        }
        row.syncFromRemote(remote.id(),remote.number(),remote.htmlUrl(),remote.title(),body,
                "open".equals(remote.state()) ? Issue.GithubState.OPEN : Issue.GithubState.CLOSED,
                remote.user()==null ? null : account(remote.user()),assignee,utc(remote.updatedAt()),EntitySupport.now());
        row.recordGithubCompletion(remote.closedAt()==null ? null : utc(remote.closedAt()));
    }
    private GitHubAccount account(GitHubIssues.Person p) {
        var type="Bot".equals(p.type()) ? GitHubAccount.AccountType.BOT : GitHubAccount.AccountType.USER;
        var account=accounts.findByGithubId(p.id()).orElseGet(() -> accounts.saveAndFlush(GitHubAccount.builder()
                .githubId(p.id()).login(p.login()).avatarUrl(p.avatarUrl()).accountType(type).build()));
        account.updateProfile(p.login(),account.getDisplayName(),p.avatarUrl());return account;
    }
    public record Content(String description,String criteria) { }
    public static Content content(String raw) {
        var body=cleanBody(raw);
        var section=Pattern.compile("\\n?<!-- zello:criteria:start -->\\n## 완료 조건\\n([\\s\\S]*?)\\n<!-- zello:criteria:end -->").matcher(body);
        if(!section.find()) return new Content(body,"");
        String criteria=section.group(1);return new Content(section.replaceAll("").stripTrailing(),criteria);
    }
    public static String cleanBody(String body) { return body==null ? "" : MARKER.matcher(body).replaceAll("").stripTrailing(); }
    public static String body(String body,String criteria,String key) {
        return (body==null ? "" : body)+(criteria==null || criteria.isBlank() ? "" : "\n\n<!-- zello:criteria:start -->\n## 완료 조건\n"+criteria+"\n<!-- zello:criteria:end -->")
                +(key==null ? "" : "\n<!-- zello:issue:"+key+" -->");
    }
    private void lock(long id) { projectRows.findLockedById(id).orElseThrow(ProjectService::notFound); }
    private LocalDateTime utc(Instant value) {return LocalDateTime.ofInstant(value,ZoneOffset.UTC);}
    private ApiException notFound() {return new ApiException(404,"ISSUE_NOT_FOUND","이슈를 찾을 수 없어요.");}

    public record Details(@NotBlank @Size(max=255) String title,@NotNull @Size(max=50000) String description,
            @NotNull Issue.WorkStatus status,@NotNull Issue.Priority priority,@NotNull Issue.SourceType source,
            LocalDate meetingDate,@Size(max=10000) String meetingContext,@Size(max=10000) String acceptanceCriteria,
            @jakarta.validation.constraints.Pattern(regexp="[A-Za-z0-9][A-Za-z0-9-]{0,99}") String assignee,LocalDate plannedWeekStart) { }
    public record Create(@NotNull @Positive Long repositoryId,@NotNull @jakarta.validation.Valid Details details,
                         @NotBlank @jakarta.validation.constraints.Pattern(regexp="[A-Za-z0-9-]{16,64}") String requestKey) { }
    public record Update(@NotNull @PositiveOrZero Long version,@NotNull @jakarta.validation.Valid Details details,
                         @NotNull Issue.GithubState githubState,@jakarta.validation.Valid Placement placement) { }
    public record Placement(@Positive Long beforeId) { }
    public record Reservation(long id,boolean retry,boolean published) { }
    public record Page(List<View> items,int page,boolean hasNext,long total) { }
    public record View(String id,Long version,String key,String repositoryId,String repositoryOwner,String repositoryName,
            Integer number,String url,String title,String description,Issue.WorkStatus status,Issue.Priority priority,Issue.SourceType source,
            LocalDate meetingDate,String meetingContext,String acceptanceCriteria,Issue.GithubState githubState,String assignee,boolean archived,PublishStatus publishStatus,String lastPublishError,long boardOrder,LocalDate plannedWeekStart,LocalDateTime completedAt) { }
}

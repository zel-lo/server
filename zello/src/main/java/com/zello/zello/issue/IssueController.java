package com.zello.zello.issue;

import com.zello.zello.api.ApiException;
import com.zello.zello.auth.ZelloPrincipal;
import com.zello.zello.domain.issue.Issue;
import com.zello.zello.integration.github.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class IssueController {
    private final IssueService service;
    private final GitHubIssues github;
    private final GitHubOAuthRepositories repositories;
    private final GitHubTokenProvider tokens;
    private final IssueSyncService syncs;
    public IssueController(IssueService service,GitHubIssues github,GitHubOAuthRepositories repositories,GitHubTokenProvider tokens,IssueSyncService syncs) {
        this.service=service;this.github=github;this.repositories=repositories;this.tokens=tokens;this.syncs=syncs;
    }
    @GetMapping("/issues")
    ResponseEntity<IssueService.Page> list(@PathVariable long projectId,@AuthenticationPrincipal ZelloPrincipal user,
            Authentication auth,HttpServletRequest request,@RequestParam(defaultValue="false") boolean archived,
            @RequestParam(required=false) Issue.WorkStatus status,@RequestParam(defaultValue="false") boolean mine,@RequestParam(defaultValue="false") boolean activeOnly,
            @RequestParam(defaultValue="") String query,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="30") int size,
            @RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate week, @RequestParam(required=false) Long repositoryId, @RequestParam(defaultValue="") String assignee) {
        if(page<1 || page>10000 || size<1 || size>100 || query.length()>255 || (repositoryId!=null && repositoryId<1) || (!assignee.isEmpty() && !assignee.equals("unassigned") && !assignee.matches("[A-Za-z0-9][A-Za-z0-9-]{0,99}"))) throw new ApiException(400,"INVALID_PAGE","조회 범위를 확인해 주세요.");
        if(week!=null) com.zello.zello.project.ProjectWeeks.requireWeek(service.projectSchedule(projectId,user.userId()),week);
        var repos=service.connected(projectId,user.userId());
        var visible=new ArrayList<Long>();
        if(!repos.isEmpty()) {
            var token=tokens.oauthToken(auth,request);
            for(var repo:repos) try { repositories.repository(token,repo.getGithubRepoId());visible.add(repo.getId()); }
                catch(ApiException e) { if(e.status()!=404) throw e; }
        }
        return ok(service.list(projectId,user.userId(),visible,archived,activeOnly,status,mine,query.strip(),page,size,week,repositoryId,assignee));
    }
    @GetMapping("/issues/{id}")
    ResponseEntity<IssueService.View> detail(@PathVariable long projectId,@PathVariable long id,@AuthenticationPrincipal ZelloPrincipal user,
            Authentication auth,HttpServletRequest request) {
        var row=service.find(projectId,user.userId(),id,false);
        repositories.repository(tokens.oauthToken(auth,request),row.getRepository().getGithubRepoId());
        return ok(service.view(row));
    }
    @PostMapping("/issues")
    ResponseEntity<IssueService.View> create(@PathVariable long projectId,@Valid @RequestBody IssueService.Create input,
            @AuthenticationPrincipal ZelloPrincipal user,Authentication auth,HttpServletRequest request) {
        var repo=service.repository(projectId,user.userId(),input.repositoryId(),true);
        service.validatePlan(projectId,user.userId(),input.details());service.validateAssignee(projectId,input.details().assignee());
        var token=tokens.oauthToken(auth,request);
        var current=repositories.repository(token,repo.getGithubRepoId());
        if(input.details().assignee()!=null) github.requireAssignee(token,current.owner().login(),current.name(),input.details().assignee());
        var result=service.createLocal(projectId,user.userId(),input);
        return ResponseEntity.created(URI.create("/api/projects/"+projectId+"/issues/"+result.id())).cacheControl(CacheControl.noStore()).body(result);
    }
    @PostMapping("/issues/{id}/publish")
    ResponseEntity<IssueService.View> publish(@PathVariable long projectId,@PathVariable long id,@Valid @RequestBody Publish input,
            @AuthenticationPrincipal ZelloPrincipal user,Authentication auth,HttpServletRequest request) {
        var row=service.find(projectId,user.userId(),id,true);
        var token=tokens.oauthToken(auth,request);
        var current=repositories.repository(token,row.getRepository().getGithubRepoId());
        var reservation=service.claimPublish(projectId,user.userId(),id,input.version());
        if(reservation.published()) return ok(service.view(service.find(projectId,user.userId(),id,false)));
        row=service.find(projectId,user.userId(),id,false);
        GitHubIssues.Remote remote;
        if(reservation.retry()) {
            var marker="<!-- zello:issue:"+row.getPublishRequestKey()+" -->";
            var fetched=github.fetch(token,current.owner().login(),current.name());
            remote=fetched.items().stream().filter(r -> r.body()!=null && r.body().contains(marker)).findFirst().orElse(null);
            if(remote==null) {
                service.fail(id,true,"이전 발행 결과를 확인하지 못했어요.");
                throw new ApiException(503,"GITHUB_RESULT_UNKNOWN","이전 발행 결과를 확인하지 못했어요. GitHub에서 확인 후 다시 확인해 주세요.");
            }
        } else {
            try {
                if(row.getAssigneeAccount()!=null) github.requireAssignee(token,current.owner().login(),current.name(),row.getAssigneeAccount().getLogin());
                remote=github.create(token,current.owner().login(),current.name(),row.getTitle(),
                        IssueService.body(row.getDescription(),row.getAcceptanceCriteria(),row.getPublishRequestKey()),
                        row.getAssigneeAccount()==null ? List.of() : List.of(row.getAssigneeAccount().getLogin()), "priority:"+row.getPriority().name().toLowerCase(Locale.ROOT));
            } catch(ApiException e) {
                service.fail(id,"GITHUB_RESULT_UNKNOWN".equals(e.code()),e.getMessage());throw e;
            }
        }
        return ok(service.publish(projectId,user.userId(),id,remote));
    }
    public record Publish(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero Long version) { }
    @PatchMapping("/issues/{id}")
    ResponseEntity<IssueService.View> update(@PathVariable long projectId,@PathVariable long id,@Valid @RequestBody IssueService.Update input,
            @AuthenticationPrincipal ZelloPrincipal user,Authentication auth,HttpServletRequest request) {
        var row=service.find(projectId,user.userId(),id,true);service.version(row,input.version());service.validatePlan(projectId,user.userId(),input.details());
        var target=service.validatePlacement(projectId,user.userId(),id,input.details().status(),input.placement());
        if(!Objects.equals(row.getAssigneeAccount()==null ? null : row.getAssigneeAccount().getLogin(),input.details().assignee()))
            service.validateAssignee(projectId,input.details().assignee());
        if(input.details().source()==Issue.SourceType.GITHUB && row.getSourceType()!=Issue.SourceType.GITHUB)
            throw new ApiException(400,"INVALID_ISSUE_SOURCE","등록 경로를 확인해 주세요.");
        if(row.getPublishStatus()==com.zello.zello.domain.common.PublishStatus.PENDING || row.getPublishStatus()==com.zello.zello.domain.common.PublishStatus.UNKNOWN)
            throw new ApiException(409,"ISSUE_IN_PROGRESS","이전 GitHub 발행 결과를 먼저 확인해 주세요.");
        var token=tokens.oauthToken(auth,request);
        var repo=repositories.repository(token,row.getRepository().getGithubRepoId());
        if(target!=null && !target.getRepositoryId().equals(row.getRepositoryId()))
            repositories.repository(token,target.getRepository().getGithubRepoId());
        var d=input.details();
        if(d.assignee()!=null) github.requireAssignee(token,repo.owner().login(),repo.name(),d.assignee());
        if(row.getPublishStatus()!=com.zello.zello.domain.common.PublishStatus.PUBLISHED)
            return ok(service.edit(projectId,user.userId(),id,input,null));
        boolean sourceChanged=row.getPriority()!=d.priority() || !row.getTitle().equals(d.title().strip()) || !Objects.equals(row.getDescription(),d.description())
                || !Objects.equals(row.getAcceptanceCriteria(),d.acceptanceCriteria()) || row.getGithubState()!=input.githubState()
                || !Objects.equals(row.getAssigneeAccount()==null ? null : row.getAssigneeAccount().getLogin(),d.assignee());
        if(!sourceChanged) return ok(service.edit(projectId,user.userId(),id,input,null));
        // Read the authoritative assignees first: editing zello's primary assignee must preserve other GitHub assignees.
        var current=github.detail(token,repo.owner().login(),repo.name(),row.getGithubNumber());
        var content=IssueService.content(current.body());
        boolean changed=row.getGithubUpdatedAt()!=null && current.updatedAt().isAfter(row.getGithubUpdatedAt().toInstant(java.time.ZoneOffset.UTC))
                || !current.title().equals(row.getTitle()) || !content.description().equals(row.getDescription()==null ? "" : row.getDescription())
                || !content.criteria().equals(row.getAcceptanceCriteria()==null ? "" : row.getAcceptanceCriteria());
        if(changed) {
            service.sync(projectId,user.userId(),row.getRepositoryId(),List.of(current));
            throw new ApiException(409,"ISSUE_CHANGED","GitHub에서 내용이 바뀌었어요. 최신 내용을 확인한 뒤 저장해 주세요.");
        }
        var assignees=new LinkedHashSet<String>();
        if(d.assignee()!=null) assignees.add(d.assignee());
        if(current.assignees()!=null) current.assignees().stream()
                .filter(p -> row.getAssigneeAccount()==null || !p.login().equals(row.getAssigneeAccount().getLogin()))
                .forEach(p -> assignees.add(p.login()));
        String body=IssueService.body(d.description(),d.acceptanceCriteria(),row.getPublishRequestKey());
        String priority="priority:"+d.priority().name().toLowerCase(Locale.ROOT);
        github.ensurePriorityLabel(token,repo.owner().login(),repo.name(),priority);
        var labels=new ArrayList<String>();
        if(current.labels()!=null) current.labels().stream().map(GitHubIssues.Label::name)
                .filter(label -> !List.of("priority:high","priority:medium","priority:low").contains(label)).forEach(labels::add);
        labels.add(priority);
        var fields=new LinkedHashMap<String,Object>();
        fields.put("title",d.title().strip());fields.put("body",body);fields.put("state",input.githubState().name().toLowerCase(Locale.ROOT));
        if(input.githubState()==Issue.GithubState.CLOSED) fields.put("state_reason","completed");
        fields.put("assignees",new ArrayList<>(assignees));fields.put("labels",labels);
        var remote=github.update(token,repo.owner().login(),repo.name(),row.getGithubNumber(),fields);
        return ok(service.edit(projectId,user.userId(),id,input,remote));
    }
    @DeleteMapping("/issues/{id}")
    ResponseEntity<Void> archive(@PathVariable long projectId,@PathVariable long id,@RequestParam long version,
            @AuthenticationPrincipal ZelloPrincipal user,Authentication auth,HttpServletRequest request) {
        archive(projectId,id,version,user,auth,request,true);return ResponseEntity.noContent().build();
    }
    @PostMapping("/issues/{id}/restore")
    ResponseEntity<Void> restore(@PathVariable long projectId,@PathVariable long id,@RequestParam long version,
            @AuthenticationPrincipal ZelloPrincipal user,Authentication auth,HttpServletRequest request) {
        archive(projectId,id,version,user,auth,request,false);return ResponseEntity.noContent().build();
    }
    private void archive(long projectId,long id,long version,ZelloPrincipal user,Authentication auth,HttpServletRequest request,boolean archived) {
        var row=service.find(projectId,user.userId(),id,true);service.version(row,version);
        repositories.repository(tokens.oauthToken(auth,request),row.getRepository().getGithubRepoId());
        service.archive(projectId,user.userId(),id,version,archived);
    }
    @PostMapping("/issues/sync")
    ResponseEntity<SyncResult> sync(@PathVariable long projectId,@AuthenticationPrincipal ZelloPrincipal user,Authentication auth,HttpServletRequest request) {
        var repos=service.connected(projectId,user.userId());int collected=0,skipped=0;boolean more=false;
        if(!repos.isEmpty()) {
            var token=tokens.oauthToken(auth,request);
            for(var repo:repos) {
                Long run=null;
                try {
                    var current=repositories.repository(token,repo.getGithubRepoId());
                    run=syncs.begin(projectId,user.userId(),repo.getId());
                    for(int page=1;page<=10;page++) {
                        var fetched=github.page(token,current.owner().login(),current.name(),page);
                        collected+=syncs.apply(projectId,user.userId(),repo.getId(),run,page,fetched.items(),fetched.hasNext(),page==10 || !fetched.hasNext());
                        more|=page==10 && fetched.hasNext();
                        if(!fetched.hasNext()) break;
                    }
                } catch(ApiException e) {
                    if(run!=null) syncs.fail(run,e.getMessage());
                    if(e.status()!=404) throw e;
                    skipped++;
                } catch(RuntimeException e) {
                    if(run!=null) syncs.fail(run,"저장 중 중단됐어요. 다시 수집해 주세요.");
                    throw e;
                }
            }
        }
        return ok(new SyncResult(collected,skipped,more));
    }
    @GetMapping("/repositories/{repositoryId}/assignees")
    ResponseEntity<GitHubGateway.Page<GitHubIssues.Person>> assignees(@PathVariable long projectId,@PathVariable long repositoryId,
            @RequestParam(defaultValue="1") int page,@AuthenticationPrincipal ZelloPrincipal user,Authentication auth,HttpServletRequest request) {
        if(page<1 || page>100) throw new ApiException(400,"INVALID_PAGE","페이지 범위를 확인해 주세요.");
        var row=service.repository(projectId,user.userId(),repositoryId,false);var token=tokens.oauthToken(auth,request);
        var repo=repositories.repository(token,row.getGithubRepoId());
        var allowed=github.assignees(token,repo.owner().login(),repo.name(),page);
        var members=service.assignableMembers(projectId,user.userId());
        return ok(new GitHubGateway.Page<>(allowed.items().stream().filter(person -> members.stream().anyMatch(m -> m.getUser().getGithubAccount().getGithubId()==person.id())).toList(),allowed.hasNext(),page));
    }
    private <T> ResponseEntity<T> ok(T value) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    public record SyncResult(int collected,int skippedRepositories,boolean hasMore) { }
}

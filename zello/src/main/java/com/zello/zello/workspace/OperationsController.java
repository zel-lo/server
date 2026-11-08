package com.zello.zello.workspace;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.zello.zello.api.ApiException;
import com.zello.zello.auth.ZelloPrincipal;
import com.zello.zello.repository.RepositoryService;
import com.zello.zello.integration.github.GitHubOAuthRepositories;
import com.zello.zello.integration.github.GitHubTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.*;

@RestController
@RequestMapping("/api/projects/{projectId}/repositories/{repositoryId}/operations")
public class OperationsController {
    private final RepositoryService repositories;
    private final GitHubOAuthRepositories github;
    private final GitHubTokenProvider tokens;
    private final RestClient client;
    public OperationsController(RepositoryService repositories,GitHubOAuthRepositories github,GitHubTokenProvider tokens,@Qualifier("githubApiRestClient") RestClient client) { this.repositories=repositories;this.github=github;this.tokens=tokens;this.client=client; }
    @GetMapping
    Object read(@PathVariable long projectId,@PathVariable String repositoryId,@AuthenticationPrincipal ZelloPrincipal p,Authentication auth,HttpServletRequest request) {
        var repo=repositories.list(projectId,p.userId()).stream().filter(r->r.id().equals(repositoryId)).findFirst().orElseThrow(()->new ApiException(404,"REPOSITORY_NOT_FOUND","연결된 저장소를 찾을 수 없어요."));
        String token=tokens.oauthToken(auth,request);
        var remote=github.repository(token,Long.parseLong(repo.githubRepositoryId()));
        String owner=remote.owner().login(),name=remote.name();
        List<Run> runs=List.of(); List<DeploymentView> deployments=new ArrayList<>(); String buildError=null,deploymentError=null;
        try { var response=client.get().uri("/repos/{owner}/{repo}/actions/runs?per_page=10",owner,name).headers(h->h.setBearerAuth(token)).retrieve().body(Runs.class); if(response!=null && response.runs()!=null) runs=response.runs(); }
        catch(RestClientException e) { buildError="빌드 기록을 조회하지 못했어요. GitHub 권한과 호출 한도를 확인해 주세요."; }
        try {
            var response=client.get().uri("/repos/{owner}/{repo}/deployments?per_page=10",owner,name).headers(h->h.setBearerAuth(token)).retrieve().body(Deployment[].class);
            if(response!=null) for(var d:response) {
                Status latest=null;
                try { var statuses=client.get().uri("/repos/{owner}/{repo}/deployments/{id}/statuses?per_page=1",owner,name,d.id()).headers(h->h.setBearerAuth(token)).retrieve().body(Status[].class); if(statuses!=null && statuses.length>0) latest=statuses[0]; }
                catch(RestClientException e) { /* A missing status stays unknown, never inferred from a build. */ }
                deployments.add(new DeploymentView(d.environment(),d.sha(),latest==null?"unknown":latest.state(),latest==null?null:latest.createdAt()));
            }
        } catch(RestClientException e) { deploymentError="배포 기록을 조회하지 못했어요. GitHub 권한과 호출 한도를 확인해 주세요."; }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Operations(runs,deployments,buildError,deploymentError));
    }
    record Operations(List<Run> builds,List<DeploymentView> deployments,String buildError,String deploymentError) { }
    record DeploymentView(String environment,String sha,String status,String updatedAt) { }
    @JsonIgnoreProperties(ignoreUnknown=true) record Runs(@JsonProperty("workflow_runs") List<Run> runs) { }
    @JsonIgnoreProperties(ignoreUnknown=true) record Run(String name,String status,String conclusion,@JsonProperty("head_branch") String branch,@JsonProperty("updated_at") String updatedAt) { }
    @JsonIgnoreProperties(ignoreUnknown=true) record Deployment(long id,String environment,String sha) { }
    @JsonIgnoreProperties(ignoreUnknown=true) record Status(String state,@JsonProperty("created_at") String createdAt) { }
}

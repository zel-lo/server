package com.zello.zello.integration.github;

import com.fasterxml.jackson.annotation.*;
import com.zello.zello.api.ApiException;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class GitHubIssues {
    private final RestClient client;
    public GitHubIssues(@Qualifier("githubApiRestClient") RestClient client) { this.client = client; }

    public GitHubGateway.Page<Remote> page(String token,String owner,String repo,int page) {
        var response=get(token,Remote[].class,"/repos/{owner}/{repo}/issues?state=all&sort=updated&direction=desc&per_page=100&page={page}",owner,repo,page);
        if(response.getBody()==null) throw invalid();
        var items=new ArrayList<Remote>();
        for(var remote:response.getBody()) {
            if(remote!=null && remote.pullRequest()!=null) continue;
            validate(remote);items.add(remote);
        }
        return new GitHubGateway.Page<>(items,hasNext(response),page);
    }
    public Fetched fetch(String token, String owner, String repo) {
        var items=new ArrayList<Remote>();boolean more=false;
        for(int page=1;page<=10;page++) {
            var result=page(token,owner,repo,page);items.addAll(result.items());more=result.hasNext();
            if(!more) break;
        }
        return new Fetched(items,more);
    }
    public GitHubGateway.Page<Person> assignees(String token, String owner, String repo, int page) {
        var response = get(token, Person[].class, "/repos/{owner}/{repo}/assignees?per_page=100&page={page}", owner, repo, page);
        if (response.getBody() == null) throw invalid();
        for(var person : response.getBody()) validate(person);
        return new GitHubGateway.Page<>(Arrays.asList(response.getBody()),hasNext(response),page);
    }
    public void requireAssignee(String token,String owner,String repo,String login) {
        get(token,Void.class,"/repos/{owner}/{repo}/assignees/{login}",owner,repo,login);
    }
    public void ensurePriorityLabel(String token,String owner,String repo,String name) {
        try {get(token,Label.class,"/repos/{owner}/{repo}/labels/{name}",owner,repo,name);}
        catch(ApiException e) {
            if(e.status()!=404) throw e;
            try {
                client.post().uri("/repos/{owner}/{repo}/labels",owner,repo).headers(h -> h.setBearerAuth(token))
                        .body(Map.of("name",name,"color",name.endsWith("high") ? "d73a4a" : name.endsWith("low") ? "0e8a16" : "fbca04"))
                        .retrieve().toBodilessEntity();
            } catch(RestClientResponseException error) {
                if(error.getStatusCode().value()==422) {
                    get(token,Label.class,"/repos/{owner}/{repo}/labels/{name}",owner,repo,name);
                } else throw upstream(error);
            } catch(RestClientException error) {throw new ApiException(502,"GITHUB_UNAVAILABLE","우선순위 라벨을 준비하지 못했어요. 다시 시도해 주세요.");}
        }
    }
    public Remote detail(String token, String owner, String repo, int number) {
        var remote = get(token, Remote.class, "/repos/{owner}/{repo}/issues/{number}", owner, repo, number).getBody();
        validate(remote);
        if (remote.number()!=number) throw invalid();
        return remote;
    }
    public Remote create(String token, String owner, String repo, String title, String body, List<String> assignees, String priority) {
        ensurePriorityLabel(token,owner,repo,priority);
        return write(true, token, owner, repo, 0, Map.of("title",title,"body",body,"assignees",assignees,"labels",List.of(priority)));
    }
    public Remote update(String token, String owner, String repo, int number, Map<String,Object> fields) {
        return write(false, token, owner, repo, number, fields);
    }
    private Remote write(boolean create, String token, String owner, String repo, int number, Map<String,Object> fields) {
        try {
            var spec = create ? client.post().uri("/repos/{owner}/{repo}/issues", owner, repo)
                    : client.patch().uri("/repos/{owner}/{repo}/issues/{number}", owner, repo, number);
            var remote = spec.headers(h -> h.setBearerAuth(token)).body(fields).retrieve().body(Remote.class);
            try { validate(remote); } catch(ApiException e) { throw unknown(); }
            if (!create && remote.number()!=number) throw unknown();
            return remote;
        } catch(RestClientResponseException e) {
            if(e.getStatusCode().value() >= 500) throw unknown();
            throw upstream(e);
        } catch(RestClientException e) { throw unknown(); }
    }
    private <T> ResponseEntity<T> get(String token, Class<T> type, String path, Object... args) {
        try { return client.get().uri(path,args).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(type); }
        catch(RestClientResponseException e) { throw upstream(e); }
        catch(RestClientException e) { throw new ApiException(502,"GITHUB_UNAVAILABLE","GitHub에 연결하지 못했어요. 잠시 후 다시 시도해 주세요."); }
    }
    private ApiException upstream(RestClientResponseException e) {
        return switch(e.getStatusCode().value()) {
            case 401 -> new ApiException(401,"GITHUB_REAUTH_REQUIRED","GitHub에 다시 로그인해 주세요.");
            case 403,429 -> new ApiException(403,"GITHUB_ACCESS_RESTRICTED","저장소의 이슈 권한, 조직 승인 또는 API 호출 한도를 확인해 주세요.");
            case 404,410 -> new ApiException(404,"GITHUB_RESOURCE_NOT_FOUND","접근할 수 없거나 이슈 기능이 꺼진 저장소입니다.");
            case 422 -> new ApiException(422,"ISSUE_REJECTED","이슈 제목과 담당자를 확인해 주세요. GitHub가 요청을 승인하지 않았어요.");
            default -> new ApiException(502,"GITHUB_UNAVAILABLE","GitHub 응답을 확인하지 못했어요.");
        };
    }
    private void validate(Remote r) {
        if(r==null || r.id()<=0 || r.number()<=0 || r.pullRequest()!=null || r.title()==null || r.title().isBlank()
                || r.title().length()>255 || r.htmlUrl()==null || !r.htmlUrl().startsWith("https://github.com/") || r.htmlUrl().length()>2048
                || !List.of("open","closed").contains(r.state()==null ? "" : r.state()) || r.updatedAt()==null) throw invalid();
        if(r.user()!=null) validate(r.user());
        if(r.assignees()!=null) r.assignees().forEach(this::validate);
    }
    private void validate(Person p) {
        if(p==null || p.id()<=0 || p.login()==null || !p.login().matches("[A-Za-z0-9][A-Za-z0-9\\[\\]-]{0,99}")) throw invalid();
    }
    private boolean hasNext(ResponseEntity<?> r) { return r.getHeaders().getOrEmpty("Link").stream().anyMatch(v -> v.contains("rel=\"next\"")); }
    private ApiException invalid() { return new ApiException(502,"INVALID_GITHUB_RESPONSE","GitHub 이슈 정보를 확인하지 못했어요."); }
    private ApiException unknown() { return new ApiException(503,"GITHUB_RESULT_UNKNOWN","GitHub 반영 여부를 확인 중이에요. 같은 요청으로 다시 확인해 주세요."); }
    public record Fetched(List<Remote> items, boolean hasMore) { }
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Person(long id, String login, String type, @JsonProperty("avatar_url") String avatarUrl) { }
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Label(String name) { }
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Remote(long id, int number, @JsonProperty("html_url") String htmlUrl, String title, String body, String state,
                         Person user, List<Person> assignees, @JsonProperty("updated_at") Instant updatedAt,
                         @JsonProperty("pull_request") Map<String,Object> pullRequest,List<Label> labels) { }
}

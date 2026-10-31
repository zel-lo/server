package com.zello.zello.issue;

import com.zello.zello.domain.common.EntitySupport;
import com.zello.zello.domain.repository.*;
import com.zello.zello.domain.issue.IssueRepository;
import com.zello.zello.integration.github.GitHubIssues;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Each page and its checkpoint commit together. HTTP calls never hold a transaction. */
@Service
public class IssueSyncService {
    private final IssueService issues;
    private final IssueRepository issueRows;
    private final RepositorySyncRunRepository runs;
    public IssueSyncService(IssueService issues,IssueRepository issueRows,RepositorySyncRunRepository runs) {
        this.issues=issues;this.issueRows=issueRows;this.runs=runs;
    }
    @Transactional
    public long begin(long projectId,long userId,long repositoryId) {
        var repo=issues.repository(projectId,userId,repositoryId,false);
        var initial=issueRows.findByRepositoryId(repositoryId).stream().noneMatch(i -> i.getGithubIssueId()!=null);
        var run=RepositorySyncRun.builder().repository(repo).runKind(initial ? RepositorySyncRun.RunKind.INITIAL : RepositorySyncRun.RunKind.MANUAL)
                .syncScope(RepositorySyncRun.SyncScope.ISSUES).emitNotifications(false).checkpoint(Map.of("nextPage",1)).build();
        run.start(EntitySupport.now());return runs.saveAndFlush(run).getId();
    }
    @Transactional
    public int apply(long projectId,long userId,long repositoryId,long runId,int page,List<GitHubIssues.Remote> fetched,boolean more,boolean complete) {
        var run=runs.findById(runId).orElseThrow();
        if(!run.getRepositoryId().equals(repositoryId)) throw new IllegalStateException("Mismatched sync repository");
        int count=issues.sync(projectId,userId,repositoryId,fetched);
        var now=EntitySupport.now();run.progress(page,count,more,now);if(complete) run.complete(now);
        return count;
    }
    @Transactional
    public void fail(long id,String message) {runs.findById(id).ifPresent(run -> run.fail(EntitySupport.now(),message));}
}

package com.zello.zello.issue;

import com.zello.zello.api.ApiException;
import com.zello.zello.domain.common.PublishStatus;
import com.zello.zello.domain.issue.Issue;
import com.zello.zello.domain.issue.IssueRepository;
import com.zello.zello.domain.project.ProjectRepository;
import com.zello.zello.project.ProjectService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class IssueBranchService {
    private final IssueService issues;
    private final IssueRepository rows;
    private final ProjectRepository projects;
    private final IssuePullRequestService links;
    public IssueBranchService(IssueService issues, IssueRepository rows, ProjectRepository projects, IssuePullRequestService links) {
        this.issues = issues; this.rows = rows; this.projects = projects; this.links = links;
    }
    public String name(Issue issue) {
        requirePublished(issue);
        return issue.getWorkBranch() == null ? "feat/" + issue.getGithubNumber() : issue.getWorkBranch();
    }
    public void requireStart(Issue issue, long version) {
        if (issue.getWorkBranch() == null) issues.version(issue, version);
        requirePublished(issue);
        if (issue.getArchivedAt() != null || issue.getWorkStatus() == Issue.WorkStatus.DONE || issue.getGithubState() == Issue.GithubState.CLOSED)
            throw new ApiException(409, "ISSUE_NOT_STARTABLE", "보관되었거나 완료한 작업은 다시 열고 작업을 시작해 주세요.");
    }
    private void requirePublished(Issue issue) {
        if (issue.getPublishStatus() != PublishStatus.PUBLISHED || issue.getGithubNumber() == null)
            throw new ApiException(409, "ISSUE_NOT_PUBLISHED", "GitHub에 이슈를 발행한 뒤 작업을 시작해 주세요.");
    }
    @Transactional
    public IssueService.View started(long projectId, long userId, long issueId, String branch, String base) {
        projects.findLockedById(projectId).orElseThrow(ProjectService::notFound);
        var issue = issues.find(projectId, userId, issueId, true);
        // Other people may edit the issue while GitHub is running. Preserve those edits and only attach the branch.
        requireStart(issue, issue.getVersion());
        if (!name(issue).equals(branch)) throw new ApiException(409, "ISSUE_CHANGED", "작업 정보가 바뀌었어요. 최신 내용을 확인해 주세요.");
        issue.startWork(branch, base);
        links.recalculate(issueId);
        rows.flush();
        return issues.view(issue);
    }
}

package com.zello.zello.domain.issue;

import com.zello.zello.domain.common.*;
import com.zello.zello.domain.pullrequest.PullRequest;
import com.zello.zello.domain.user.User;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "issue_pull_requests",
        indexes = {
                @Index(name = "idx_issue_pull_requests_pr_issue", columnList = "pull_request_id, issue_id")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IssuePullRequest extends CreatedAtEntity {
    @EmbeddedId
    private IssuePullRequestId id;

    @Column(name = "repository_id", nullable = false)
    private Long repositoryId;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(value = {
            @JoinColumn(name = "issue_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "repository_id", referencedColumnName = "repository_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_issue_pull_requests_issue"))
    private Issue issue;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(value = {
            @JoinColumn(name = "pull_request_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "repository_id", referencedColumnName = "repository_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_issue_pull_requests_pr"))
    private PullRequest pullRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_issue_pull_requests_creator"))
    private User createdByUser;

    @Builder
    private IssuePullRequest(
            Issue issue,
            PullRequest pullRequest,
            User createdByUser) {
        this.issue = EntitySupport.required(issue, "issue");
        this.pullRequest = EntitySupport.required(pullRequest, "pullRequest");
        this.createdByUser = createdByUser;
        this.repositoryId = EntitySupport.required(issue.getRepositoryId(), "persisted issue.repository_id");
        this.createdByUserId = createdByUser == null ? null : EntitySupport.required(createdByUser.getId(), "persisted createdByUser.id");
        this.id = new IssuePullRequestId(EntitySupport.required(issue.getId(), "persisted issue.id"), EntitySupport.required(pullRequest.getId(), "persisted pullRequest.id"));
        EntitySupport.same(this.repositoryId, pullRequest.getRepositoryId(), "repository_id");
    }

}

package com.zello.zello.domain.pullrequest;

import com.zello.zello.domain.common.*;
import com.zello.zello.domain.github.GitHubAccount;
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
@Table(name = "pull_request_reviews",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_pull_request_reviews_pr_github", columnNames = {"pull_request_id", "github_review_id"})
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PullRequestReview extends IdentityEntity {
    @Column(name = "pull_request_id", nullable = false)
    private Long pullRequestId;

    @Column(name = "reviewer_account_id")
    private Long reviewerAccountId;

    @Column(name = "github_review_id", nullable = false)
    private Long githubReviewId;

    @Column(name = "state", nullable = false, length = 30)
    private String state;

    @Column(name = "body", columnDefinition = "text")
    private String body;

    @Column(name = "commit_sha", length = 64)
    private String commitSha;

    @Column(name = "submitted_at", columnDefinition = "datetime(6)")
    private LocalDateTime submittedAt;

    @Column(name = "last_fetched_at", columnDefinition = "datetime(6)")
    private LocalDateTime lastFetchedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pull_request_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_pull_request_reviews_pr"))
    private PullRequest pullRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_account_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_pull_request_reviews_account"))
    private GitHubAccount reviewerAccount;

    @Builder
    private PullRequestReview(
            PullRequest pullRequest,
            GitHubAccount reviewerAccount,
            Long githubReviewId,
            String state,
            String body,
            String commitSha,
            LocalDateTime submittedAt,
            LocalDateTime lastFetchedAt) {
        this.pullRequest = EntitySupport.required(pullRequest, "pullRequest");
        this.reviewerAccount = reviewerAccount;
        this.pullRequestId = EntitySupport.required(pullRequest.getId(), "persisted pullRequest.id");
        this.reviewerAccountId = reviewerAccount == null ? null : EntitySupport.required(reviewerAccount.getId(), "persisted reviewerAccount.id");
        this.githubReviewId = EntitySupport.required(githubReviewId, "githubReviewId");
        this.state = EntitySupport.text(state, "state");
        this.body = body;
        this.commitSha = commitSha;
        this.submittedAt = submittedAt;
        this.lastFetchedAt = lastFetchedAt;
    }

}

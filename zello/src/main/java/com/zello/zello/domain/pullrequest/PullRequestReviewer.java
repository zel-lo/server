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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "pull_request_reviewers",
        indexes = {
                @Index(name = "idx_pull_request_reviewers_account_status", columnList = "reviewer_account_id, request_status, pull_request_id")
        },
        check = {
                @CheckConstraint(name = "ck_pull_request_reviewers_status", constraint = "request_status IN ('REQUESTED', 'COMPLETED', 'REMOVED')")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PullRequestReviewer {
    @EmbeddedId
    private PullRequestReviewerId id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "request_status", nullable = false, length = 20)
    private RequestStatus requestStatus;

    @Column(name = "requested_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime requestedAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pull_request_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_pull_request_reviewers_pr"))
    private PullRequest pullRequest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reviewer_account_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_pull_request_reviewers_account"))
    private GitHubAccount reviewerAccount;

    @Builder
    private PullRequestReviewer(
            PullRequest pullRequest,
            GitHubAccount reviewerAccount,
            RequestStatus requestStatus,
            LocalDateTime requestedAt,
            LocalDateTime updatedAt) {
        this.pullRequest = EntitySupport.required(pullRequest, "pullRequest");
        this.reviewerAccount = EntitySupport.required(reviewerAccount, "reviewerAccount");
        this.id = new PullRequestReviewerId(EntitySupport.required(pullRequest.getId(), "persisted pullRequest.id"), EntitySupport.required(reviewerAccount.getId(), "persisted reviewerAccount.id"));
        this.requestStatus = Objects.requireNonNullElseGet(requestStatus, () -> RequestStatus.REQUESTED);
        this.requestedAt = Objects.requireNonNullElseGet(requestedAt, () -> EntitySupport.now());
        this.updatedAt = Objects.requireNonNullElseGet(updatedAt, () -> EntitySupport.now());
    }

    public void requestAgain(LocalDateTime requestedAt) {
        this.requestedAt = EntitySupport.required(requestedAt, "requestedAt");
        this.requestStatus = RequestStatus.REQUESTED;
        this.updatedAt = EntitySupport.now();
    }

    /** GitHub의 검토 요청 목록에서 빠졌을 때(리뷰 제출, 요청 취소) 대기 목록에서 뺀다. */
    public void remove() {
        this.requestStatus = RequestStatus.REMOVED;
        this.updatedAt = EntitySupport.now();
    }

    public void completeReview() {
        this.requestStatus = RequestStatus.COMPLETED;
        this.updatedAt = EntitySupport.now();
    }

    public enum RequestStatus {
        REQUESTED, COMPLETED, REMOVED
    }

}

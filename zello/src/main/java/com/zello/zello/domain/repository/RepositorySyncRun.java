package com.zello.zello.domain.repository;

import com.zello.zello.domain.common.*;
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
@Table(name = "repository_sync_runs",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_repository_sync_runs_scope", columnNames = {"id", "repository_id"})
        },
        indexes = {
                @Index(name = "idx_repository_sync_runs_repository_created", columnList = "repository_id, created_at, id"),
                @Index(name = "idx_repository_sync_runs_status_retry", columnList = "status, next_retry_at, id")
        },
        check = {
                @CheckConstraint(name = "ck_repository_sync_runs_kind", constraint = "run_kind IN ('INITIAL', 'PERIODIC', 'MANUAL')"),
                @CheckConstraint(name = "ck_repository_sync_runs_scope", constraint = "sync_scope IN ('ALL', 'ISSUES', 'PULL_REQUESTS', 'REVIEWS', 'BUILDS', 'DEPLOYMENTS')"),
                @CheckConstraint(name = "ck_repository_sync_runs_status", constraint = "status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')"),
                @CheckConstraint(name = "ck_repository_sync_runs_counts", constraint = "attempt_no > 0 AND items_processed >= 0"),
                @CheckConstraint(name = "ck_repository_sync_runs_initial", constraint = "run_kind <> 'INITIAL' OR emit_notifications = FALSE"),
                @CheckConstraint(name = "ck_repository_sync_runs_lifecycle", constraint = "(status = 'PENDING' AND started_at IS NULL AND finished_at IS NULL) OR (status = 'RUNNING' AND started_at IS NOT NULL AND finished_at IS NULL) OR (status IN ('SUCCEEDED', 'FAILED') AND started_at IS NOT NULL AND finished_at IS NOT NULL AND finished_at >= started_at)")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepositorySyncRun extends IdentityEntity {
    @Column(name = "repository_id", nullable = false)
    private Long repositoryId;

    @Column(name = "retry_of_id")
    private Long retryOfId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "run_kind", nullable = false, length = 20)
    private RunKind runKind;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "sync_scope", nullable = false, length = 30)
    private SyncScope syncScope;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "attempt_no", nullable = false)
    private Integer attemptNo;

    @Column(name = "emit_notifications", nullable = false)
    private Boolean emitNotifications;

    @Column(name = "source_since_at", columnDefinition = "datetime(6)")
    private LocalDateTime sourceSinceAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "checkpoint", columnDefinition = "json")
    private Map<String, Object> checkpoint;

    @Column(name = "items_processed", nullable = false)
    private Long itemsProcessed;

    @Column(name = "started_at", columnDefinition = "datetime(6)")
    private LocalDateTime startedAt;

    @Column(name = "heartbeat_at", columnDefinition = "datetime(6)")
    private LocalDateTime heartbeatAt;

    @Column(name = "finished_at", columnDefinition = "datetime(6)")
    private LocalDateTime finishedAt;

    @Column(name = "next_retry_at", columnDefinition = "datetime(6)")
    private LocalDateTime nextRetryAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_repository_sync_runs_repository"))
    private GitRepository repository;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
            @JoinColumn(name = "retry_of_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "repository_id", referencedColumnName = "repository_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_repository_sync_runs_retry"))
    private RepositorySyncRun retryOf;

    @Builder
    private RepositorySyncRun(
            GitRepository repository,
            RepositorySyncRun retryOf,
            RunKind runKind,
            SyncScope syncScope,
            Status status,
            Integer attemptNo,
            Boolean emitNotifications,
            LocalDateTime sourceSinceAt,
            Map<String, Object> checkpoint,
            Long itemsProcessed,
            LocalDateTime startedAt,
            LocalDateTime heartbeatAt,
            LocalDateTime finishedAt,
            LocalDateTime nextRetryAt,
            String lastError) {
        this.repository = EntitySupport.required(repository, "repository");
        this.retryOf = retryOf;
        this.repositoryId = EntitySupport.required(repository.getId(), "persisted repository.id");
        this.retryOfId = retryOf == null ? null : EntitySupport.required(retryOf.getId(), "persisted retryOf.id");
        if (retryOf != null) EntitySupport.same(this.repositoryId, retryOf.getRepositoryId(), "repository_id");
        this.runKind = Objects.requireNonNullElseGet(runKind, () -> RunKind.MANUAL);
        this.syncScope = Objects.requireNonNullElseGet(syncScope, () -> SyncScope.ALL);
        this.status = Objects.requireNonNullElseGet(status, () -> Status.PENDING);
        this.attemptNo = Objects.requireNonNullElseGet(attemptNo, () -> 1);
        this.emitNotifications = Objects.requireNonNullElseGet(emitNotifications, () -> false);
        this.sourceSinceAt = sourceSinceAt;
        this.checkpoint = (checkpoint == null ? null : new java.util.LinkedHashMap<>(checkpoint));
        this.itemsProcessed = Objects.requireNonNullElseGet(itemsProcessed, () -> 0L);
        this.startedAt = startedAt;
        this.heartbeatAt = heartbeatAt;
        this.finishedAt = finishedAt;
        this.nextRetryAt = nextRetryAt;
        this.lastError = lastError;
    }

    public void start(LocalDateTime startedAt) {
        if (status != Status.PENDING) throw new IllegalStateException("Sync is not pending");
        this.startedAt = EntitySupport.required(startedAt, "startedAt");
        this.heartbeatAt = startedAt;
        this.status = Status.RUNNING;
    }

    public void complete(LocalDateTime finishedAt) {
        if (status != Status.RUNNING) throw new IllegalStateException("Sync is not running");
        this.finishedAt = EntitySupport.required(finishedAt, "finishedAt");
        if (finishedAt.isBefore(startedAt)) throw new IllegalArgumentException("Invalid finish time");
        this.status = Status.SUCCEEDED;
    }

    public void progress(int page,long count,boolean hasMore,LocalDateTime now) {
        if(status!=Status.RUNNING) throw new IllegalStateException("Sync is not running");
        this.itemsProcessed += count;
        this.heartbeatAt = now;
        this.checkpoint = Map.of("lastCompletedPage",page,"nextPage",page+1,"hasMore",hasMore);
    }
    public void fail(LocalDateTime now,String message) {
        if(status!=Status.RUNNING) return;
        this.status = Status.FAILED;
        this.finishedAt = now;
        this.lastError = message;
    }

    public enum RunKind {
        INITIAL, PERIODIC, MANUAL
    }

    public enum SyncScope {
        ALL, ISSUES, PULL_REQUESTS, REVIEWS, BUILDS, DEPLOYMENTS
    }

    public enum Status {
        PENDING, RUNNING, SUCCEEDED, FAILED
    }

}

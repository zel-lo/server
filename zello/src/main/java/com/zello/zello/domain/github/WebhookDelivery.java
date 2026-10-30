package com.zello.zello.domain.github;

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
@Table(name = "webhook_deliveries",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_webhook_deliveries_github_id", columnNames = {"github_delivery_id"})
        },
        indexes = {
                @Index(name = "idx_webhook_deliveries_processing", columnList = "processing_status, next_retry_at, received_at")
        },
        check = {
                @CheckConstraint(name = "ck_webhook_deliveries_status", constraint = "processing_status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED')"),
                @CheckConstraint(name = "ck_webhook_deliveries_attempts", constraint = "attempt_count >= 0"),
                @CheckConstraint(name = "ck_webhook_deliveries_processed", constraint = "(processing_status = 'PROCESSED' AND processed_at IS NOT NULL) OR (processing_status <> 'PROCESSED' AND processed_at IS NULL)"),
                @CheckConstraint(name = "ck_webhook_deliveries_lease", constraint = "processing_status <> 'PROCESSING' OR (processing_started_at IS NOT NULL AND lease_expires_at IS NOT NULL AND lease_expires_at > processing_started_at)")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookDelivery extends IdentityEntity {
    @Column(name = "github_delivery_id", nullable = false, length = 64)
    private String githubDeliveryId;

    @Column(name = "installation_id")
    private Long installationId;

    @Column(name = "event_name", nullable = false, length = 100)
    private String eventName;

    @Column(name = "action", length = 100)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "json")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "processing_status", nullable = false, length = 20)
    private ProcessingStatus processingStatus;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "received_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime receivedAt;

    @Column(name = "processing_started_at", columnDefinition = "datetime(6)")
    private LocalDateTime processingStartedAt;

    @Column(name = "lease_expires_at", columnDefinition = "datetime(6)")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "next_retry_at", columnDefinition = "datetime(6)")
    private LocalDateTime nextRetryAt;

    @Column(name = "processed_at", columnDefinition = "datetime(6)")
    private LocalDateTime processedAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "installation_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_webhook_deliveries_installation"))
    private GitHubInstallation installation;

    @Builder
    private WebhookDelivery(
            GitHubInstallation installation,
            String githubDeliveryId,
            String eventName,
            String action,
            Map<String, Object> payload,
            ProcessingStatus processingStatus,
            Integer attemptCount,
            LocalDateTime receivedAt,
            LocalDateTime processingStartedAt,
            LocalDateTime leaseExpiresAt,
            LocalDateTime nextRetryAt,
            LocalDateTime processedAt,
            String lastError) {
        this.installation = installation;
        this.installationId = installation == null ? null : EntitySupport.required(installation.getId(), "persisted installation.id");
        this.githubDeliveryId = EntitySupport.text(githubDeliveryId, "githubDeliveryId");
        this.eventName = EntitySupport.text(eventName, "eventName");
        this.action = action;
        this.payload = (EntitySupport.required(payload, "payload") == null ? null : new java.util.LinkedHashMap<>(EntitySupport.required(payload, "payload")));
        this.processingStatus = Objects.requireNonNullElseGet(processingStatus, () -> ProcessingStatus.RECEIVED);
        this.attemptCount = Objects.requireNonNullElseGet(attemptCount, () -> 0);
        this.receivedAt = Objects.requireNonNullElseGet(receivedAt, () -> EntitySupport.now());
        this.processingStartedAt = processingStartedAt;
        this.leaseExpiresAt = leaseExpiresAt;
        this.nextRetryAt = nextRetryAt;
        this.processedAt = processedAt;
        this.lastError = lastError;
    }

    public void startProcessing(LocalDateTime startedAt, LocalDateTime leaseExpiresAt) {
        EntitySupport.required(startedAt, "startedAt");
        EntitySupport.required(leaseExpiresAt, "leaseExpiresAt");
        if (!leaseExpiresAt.isAfter(startedAt)) throw new IllegalArgumentException("Invalid lease");
        if (processingStatus == ProcessingStatus.PROCESSED) throw new IllegalStateException("Already processed");
        this.processingStartedAt = startedAt;
        this.leaseExpiresAt = leaseExpiresAt;
        this.processedAt = null;
        this.processingStatus = ProcessingStatus.PROCESSING;
        this.attemptCount++;
    }

    public void complete(LocalDateTime processedAt) {
        if (processingStatus != ProcessingStatus.PROCESSING) throw new IllegalStateException("Not processing");
        this.processedAt = EntitySupport.required(processedAt, "processedAt");
        this.processingStatus = ProcessingStatus.PROCESSED;
    }

    public enum ProcessingStatus {
        RECEIVED, PROCESSING, PROCESSED, FAILED
    }

}

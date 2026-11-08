package com.zello.zello.domain.project;

import com.zello.zello.domain.user.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** A removed member cannot regain access through a previously used shared link. */
@Entity
@Table(name = "project_invitation_uses")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectInvitationUse {
    @EmbeddedId
    private ProjectInvitationUseId id;

    @Column(name = "accepted_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime acceptedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invitation_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_invitation_uses_invitation"))
    private ProjectInvitation invitation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_invitation_uses_user"))
    private User user;

    public ProjectInvitationUse(ProjectInvitation invitation, User user, LocalDateTime acceptedAt) {
        this.id = new ProjectInvitationUseId(invitation.getId(), user.getId());
        this.invitation = invitation;
        this.user = user;
        this.acceptedAt = acceptedAt;
    }
}

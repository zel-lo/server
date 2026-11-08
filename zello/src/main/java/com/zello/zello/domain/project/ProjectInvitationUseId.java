package com.zello.zello.domain.project;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Embeddable
@EqualsAndHashCode
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectInvitationUseId implements Serializable {
    private static final long serialVersionUID = 1L;
    @Column(name = "invitation_id", nullable = false)
    private Long invitationId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
}

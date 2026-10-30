package com.zello.zello.domain.patchnote;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Embeddable
@EqualsAndHashCode
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PatchNoteItemId implements Serializable {
    private static final long serialVersionUID = 1L;

    @Column(name = "patch_note_id", nullable = false)
    private Long patchNoteId;

    @Column(name = "pull_request_id", nullable = false)
    private Long pullRequestId;

}

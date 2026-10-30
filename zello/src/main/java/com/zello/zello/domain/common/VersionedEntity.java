package com.zello.zello.domain.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;

@Getter
@MappedSuperclass
public abstract class VersionedEntity extends IdentityEntity {
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}

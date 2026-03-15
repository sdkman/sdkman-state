package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.Option
import arrow.core.raise.either
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.Auditable
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueVersion
import io.sdkman.state.domain.model.Version
import io.sdkman.state.domain.repository.AuditRepository
import io.sdkman.state.domain.repository.VersionRepository
import io.sdkman.state.domain.service.TagService
import io.sdkman.state.domain.service.VersionService
import org.slf4j.LoggerFactory

class VersionServiceImpl(
    private val versionsRepo: VersionRepository,
    private val tagService: TagService,
    private val auditRepo: AuditRepository,
) : VersionService {
    private val logger = LoggerFactory.getLogger(VersionServiceImpl::class.java)

    override suspend fun findByCandidate(
        candidate: String,
        platform: Option<Platform>,
        distribution: Option<Distribution>,
        visible: Option<Boolean>,
    ): Either<DomainError, List<Version>> =
        versionsRepo
            .findByCandidate(candidate, platform, distribution, visible)
            .mapLeft { DomainError.DatabaseError(it) }

    override suspend fun findUnique(
        candidate: String,
        version: String,
        platform: Platform,
        distribution: Option<Distribution>,
    ): Either<DomainError, Option<Version>> =
        versionsRepo
            .findUnique(candidate, version, platform, distribution)
            .mapLeft { DomainError.DatabaseError(it) }

    override suspend fun createOrUpdate(
        version: Version,
        username: String,
    ): Either<DomainError, Unit> =
        versionsRepo
            .createOrUpdate(version)
            .mapLeft { DomainError.DatabaseError(it) }
            .map { versionId ->
                logAudit(username, AuditOperation.CREATE, version)
                processTags(versionId, version)
            }

    override suspend fun delete(
        uniqueVersion: UniqueVersion,
        username: String,
    ): Either<DomainError, Unit> =
        either {
            val versionToDelete =
                versionsRepo
                    .findUnique(
                        candidate = uniqueVersion.candidate,
                        version = uniqueVersion.version,
                        platform = uniqueVersion.platform,
                        distribution = uniqueVersion.distribution,
                    ).mapLeft { DomainError.DatabaseError(it) }
                    .bind()
                    .toEither {
                        DomainError.VersionNotFound(uniqueVersion.candidate, uniqueVersion.version)
                    }.bind()
            val versionId =
                versionsRepo
                    .findVersionId(uniqueVersion)
                    .mapLeft { DomainError.DatabaseError(it) }
                    .bind()
                    .toEither {
                        DomainError.VersionNotFound(uniqueVersion.candidate, uniqueVersion.version)
                    }.bind()
            val tagNames =
                tagService
                    .findTagNamesByVersionId(versionId)
                    .bind()
            if (tagNames.isNotEmpty()) raise(DomainError.TagConflict(tagNames))
            logAudit(username, AuditOperation.DELETE, versionToDelete)
            val deleted =
                versionsRepo
                    .delete(uniqueVersion)
                    .mapLeft { DomainError.DatabaseError(it) }
                    .bind()
            if (deleted == 0) {
                raise(DomainError.VersionNotFound(uniqueVersion.candidate, uniqueVersion.version))
            }
        }

    private suspend fun logAudit(
        username: String,
        operation: AuditOperation,
        data: Auditable,
    ) {
        auditRepo.recordAudit(username, operation, data).onLeft { error ->
            logger.warn("Audit logging failed: ${error.message}", error)
        }
    }

    private suspend fun processTags(
        versionId: Int,
        version: Version,
    ) {
        version.tags.onSome { tagList ->
            tagService
                .replaceTags(
                    versionId = versionId,
                    candidate = version.candidate,
                    distribution = version.distribution,
                    platform = version.platform,
                    tags = tagList,
                ).onLeft { error ->
                    logger.warn("Tag processing failed: $error")
                }
        }
    }
}

package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.Option
import arrow.core.getOrElse
import arrow.core.raise.either
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.TagAssignment
import io.sdkman.state.domain.model.UniqueTag
import io.sdkman.state.domain.model.UniqueVersion
import io.sdkman.state.domain.repository.AuditRepository
import io.sdkman.state.domain.repository.TagRepository
import io.sdkman.state.domain.repository.VersionRepository
import io.sdkman.state.domain.service.TagService
import io.sdkman.state.domain.service.Transactional
import java.util.UUID

class TagServiceImpl(
    private val tagsRepo: TagRepository,
    private val auditRepo: AuditRepository,
    private val versionsRepo: VersionRepository,
    private val transactional: Transactional,
) : TagService {
    override suspend fun replaceTags(
        versionId: Int,
        candidate: String,
        distribution: Option<Distribution>,
        platform: Platform,
        tags: List<String>,
    ): Either<DomainError, Unit> =
        tagsRepo
            .replaceTags(versionId, candidate, distribution, platform, tags)
            .mapLeft { DomainError.DatabaseError(it) }

    override suspend fun assignTagToVersion(
        versionId: Int,
        candidate: String,
        distribution: Option<Distribution>,
        platform: Platform,
        tag: String,
    ): Either<DomainError, Unit> =
        tagsRepo
            .assignTag(versionId, candidate, distribution, platform, tag)
            .mapLeft { DomainError.DatabaseError(it) }

    override suspend fun findTagNamesByVersionId(versionId: Int): Either<DomainError, List<String>> =
        tagsRepo
            .findTagNamesByVersionId(versionId)
            .mapLeft { DomainError.DatabaseError(it) }

    override suspend fun deleteTag(
        uniqueTag: UniqueTag,
        vendorId: UUID,
        email: String,
    ): Either<DomainError, Unit> =
        transactional
            .inTransaction { deleteExistingTag(uniqueTag) }
            .onRight { auditRepo.recordAuditBestEffort(vendorId, email, AuditOperation.DELETE, uniqueTag) }

    private suspend fun deleteExistingTag(uniqueTag: UniqueTag): Either<DomainError, Unit> =
        either {
            versionsRepo.lockWritesIfJava(uniqueTag.candidate, uniqueTag.distribution, uniqueTag.platform).bind()
            val deletedCount =
                tagsRepo
                    .deleteTag(uniqueTag)
                    .mapLeft { DomainError.DatabaseError(it) }
                    .bind()
            if (deletedCount == 0) raise(DomainError.TagNotFound(uniqueTag.tag))
        }

    override suspend fun assignTag(
        assignment: TagAssignment,
        vendorId: UUID,
        email: String,
    ): Either<DomainError, Unit> =
        transactional
            .inTransaction { assignTagToExistingVersion(assignment) }
            .onRight { auditRepo.recordAuditBestEffort(vendorId, email, AuditOperation.TAG, assignment) }

    private suspend fun assignTagToExistingVersion(assignment: TagAssignment): Either<DomainError, Unit> =
        either {
            versionsRepo.lockWritesIfJava(assignment.candidate, assignment.distribution, assignment.platform).bind()
            val versionId =
                versionsRepo
                    .findVersionId(
                        UniqueVersion(
                            candidate = assignment.candidate,
                            version = assignment.version,
                            distribution = assignment.distribution,
                            platform = assignment.platform,
                        ),
                    ).mapLeft { DomainError.DatabaseError(it) }
                    .bind()
                    .getOrElse { raise(DomainError.VersionNotFound(assignment.candidate, assignment.version)) }
            assignTagToVersion(versionId, assignment.candidate, assignment.distribution, assignment.platform, assignment.tag)
                .bind()
        }
}

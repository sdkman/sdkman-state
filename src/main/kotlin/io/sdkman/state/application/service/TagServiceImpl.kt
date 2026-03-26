package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.Option
import arrow.core.raise.either
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueTag
import io.sdkman.state.domain.repository.AuditRepository
import io.sdkman.state.domain.repository.TagRepository
import io.sdkman.state.domain.service.TagService
import org.slf4j.LoggerFactory
import java.util.UUID

class TagServiceImpl(
    private val tagsRepo: TagRepository,
    private val auditRepo: AuditRepository,
) : TagService {
    private val logger = LoggerFactory.getLogger(TagServiceImpl::class.java)

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

    override suspend fun findTagNamesByVersionId(versionId: Int): Either<DomainError, List<String>> =
        tagsRepo
            .findTagNamesByVersionId(versionId)
            .mapLeft { DomainError.DatabaseError(it) }

    override suspend fun deleteTag(
        uniqueTag: UniqueTag,
        vendorId: UUID,
        email: String,
    ): Either<DomainError, Unit> =
        either {
            val deletedCount =
                tagsRepo
                    .deleteTag(uniqueTag)
                    .mapLeft { DomainError.DatabaseError(it) }
                    .bind()
            if (deletedCount == 0) raise(DomainError.TagNotFound(uniqueTag.tag))
            auditRepo
                .recordAudit(vendorId, email, AuditOperation.DELETE, uniqueTag)
                .onLeft { error ->
                    logger.warn("Audit logging failed for tag deletion: ${error.message}")
                }
        }
}

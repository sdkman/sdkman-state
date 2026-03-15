package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.raise.either
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.UniqueTag
import io.sdkman.state.domain.repository.AuditRepository
import io.sdkman.state.domain.repository.TagsRepository
import io.sdkman.state.domain.service.TagService
import org.slf4j.LoggerFactory

class TagServiceImpl(
    private val tagsRepo: TagsRepository,
    private val auditRepo: AuditRepository,
) : TagService {
    private val logger = LoggerFactory.getLogger(TagServiceImpl::class.java)

    override suspend fun deleteTag(
        uniqueTag: UniqueTag,
        username: String,
    ): Either<DomainError, Unit> =
        either {
            val deletedCount =
                tagsRepo
                    .deleteTag(uniqueTag)
                    .mapLeft { DomainError.DatabaseError(it) }
                    .bind()
            if (deletedCount == 0) raise(DomainError.TagNotFound(uniqueTag.tag))
            auditRepo
                .recordAudit(username, AuditOperation.DELETE, uniqueTag)
                .onLeft { error ->
                    logger.warn("Audit logging failed for tag deletion: ${error.message}")
                }
        }
}

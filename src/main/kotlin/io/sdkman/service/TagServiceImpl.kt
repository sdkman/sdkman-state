package io.sdkman.service

import arrow.core.Either
import arrow.core.raise.either
import io.sdkman.domain.AuditOperation
import io.sdkman.domain.AuditRepository
import io.sdkman.domain.DomainError
import io.sdkman.domain.TagService
import io.sdkman.domain.TagsRepository
import io.sdkman.domain.UniqueTag
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

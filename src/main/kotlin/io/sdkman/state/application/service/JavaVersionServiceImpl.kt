package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.raise.either
import arrow.core.some
import io.sdkman.state.application.validation.InvalidVersionFormatError
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.JavaRefusal
import io.sdkman.state.domain.model.JavaVersion
import io.sdkman.state.domain.model.Version
import io.sdkman.state.domain.repository.AuditRepository
import io.sdkman.state.domain.repository.VersionRepository
import io.sdkman.state.domain.service.JavaSupersession
import io.sdkman.state.domain.service.JavaVersionService
import io.sdkman.state.domain.service.TagService
import io.sdkman.state.domain.service.Transactional
import io.sdkman.state.domain.service.VendorDecision
import java.util.UUID

class JavaVersionServiceImpl(
    private val versionsRepo: VersionRepository,
    private val tagService: TagService,
    private val auditRepo: AuditRepository,
    private val transactional: Transactional,
) : JavaVersionService {
    override suspend fun publish(
        version: Version,
        vendorId: UUID,
        email: String,
    ): Either<DomainError, Unit> =
        parsePost(version)
            .flatMap { post -> transactional.inTransaction { storeVendorPost(version, post) } }
            .onLeft { error ->
                if (error is DomainError.JavaPostRefused) {
                    auditRepo.recordAuditBestEffort(vendorId, email, AuditOperation.REFUSE, JavaRefusal(version, error.head))
                }
            }.onRight { publication -> recordPublication(publication, vendorId, email) }
            .map { }

    private fun parsePost(version: Version): Either<DomainError, JavaVersion> =
        JavaVersion
            .parse(version.version)
            .toEither { DomainError.ValidationFailed(InvalidVersionFormatError(version = version.version).message) }

    private suspend fun storeVendorPost(
        version: Version,
        post: JavaVersion,
    ): Either<DomainError, Publication> =
        either {
            val decision = decideInLockedScope(version, post).bind()
            val stored = version.copy(visible = decision.visible.some())
            val versionId =
                versionsRepo
                    .createOrUpdate(stored)
                    .mapLeft { DomainError.DatabaseError(it) }
                    .bind()
            versionsRepo
                .hideJavaVersions(version.distribution, version.platform, decision.hidden.map(Version::version))
                .mapLeft { DomainError.DatabaseError(it) }
                .bind()
            val storedTags = moveTagsOnto(versionId, version, decision.tagsToMove).bind()
            Publication(
                stored = stored.copy(tags = storedTags.some()),
                hidden = decision.hidden.map { row -> row.hiddenWithout(decision.tagsToMove) },
            )
        }

    private suspend fun moveTagsOnto(
        versionId: Int,
        version: Version,
        tags: List<String>,
    ): Either<DomainError, List<String>> =
        either {
            tags.forEach { tag ->
                tagService.assignTagToVersion(versionId, version.candidate, version.distribution, version.platform, tag).bind()
            }
            tagService.findTagNamesByVersionId(versionId).bind()
        }

    private suspend fun decideInLockedScope(
        version: Version,
        post: JavaVersion,
    ): Either<DomainError, VendorDecision> =
        either {
            versionsRepo
                .lockJavaWrites(version.distribution, version.platform)
                .mapLeft { DomainError.DatabaseError(it) }
                .bind()
            val rows =
                versionsRepo
                    .findJavaRows(version.distribution, version.platform)
                    .mapLeft { DomainError.DatabaseError(it) }
                    .bind()
            JavaSupersession
                .decide(post, rows)
                .mapLeft { refusal -> DomainError.JavaPostRefused(version.version, refusal.head) }
                .bind()
        }

    private suspend fun recordPublication(
        publication: Publication,
        vendorId: UUID,
        email: String,
    ) {
        auditRepo.recordAuditBestEffort(vendorId, email, AuditOperation.CREATE, publication.stored)
        publication.hidden.forEach { row -> auditRepo.recordAuditBestEffort(vendorId, email, AuditOperation.RETIRE, row) }
    }

    private data class Publication(
        val stored: Version,
        val hidden: List<Version>,
    )
}

private fun Version.hiddenWithout(movedTags: List<String>): Version =
    copy(visible = false.some(), tags = tags.map { held -> held - movedTags.toSet() })

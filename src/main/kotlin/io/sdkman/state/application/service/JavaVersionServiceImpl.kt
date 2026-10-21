package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.Some
import arrow.core.flatMap
import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.right
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

    override suspend fun overrideAsAdmin(
        version: Version,
        vendorId: UUID,
        email: String,
    ): Either<DomainError, Unit> =
        transactional
            .inTransaction { storeAdminPost(version.copy(visible = version.visible.getOrElse { true }.some())) }
            .onRight { publication -> recordPublication(publication, vendorId, email) }
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
            val versionId = upsert(stored).bind()
            hideInScope(version, decision.hidden).bind()
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
            versionsRepo.lockWritesIfJava(version.candidate, version.distribution, version.platform).bind()
            JavaSupersession
                .decide(post, findScopeRows(version).bind())
                .mapLeft { refusal -> DomainError.JavaPostRefused(version.version, refusal.head) }
                .bind()
        }

    private suspend fun storeAdminPost(stored: Version): Either<DomainError, Publication> =
        either {
            versionsRepo.lockWritesIfJava(stored.candidate, stored.distribution, stored.platform).bind()
            val versionId = upsert(stored).bind()
            tagService
                .replaceTags(versionId, stored.candidate, stored.distribution, stored.platform, stored.tags.getOrElse { emptyList() })
                .bind()
            val hidden = hideRestOfSeries(stored).bind()
            val storedTags = tagService.findTagNamesByVersionId(versionId).bind()
            Publication(stored = stored.copy(tags = storedTags.some()), hidden = hidden)
        }

    private suspend fun hideRestOfSeries(stored: Version): Either<DomainError, List<Version>> =
        JavaVersion
            .parse(stored.version)
            .filter { stored.visible == Some(true) }
            .map { post ->
                either {
                    val hidden = JavaSupersession.overrideHides(post, findScopeRows(stored).bind())
                    hideInScope(stored, hidden).bind()
                    hidden.map { row -> row.copy(visible = false.some()) }
                }
            }.getOrElse { emptyList<Version>().right() }

    private suspend fun findScopeRows(version: Version): Either<DomainError, List<Version>> =
        versionsRepo
            .findJavaRows(version.distribution, version.platform)
            .mapLeft { DomainError.DatabaseError(it) }

    private suspend fun upsert(version: Version): Either<DomainError, Int> =
        versionsRepo
            .createOrUpdate(version)
            .mapLeft { DomainError.DatabaseError(it) }

    private suspend fun hideInScope(
        version: Version,
        rows: List<Version>,
    ): Either<DomainError, Int> =
        versionsRepo
            .hideJavaVersions(version.distribution, version.platform, rows.map(Version::version))
            .mapLeft { DomainError.DatabaseError(it) }

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

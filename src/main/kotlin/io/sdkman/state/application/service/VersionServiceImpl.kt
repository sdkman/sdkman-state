package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.Option
import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.right
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.Auditable
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.SeriesKey
import io.sdkman.state.domain.model.UniqueVersion
import io.sdkman.state.domain.model.Version
import io.sdkman.state.domain.repository.AuditRepository
import io.sdkman.state.domain.repository.VersionRepository
import io.sdkman.state.domain.service.TagService
import io.sdkman.state.domain.service.Transactional
import io.sdkman.state.domain.service.VersionService
import org.slf4j.LoggerFactory
import java.util.UUID

// R1: the candidate that supersedes on write is fixed in code — java is the only candidate with a
// distribution axis and a release-series notion, so the rule is meaningful nowhere else.
private const val JAVA_CANDIDATE = "java"

class VersionServiceImpl(
    private val versionsRepo: VersionRepository,
    private val tagService: TagService,
    private val auditRepo: AuditRepository,
    private val transactional: Transactional,
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

    override suspend fun resolveByTag(
        candidate: String,
        tag: String,
        platform: Platform,
        distribution: Option<Distribution>,
    ): Either<DomainError, Option<Version>> =
        versionsRepo
            .findByTag(candidate, tag, platform, distribution)
            .mapLeft { DomainError.DatabaseError(it) }

    // R3/R5: the version write and tag replacement run inside a single Transactional block so a
    // tag-replacement failure rolls back the version write (today they were in separate
    // transactions, leaving orphan version rows on partial failure). R13: the retirements of the
    // superseded series join that same transaction, so a failed retirement fails the POST and
    // leaves no partial state. R6/R15: audit logging stays outside the transaction so an audit
    // failure cannot roll back a successful version write, for the create and for each retirement.
    override suspend fun createOrUpdate(
        version: Version,
        vendorId: UUID,
        email: String,
    ): Either<DomainError, Unit> =
        transactional
            .inTransaction {
                either {
                    val versionId =
                        versionsRepo
                            .createOrUpdate(version)
                            .mapLeft { DomainError.DatabaseError(it) }
                            .bind()
                    version.tags.onSome { tagList ->
                        tagService
                            .replaceTags(
                                versionId = versionId,
                                candidate = version.candidate,
                                distribution = version.distribution,
                                platform = version.platform,
                                tags = tagList,
                            ).bind()
                    }
                    retireSupersededVersions(version).bind()
                }
            }.also { result ->
                result.onRight { retired ->
                    logAudit(vendorId, email, AuditOperation.CREATE, version)
                    retired.forEach { logAudit(vendorId, email, AuditOperation.RETIRE, it) }
                }
            }.map { }

    // R1: supersession is fixed to the java candidate in code — no toggle, no candidate list.
    // R11: a POST carrying visible=false publishes a hidden row and must not empty the series;
    // an absent visible field persists as true and therefore does supersede. R11a: a posted
    // version outside the eligibility grammar belongs to no series and retires nothing, which is
    // the fail-safe if semverish validation is ever switched off for java.
    private suspend fun retireSupersededVersions(version: Version): Either<DomainError, List<Version>> =
        if (version.candidate != JAVA_CANDIDATE || !version.visible.getOrElse { true }) {
            emptyList<Version>().right()
        } else {
            SeriesKey
                .of(
                    candidate = version.candidate,
                    distribution = version.distribution,
                    platform = version.platform,
                    version = version.version,
                ).map { key ->
                    versionsRepo
                        .retireOtherVersionsInSeries(key, version.version)
                        .mapLeft { DomainError.DatabaseError(it) }
                }.getOrElse { emptyList<Version>().right() }
        }

    override suspend fun delete(
        uniqueVersion: UniqueVersion,
        vendorId: UUID,
        email: String,
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
            val tagNames = versionToDelete.tags.getOrElse { emptyList() }
            if (tagNames.isNotEmpty()) raise(DomainError.TagConflict(tagNames))
            logAudit(vendorId, email, AuditOperation.DELETE, versionToDelete)
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
        vendorId: UUID,
        email: String,
        operation: AuditOperation,
        data: Auditable,
    ) {
        auditRepo.recordAudit(vendorId, email, operation, data).onLeft { error ->
            logger.warn("Audit logging failed: ${error.message}", error)
        }
    }
}

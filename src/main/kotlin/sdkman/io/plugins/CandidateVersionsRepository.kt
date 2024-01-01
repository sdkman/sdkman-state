package sdkman.io.plugins

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

class CandidateVersionsRepository {
    object CandidateVersions : IntIdTable(name = "version") {
        val candidate = varchar("candidate", length = 20)
        val version = varchar("version", length = 25)
        val platform = varchar("platform", length = 15)
        val url = varchar("url", length = 500)
        val visible = bool("visible")
        val vendor = varchar("vendor", length = 10).nullable()
        val md5Sum = varchar("md5_sum", length = 32).nullable()
        val sha256Sum = varchar("sha_256_sum", length = 64).nullable()
        val sha512Sum = varchar("sha_512_sum", length = 128).nullable()
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    suspend fun read(candidate: String): List<CandidateVersion> =
        dbQuery {
            CandidateVersions.select { CandidateVersions.candidate eq candidate }
                .map {
                    CandidateVersion(
                        candidate = it[CandidateVersions.candidate],
                        version = it[CandidateVersions.version],
                        platform = it[CandidateVersions.platform],
                        url = it[CandidateVersions.url],
                        visible = it[CandidateVersions.visible],
                        vendor = it[CandidateVersions.vendor],
                        md5sum = it[CandidateVersions.md5Sum],
                        sha256sum = it[CandidateVersions.sha256Sum],
                        sha512sum = it[CandidateVersions.sha512Sum]
                    )
                }
        }
}

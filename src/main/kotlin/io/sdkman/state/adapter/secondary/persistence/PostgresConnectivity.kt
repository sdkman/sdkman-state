package io.sdkman.state.adapter.secondary.persistence

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

const val NA_SENTINEL = "NA"

suspend fun <T> dbQuery(block: suspend () -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }

fun java.time.Instant.toKotlinTimeInstant(): kotlin.time.Instant = kotlin.time.Instant.fromEpochSeconds(epochSecond, nano.toLong())

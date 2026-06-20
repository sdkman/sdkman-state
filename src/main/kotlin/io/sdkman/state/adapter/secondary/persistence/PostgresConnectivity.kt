package io.sdkman.state.adapter.secondary.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

suspend fun <T> dbQuery(block: suspend () -> T): T = withContext(Dispatchers.IO) { suspendTransaction { block() } }

fun java.time.Instant.toKotlinTimeInstant(): kotlin.time.Instant = kotlin.time.Instant.fromEpochSeconds(epochSecond, nano.toLong())

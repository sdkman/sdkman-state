package io.sdkman.state.support

import arrow.core.Either
import io.kotest.matchers.shouldBe

/**
 * Arrow Either test matchers for Kotest.
 *
 * These replace verbose patterns like `result.isRight() shouldBe true` and
 * `result.getOrElse { error("expected Right") }` with expressive one-liners
 * that also return the unwrapped value for subsequent assertions.
 */

fun <A, B> Either<A, B>.shouldBeRight(): B =
    fold(
        { throw AssertionError("Expected Either.Right but got Either.Left($it)") },
        { it },
    )

infix fun <A, B> Either<A, B>.shouldBeRight(expected: B): B =
    fold(
        { throw AssertionError("Expected Either.Right($expected) but got Either.Left($it)") },
        {
            it shouldBe expected
            it
        },
    )

fun <A, B> Either<A, B>.shouldBeLeft(): A =
    fold(
        { it },
        { throw AssertionError("Expected Either.Left but got Either.Right($it)") },
    )

infix fun <A, B> Either<A, B>.shouldBeLeft(expected: A): A =
    fold(
        {
            it shouldBe expected
            it
        },
        { throw AssertionError("Expected Either.Left($expected) but got Either.Right($it)") },
    )

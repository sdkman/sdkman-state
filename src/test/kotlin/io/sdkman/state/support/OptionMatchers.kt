package io.sdkman.state.support

import arrow.core.Option
import io.kotest.matchers.shouldBe

/**
 * Arrow Option test matchers for Kotest.
 *
 * These replace verbose patterns like `.isSome() shouldBe true` with expressive
 * one-liners that also return the unwrapped value for subsequent assertions.
 */

fun <A> Option<A>.shouldBeSome(): A =
    fold(
        { throw AssertionError("Expected Some but got None") },
        { it },
    )

infix fun <A> Option<A>.shouldBeSome(expected: A): A =
    fold(
        { throw AssertionError("Expected Some($expected) but got None") },
        {
            it shouldBe expected
            it
        },
    )

fun <A> Option<A>.shouldBeNone() =
    fold(
        { },
        { throw AssertionError("Expected None but got Some($it)") },
    )

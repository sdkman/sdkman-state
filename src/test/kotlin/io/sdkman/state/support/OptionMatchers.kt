package io.sdkman.state.support

import arrow.core.Option
import io.kotest.matchers.shouldBe

fun <A> Option<A>.shouldBeSome(): A =
    fold(
        { throw AssertionError("Expected Some but got none()") },
        { it },
    )

infix fun <A> Option<A>.shouldBeSome(expected: A): A =
    fold(
        { throw AssertionError("Expected Some($expected) but got none()") },
        {
            it shouldBe expected
            it
        },
    )

fun <A> Option<A>.shouldBeNone() =
    fold(
        { },
        { throw AssertionError("Expected none() but got Some($it)") },
    )

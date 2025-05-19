/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.calls.inference.components

import org.jetbrains.kotlin.container.DefaultImplementation
import org.jetbrains.kotlin.resolve.calls.inference.model.Constraint
import org.jetbrains.kotlin.resolve.calls.inference.model.ConstraintSystemError
import org.jetbrains.kotlin.resolve.calls.inference.model.InitialConstraint
import org.jetbrains.kotlin.types.model.TypeConstructorMarker
import org.jetbrains.kotlin.types.model.TypeSystemInferenceExtensionContext
import org.jetbrains.kotlin.types.model.TypeVariableMarker

@DefaultImplementation(ConstraintsLogger.Dummy::class)
abstract class ConstraintsLogger {
    protected abstract val currentContext: TypeSystemInferenceExtensionContext

    protected fun requireSameContext(context: TypeSystemInferenceExtensionContext) {
        require(context == currentContext) {
            "Logging a constraint from $context together with constraints from $currentContext. Make sure you call `logContext()` when you create the constraints system."
        }
    }

    abstract fun logInitial(constraint: InitialConstraint, context: TypeSystemInferenceExtensionContext)

    abstract fun log(variable: TypeVariableMarker, constraint: Constraint, context: TypeSystemInferenceExtensionContext)

    abstract fun logConstraintSubstitution(
        variable: TypeVariableMarker,
        substitutedConstraint: Constraint,
        context: TypeSystemInferenceExtensionContext
    )

    abstract fun logError(error: ConstraintSystemError, context: TypeSystemInferenceExtensionContext)

    abstract fun logNewVariable(variable: TypeVariableMarker, context: TypeSystemInferenceExtensionContext)

    abstract fun logReadiness(
        variable: TypeConstructorMarker,
        readiness: VariableFixationFinder.TypeVariableFixationReadiness,
        context: TypeSystemInferenceExtensionContext,
    )

    abstract val currentState: State

    abstract class State() {
        abstract fun withPrevious(constraint: InitialConstraint)

        abstract fun withPrevious(
            variable1: TypeVariableMarker, constraint1: Constraint,
            variable2: TypeVariableMarker, constraint2: Constraint,
        )

        abstract fun logReadiness()

        abstract fun restore()
    }

    /**
     * Exists to satisfy K1's dependency injection as it doesn't support `null` values.
     */
    object Dummy : ConstraintsLogger() {
        override val currentContext: TypeSystemInferenceExtensionContext
            get() = error("Should not be called")

        override fun logInitial(
            constraint: InitialConstraint,
            context: TypeSystemInferenceExtensionContext
        ) {
        }

        override fun log(
            variable: TypeVariableMarker,
            constraint: Constraint,
            context: TypeSystemInferenceExtensionContext
        ) {
        }

        override fun logConstraintSubstitution(
            variable: TypeVariableMarker,
            substitutedConstraint: Constraint,
            context: TypeSystemInferenceExtensionContext
        ) {
        }

        override fun logError(
            error: ConstraintSystemError,
            context: TypeSystemInferenceExtensionContext
        ) {
        }

        override fun logNewVariable(
            variable: TypeVariableMarker,
            context: TypeSystemInferenceExtensionContext
        ) {
        }

        override fun logReadiness(
            variable: TypeConstructorMarker,
            readiness: VariableFixationFinder.TypeVariableFixationReadiness,
            context: TypeSystemInferenceExtensionContext
        ) {
        }

        override val currentState: State = object : State() {
            override fun withPrevious(constraint: InitialConstraint) {}

            override fun withPrevious(
                variable1: TypeVariableMarker,
                constraint1: Constraint,
                variable2: TypeVariableMarker,
                constraint2: Constraint
            ) {
            }

            override fun logReadiness() {}
            override fun restore() {}
        }
    }
}

inline fun <T> ConstraintsLogger?.withStateAdvancement(block: () -> T, advance: ConstraintsLogger.State.() -> Unit): T {
    val oldContext = this?.currentState
    return try {
        oldContext?.advance()
        block()
    } finally {
        oldContext?.restore()
    }
}

inline fun <T> ConstraintsLogger?.withPrevious(constraint: InitialConstraint, block: () -> T): T =
    withStateAdvancement(block) { withPrevious(constraint) }

inline fun <T> ConstraintsLogger?.withPrevious(
    variable1: TypeVariableMarker,
    constraint1: Constraint,
    variable2: TypeVariableMarker,
    constraint2: Constraint,
    block: () -> T,
): T = withStateAdvancement(block) { withPrevious(variable1, constraint1, variable2, constraint2) }

inline fun <T> ConstraintsLogger?.logReadiness(block: () -> T): T =
    withStateAdvancement(block) { logReadiness() }

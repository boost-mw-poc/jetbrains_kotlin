/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.inference

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.FirSessionComponent
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.resolve.calls.candidate.Candidate
import org.jetbrains.kotlin.resolve.calls.inference.components.ConstraintsLogger
import org.jetbrains.kotlin.resolve.calls.inference.components.VariableFixationFinder
import org.jetbrains.kotlin.resolve.calls.inference.model.Constraint
import org.jetbrains.kotlin.resolve.calls.inference.model.ConstraintKind
import org.jetbrains.kotlin.resolve.calls.inference.model.ConstraintSystemError
import org.jetbrains.kotlin.resolve.calls.inference.model.InitialConstraint
import org.jetbrains.kotlin.types.model.TypeConstructorMarker
import org.jetbrains.kotlin.types.model.TypeSystemInferenceExtensionContext
import org.jetbrains.kotlin.types.model.TypeVariableMarker

open class FirConstraintsLogger : ConstraintsLogger(), FirSessionComponent {
    sealed class LoggingElement

    class CallElement(
        val call: String,
        val candidates: MutableList<CandidateElement> = mutableListOf(),
    ) : LoggingElement()

    class CandidateElement(
        val candidate: Candidate,
        val blocks: MutableList<BlockElement> = mutableListOf(),
    ) : LoggingElement()

    sealed class BlockElement : LoggingElement()

    class StageBlockElement(val name: String, val elements: MutableList<StageElement> = mutableListOf()) : BlockElement()

    class VariableReadinessBlockElement(
        val constraints: MutableList<VariableReadinessElement> = mutableListOf(),
    ) : BlockElement()

    sealed class StageElement() : LoggingElement()

    class NewVariableElement(val variable: TypeVariableMarker) : StageElement()

    class ErrorElement(val error: ConstraintSystemError) : StageElement()

    sealed class ConstraintElement(val previous: List<ConstraintElement>) : StageElement()

    class InitialConstraintElement(val constraint: String, val position: String) : ConstraintElement(emptyList())

    class IncorporatedConstraintElement(
        val constraint: String,
        previous: List<ConstraintElement>,
    ) : ConstraintElement(previous)

    class ConstraintSubstitutionElement(val constraint: String) : ConstraintElement(emptyList())

    class VariableReadinessElement(
        val variable: TypeConstructorMarker,
        val readiness: VariableFixationFinder.TypeVariableFixationReadiness,
    ) : LoggingElement()

    val topLevelElements: MutableList<LoggingElement> = mutableListOf<LoggingElement>()

    protected val currentCall: CallElement
        get() = topLevelElements.lastOrNull() as? CallElement ?: error("No call has been logged yet.")

    protected val currentCandidate: CandidateElement
        get() = currentCall.candidates.lastOrNull() ?: error("No candidate has been logged yet.")

    override val currentContext: TypeSystemInferenceExtensionContext
        get() = currentCandidate.candidate.system

    override lateinit var currentState: State

    private val currentBlock: BlockElement
        get() = currentState.currentBlock

    private val knownConstraintsCache = mutableMapOf<Any, ConstraintElement>()

    private fun cachedElementFor(constraint: InitialConstraint) =
        knownConstraintsCache[constraint]
            ?: error("This constraint has not yet been logged: $constraint")

    private fun cachedElementFor(constraint: Constraint) =
        knownConstraintsCache[constraint]
            ?: error("This constraint has not yet been logged: $constraint")

    fun logCall(call: FirElement) {
        topLevelElements.add(CallElement(call.render()))
    }

    fun logCandidate(candidate: Candidate) {
        // A candidate may have not been processed in one go.
        // See `fullyProcessCandidate()`.
        if (currentCall.candidates.lastOrNull()?.candidate != candidate) {
            currentCall.candidates.add(CandidateElement(candidate))
        }
    }

    fun logStage(name: String, context: TypeSystemInferenceExtensionContext) {
        requireSameContext(context)
        val currentChunk = StageBlockElement(name)
        currentCandidate.blocks.add(currentChunk)
        currentState = State(this, currentChunk)
    }

    private val currentStageElements: MutableList<StageElement>
        get() {
            val block = currentBlock as? StageBlockElement
                ?: error("Current block $currentBlock is not expecting constraints or variable introductions")
            return block.elements
        }

    private val currentVariableReadinessValues: MutableList<VariableReadinessElement>
        get() {
            val block = currentBlock as? VariableReadinessBlockElement
                ?: error("Current block $currentBlock is not expecting variable readiness values")
            return block.constraints
        }

    override fun logInitial(constraint: InitialConstraint, context: TypeSystemInferenceExtensionContext) {
        requireSameContext(context)
        // The constraint position must be rendered right away, because it may contain
        // FIR renders, and the FIR may have changed by the time we finish inference.
        val element = InitialConstraintElement(formatConstraint(constraint), sanitizeFqNames(constraint.position.toString()))
        knownConstraintsCache.putIfAbsent(constraint, element)
        currentStageElements += element
    }

    override fun log(variable: TypeVariableMarker, constraint: Constraint, context: TypeSystemInferenceExtensionContext) {
        requireSameContext(context)
        val element = IncorporatedConstraintElement(formatConstraint(variable, constraint), currentState.previous)
        knownConstraintsCache.putIfAbsent(constraint, element)
        currentStageElements += element
    }

    override fun logConstraintSubstitution(
        variable: TypeVariableMarker,
        substitutedConstraint: Constraint,
        context: TypeSystemInferenceExtensionContext,
    ) {
        requireSameContext(context)
        val element = ConstraintSubstitutionElement(formatConstraint(variable, substitutedConstraint))
        knownConstraintsCache.putIfAbsent(substitutedConstraint, element)
        currentStageElements += element
    }

    override fun logError(error: ConstraintSystemError, context: TypeSystemInferenceExtensionContext) {
        requireSameContext(context)
        currentStageElements.add(ErrorElement(error))
    }

    override fun logNewVariable(variable: TypeVariableMarker, context: TypeSystemInferenceExtensionContext) {
        requireSameContext(context)
        currentStageElements.add(NewVariableElement(variable))
    }

    override fun logReadiness(
        variable: TypeConstructorMarker,
        readiness: VariableFixationFinder.TypeVariableFixationReadiness,
        context: TypeSystemInferenceExtensionContext,
    ) {
        requireSameContext(context)
        currentVariableReadinessValues.add(VariableReadinessElement(variable, readiness))
    }

    data class State(
        private val outer: FirConstraintsLogger,
        val currentBlock: BlockElement,
        val previous: List<ConstraintElement> = mutableListOf(),
    ) : ConstraintsLogger.State() {
        override fun withPrevious(constraint: InitialConstraint) {
            outer.currentState = copy(previous = listOf(outer.cachedElementFor(constraint)))
        }

        override fun withPrevious(
            variable1: TypeVariableMarker,
            constraint1: Constraint,
            variable2: TypeVariableMarker,
            constraint2: Constraint,
        ) {
            outer.currentState = copy(
                previous = listOf(outer.cachedElementFor(constraint1), outer.cachedElementFor(constraint2)),
            )
        }

        override fun logReadiness() {
            outer.currentState = copy(currentBlock = VariableReadinessBlockElement())
        }

        override fun restore() {
            if (outer.currentBlock is VariableReadinessBlockElement) {
                val index = outer.currentCandidate.blocks.size.takeIf { it > 0 }?.let { it - 1 } ?: 0
                outer.currentCandidate.blocks.add(index, outer.currentBlock)
            }

            outer.currentState = this
        }
    }

    companion object {
        @JvmStatic
        protected fun formatConstraint(constraint: InitialConstraint): String {
            return when (constraint.constraintKind) {
                ConstraintKind.UPPER -> "${constraint.a} <: ${constraint.b}"
                ConstraintKind.LOWER -> "${constraint.b} <: ${constraint.a}"
                ConstraintKind.EQUALITY -> "${constraint.a} == ${constraint.b}"
            }
        }

        @JvmStatic
        protected fun formatConstraint(variable: TypeVariableMarker, constraint: Constraint): String {
            return when (constraint.kind) {
                ConstraintKind.LOWER -> "${constraint.type} <: $variable"
                ConstraintKind.UPPER -> "$variable <: ${constraint.type}"
                ConstraintKind.EQUALITY -> "$variable == ${constraint.type}"
            }
        }

        private val fqNameRegex = """(?:\w+\.)*(\w+)@\w+""".toRegex()

        @JvmStatic
        fun sanitizeFqNames(string: String): String = string.replace(fqNameRegex, "$1")
    }
}

val FirSession.constraintsLogger: FirConstraintsLogger? by FirSession.nullableSessionComponentAccessor<FirConstraintsLogger>()

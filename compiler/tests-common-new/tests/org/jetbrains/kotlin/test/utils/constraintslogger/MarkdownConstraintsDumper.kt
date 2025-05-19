/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.utils.constraintslogger

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.renderer.FirRenderer
import org.jetbrains.kotlin.fir.resolve.inference.FirConstraintsLogger
import org.jetbrains.kotlin.fir.resolve.inference.FirConstraintsLogger.*
import org.jetbrains.kotlin.fir.resolve.inference.FirConstraintsLogger.Companion.sanitizeFqNames
import org.jetbrains.kotlin.fir.symbols.SymbolInternals

class MarkdownConstraintsDumper(private val ignoreDuplicates: Boolean = true) : FirConstraintsDumper() {
    override fun renderDump(sessionsToLoggers: Map<FirSession, FirConstraintsLogger>): String =
        sessionsToLoggers.entries.joinToString("\n\n") { (session, logger) ->
            listOf("## `${session}`", *logger.topLevelElements.renderList().orEmpty().toTypedArray()).joinToString("\n\n")
        }

    override fun monospace(text: String): String = "`$text`"

    private val stack = mutableListOf<LoggingElement>()

    private fun LoggingElement.render(indexWithinParent: Int): String? {
        val oldStackSize = stack.size
        return try {
            stack.add(this)
            when (this) {
                is CallElement -> render(indexWithinParent)
                is CandidateElement -> render(indexWithinParent)
                is VariableReadinessBlockElement -> render()
                is StageBlockElement -> render()
                is InitialConstraintElement -> render(indexWithinParent)
                is IncorporatedConstraintElement -> render(indexWithinParent)
                is ConstraintSubstitutionElement -> render(indexWithinParent)
                is ErrorElement -> render(indexWithinParent)
                is NewVariableElement -> render(indexWithinParent)
                is VariableReadinessElement -> render(indexWithinParent)
            }
        } finally {
            while (stack.size > oldStackSize) {
                stack.removeLast()
            }
        }
    }

    private fun List<LoggingElement>.renderList(): List<String>? {
        var index = 0

        return mapNotNull {
            it.render(index)?.also { index++ }
        }.takeIf { it.isNotEmpty() }
    }

    private fun CallElement.render(indexWithinParent: Int): String? {
        val nonEmptyCandidates = candidates.renderList() ?: return null
        val title = "$indent### Call ${indexWithinParent + 1}"
        val code = "$indent```\n$call\n```"

        return listOf(title, code, *nonEmptyCandidates.toTypedArray()).joinToString("\n\n")
    }

    private fun CandidateElement.render(indexWithinParent: Int): String? {
        val nonEmptyBlocks = blocks.renderList() ?: return null
        val signatureRenderer = FirRenderer.forReadability()

        @OptIn(SymbolInternals::class)
        val signature = signatureRenderer.renderElementAsString(candidate.symbol.fir)
        val title = "$indent#### Candidate ${indexWithinParent + 1}: `${candidate.symbol}` --- `$signature`"

        return title + "\n" + nonEmptyBlocks.joinToString("\n\n")
    }

    private fun VariableReadinessBlockElement.render(): String? {
        val entries = constraints.renderList() ?: return null
        return "$indent##### Readiness of Variables:\n\n" + entries.joinToString("\n")
    }

    private fun StageBlockElement.render(): String? {
        val groupsByOrigin = bringStructureToStageElements(elements)
        var index = 0

        val entries = groupsByOrigin.mapNotNull { group ->
            renderStageElementGroup(group, indexWithinParent = { index }, incrementIndex = { index++ })
        }.takeIf { it.isNotEmpty() } ?: return null

        return "$indent##### $name:\n\n" + entries.joinToString("\n")
    }

    private fun bringStructureToStageElements(stageElements: List<StageElement>): List<List<StageElement>> {
        if (stageElements.isEmpty()) return emptyList()

        val groupsByOrigin = mutableListOf(mutableListOf<StageElement>())
        val seenConstraints = mutableSetOf<String>()

        for (next in stageElements) {
            if (ignoreDuplicates && next is ConstraintElement && next.renderRelation() in seenConstraints) {
                continue
            }

            val previousElement = groupsByOrigin.last().lastOrNull()

            if (next is ConstraintElement && previousElement is ConstraintElement && previousElement.previous == next.previous) {
                groupsByOrigin.last().add(next)
            } else if (groupsByOrigin.last().isNotEmpty()) {
                groupsByOrigin.add(mutableListOf(next))
            } else {
                groupsByOrigin.last().add(next)
            }

            if (next is ConstraintElement) {
                seenConstraints.add(next.renderRelation())
            }
        }

        return groupsByOrigin
    }

    private inline fun renderStageElementGroup(
        group: List<StageElement>,
        indexWithinParent: () -> Int,
        incrementIndex: () -> Unit,
    ): String? {
        val first = group.firstOrNull() ?: return null

        if (first !is ConstraintElement || first.previous.isEmpty()) {
            return group.mapNotNull {
                it.render(indexWithinParent())?.also { incrementIndex() }
            }.takeIf { it.isNotEmpty() }?.joinToString("\n")
        }

        val elements = withIndent { group.renderList() } ?: return null

        if (first.previous.size == 1) {
            if (first.previous.single().renderRelation() == (first.previousEntry as? ConstraintElement)?.renderRelation()) {
                return elements.joinToString("\n")
            }

            val origin = "$indent${indexWithinParent() + 1}. From `" + first.previous.single().renderRelation() + "`"
                .also { incrementIndex() }
            return "$origin\n" + elements.joinToString("\n")
        }

        val manyCombined = first.previous.joinToString(" with ") { "`" + it.renderRelation() + "`" }
        val origin = "$indent${indexWithinParent() + 1}. Combine $manyCombined".also { incrementIndex() }
        return "$origin\n" + elements.joinToString("\n")
    }

    private fun ConstraintElement.renderRelation() = when (this) {
        is InitialConstraintElement -> constraint
        is IncorporatedConstraintElement -> constraint
        is ConstraintSubstitutionElement -> constraint
    }

    private fun InitialConstraintElement.render(indexWithinParent: Int): String? {
        val position = sanitizeFqNames(position)
        return "$indent${indexWithinParent + 1}. `$constraint` _from ${position}_"
    }

    private fun IncorporatedConstraintElement.render(indexWithinParent: Int): String? {
        val formattedSelf = "`$constraint`"
        return "$indent${indexWithinParent + 1}. $formattedSelf"
    }

    private val ConstraintElement.previousEntry: StageElement?
        get() {
            val outerConstraintsOwner = stack.getOrNull(stack.size - 1) as? StageBlockElement ?: return null
            val currentEntryIndex = outerConstraintsOwner.elements.indexOf(this)
            return outerConstraintsOwner.elements.getOrNull(currentEntryIndex - 1)
        }

    private fun ConstraintSubstitutionElement.render(indexWithinParent: Int): String? {
        return "$indent${indexWithinParent + 1}. `$constraint`"
    }

    private fun ErrorElement.render(indexWithinParent: Int): String =
        "$indent${indexWithinParent + 1}. __${renderErrorTitle(error)}__"

    private fun NewVariableElement.render(indexWithinParent: Int): String =
        "${indent}${indexWithinParent + 1}. " + renderVariableTitle(variable)

    private fun VariableReadinessElement.render(indexWithinParent: Int): String =
        "$indent${indexWithinParent + 1}. $variable is $readiness"

    private var printingOptions = PrintingOptions()
    private val indent get() = printingOptions.indent

    private data class PrintingOptions(
        val indent: String = "",
    )

    private inline fun <T> withIndent(block: () -> T): T {
        val oldOptions = printingOptions
        return try {
            printingOptions = oldOptions.copy(indent = oldOptions.indent + "    ")
            block()
        } finally {
            printingOptions = oldOptions
        }
    }
}

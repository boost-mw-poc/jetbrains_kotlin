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
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.orEmpty

class MermaidConstraintsDumper(
    /**
     * Breaks too wide diagrams resulting from the regular topological sorting
     * by assigning all further elements the next rank.
     * IJ's Mermaid plugin can't zoom, so the space is precious :(
     */
    private val maxElementsPerRank: Int = 3,
    /**
     * Turns `kotlin/Comparable<kotlin/Long>` into `Comparable<Long>` to make
     * the diagrams narrower.
     */
    private val renderPackageQualifiers: Boolean = false,
    /**
     * Render the supertype above the subtype instead of `A <: B` and `A == B`.
     * Makes the diagrams narrower, but the unconventional notation may be confusing.
     */
    private val renderConstraintsVertically: Boolean = false,
) : FirConstraintsDumper() {
    override fun renderDump(sessionsToLoggers: Map<FirSession, FirConstraintsLogger>): String {
        val header = listOf(
            "flowchart TD",
            withIndent { "${indent}classDef nowrapClass text-align:center,white-space:nowrap;" },
            withIndent { "${indent}classDef callStyle fill:#f2debb,stroke:#333,stroke-width:4px;" },
            withIndent { "${indent}classDef candidateStyle fill:#f2e5ce,stroke:#333,stroke-width:4px;" },
            withIndent { "${indent}classDef stageStyle fill:#c8f0f7,stroke:#333,stroke-width:4px;" },
        ).joinToString("\n")

        val contents = withIndent {
            sessionsToLoggers.entries.mapNotNull { (session, logger) ->
                val title = node("session", formatCode(session))
                val rendered = withIndent { logger.topLevelElements.renderList() }
                listOfNotNull(title, rendered).join("\n\n")
            }.join("\n\n")
        }

        return listOfNotNull(header, contents?.rendered).joinToString("\n\n")
    }

    override fun monospace(text: String): String = "<tt>$text</tt>"

    override fun formatCode(code: Any): String = code.toString()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace("*", "\\*")
        .replace("*\n", "<br>")
        .let(::monospace)

    private fun formatConstraint(constraint: String): String = constraint
        .let {
            if (renderPackageQualifiers) return@let it
            it.replace("""\b(?:\w+/)*""".toRegex(), "")
        }
        .let {
            if (!renderConstraintsVertically) return@let it
            it.replace("""(.*) <: (.*)""".toRegex(), "$2\n▽\n$1").replace(" == ", "\n‖\n")
        }
        .let(::formatCode)

    private data class RenderingResult(
        val rendered: String,
        val firstNodeId: String,
        val lastNodes: List<LastNodeConnectionInfo>,
    )

    private fun RenderingResult.mapLasts(transform: (LastNodeConnectionInfo) -> LastNodeConnectionInfo): RenderingResult =
        copy(lastNodes = lastNodes.map(transform))

    private data class LastNodeConnectionInfo(
        val id: String,
        // Some subgraphs with cross-connections must have their
        // own connections equal to their topological height.
        // Length 1 is considered normal, that is `-->`.
        val outgoingConnectionLength: Int,
        val outgoingConnectionStyle: ConnectionStyle = ConnectionStyle.Normal,
    )

    private enum class ConnectionStyle(val beginning: String, val middle: String, val end: String) {
        Normal("--", "-", ">"),
        Invisible("~~", "~", "~")
    }

    private var nextNodeIndex = 0

    private fun node(
        idPrefix: String,
        title: String,
        outgoingConnectionStyle: ConnectionStyle = ConnectionStyle.Normal,
        extraClasses: List<String> = emptyList(),
    ): RenderingResult {
        val id = idPrefix + nextNodeIndex++

        return RenderingResult(
            rendered = "$indent$id[\"$title\"]\n${indent}class $id nowrapClass;" +
                    extraClasses.joinToString("") { "\n${indent}class $id $it;" },
            firstNodeId = id,
            lastNodes = listOf(LastNodeConnectionInfo(id, outgoingConnectionLength = 1, outgoingConnectionStyle)),
        )
    }

    private fun subgraph(
        idPrefix: String,
        title: String,
        contents: String,
        height: Int?,
        style: String? = null,
        direction: String? = null,
        outgoingConnectionStyle: ConnectionStyle = ConnectionStyle.Normal,
    ): RenderingResult {
        val id = idPrefix + nextNodeIndex++
        val openingLine = "${indent}subgraph $id[\"${title.replace(" ", "&nbsp;")}\"]"
        val closingLine = "${indent}end"
        val style = style?.let { withIndent { "\n${indent}style $id $it;" } } ?: ""
        val direction = direction?.let { withIndent { "\n${indent}direction $it;" } } ?: ""

        return RenderingResult(
            rendered = "$openingLine$direction$style\n$contents\n$closingLine",
            firstNodeId = id,
            lastNodes = listOf(LastNodeConnectionInfo(id, height ?: 1, outgoingConnectionStyle)),
        )
    }

    private fun LoggingElement.render(indexWithinParent: Int): RenderingResult? {
        return when (this) {
            is CallElement -> render(indexWithinParent)
            is CandidateElement -> render(indexWithinParent)
            is VariableReadinessBlockElement -> render()
            is StageBlockElement -> render()
            is InitialConstraintElement -> render()
            is IncorporatedConstraintElement -> render()
            is ConstraintSubstitutionElement -> render()
            is ErrorElement -> render()
            is NewVariableElement -> render()
            is VariableReadinessElement -> render()
        }
    }

    private inline fun <T : LoggingElement, R> List<T>.renderListWithoutJoining(
        transformRender: (T, RenderingResult) -> R,
    ): List<R>? {
        var index = 0

        return mapNotNull {
            it.render(index)?.let { rendered -> transformRender(it, rendered) }?.also { index++ }
        }.takeIf { it.isNotEmpty() }
    }

    private fun List<LoggingElement>.renderList(delimiter: String = "\n\n"): RenderingResult? =
        renderListWithoutJoining { _, rendered -> rendered }?.join(delimiter)

    private inline fun List<RenderingResult>.join(
        delimiter: String = "\n\n",
        connect: (RenderingResult, RenderingResult) -> String,
    ): RenderingResult? {
        if (isEmpty()) return null

        val renderedWithConnectors = mutableListOf(first().rendered)

        for (it in 1 until size) {
            val previous = this[it - 1]
            val next = this[it]

            renderedWithConnectors.add(connect(previous, next))
            renderedWithConnectors.add(next.rendered)
        }

        return RenderingResult(
            rendered = renderedWithConnectors.joinToString(delimiter),
            firstNodeId = first().firstNodeId,
            lastNodes = last().lastNodes,
        )
    }

    private fun connectLastToFirst(previous: LastNodeConnectionInfo, nextFirstNodeId: String): String {
        val connection = previous.outgoingConnectionStyle.run {
            beginning + middle.repeat(previous.outgoingConnectionLength - 1) + end
        }
        return "$indent${previous.id} $connection $nextFirstNodeId"
    }

    private fun connectLastsToFirst(previous: RenderingResult, next: RenderingResult): String =
        previous.lastNodes.joinToString("\n") { node ->
            connectLastToFirst(node, next.firstNodeId)
        }

    private fun List<RenderingResult>.join(delimiter: String = "\n\n"): RenderingResult? =
        join(delimiter, ::connectLastsToFirst)

    private fun CallElement.render(indexWithinParent: Int): RenderingResult? {
        val number = indexWithinParent + 1
        val contents = withIndent {
            val nonEmptyCandidates = candidates.renderList() ?: return null
            val node = node("call", "Call $number<br>" + formatCode(call), extraClasses = listOf("callStyle"))
            listOf(node, nonEmptyCandidates).join("\n\n") ?: return null
        }
        // These subgraph wrappers improve horizontal balancing of the calls.
        return subgraph(
            idPrefix = "callGraph",
            title = "&nbsp;",
            contents = contents.rendered,
            height = 1,
            style = "fill:#fefefe,stroke:#aeaeae,stroke-width:1px",
            outgoingConnectionStyle = ConnectionStyle.Invisible,
        )
    }

    private fun CandidateElement.render(indexWithinParent: Int): RenderingResult? {
        val deduplicatedConstraints = recordUniqueConstraints()
        val forwardEdges = calculateForwardEdges(deduplicatedConstraints)

        val nonEmptyBlocks = withCandidate(deduplicatedConstraints, forwardEdges) {
            blocks.renderList("\n\n") ?: return null
        }

        val number = indexWithinParent + 1
        val signatureRenderer = FirRenderer.forReadability()

        @OptIn(SymbolInternals::class)
        val signature = signatureRenderer.renderElementAsString(candidate.symbol.fir)
        val node = node(
            idPrefix = "candidate",
            title = "Candidate $number: " + formatCode(candidate.symbol) + "<br><br>" + formatCode(signature),
            extraClasses = listOf("candidateStyle"),
        )
        return listOf(node, nonEmptyBlocks).join("\n\n")
    }

    private fun CandidateElement.recordUniqueConstraints(): Map<String, ConstraintElement> = blocks
        .flatMap { (it as? StageBlockElement)?.elements.orEmpty() }
        .filterIsInstance<ConstraintElement>()
        .asReversed() // `associateBy()` prioritizes later occurrences
        .associateBy { it.renderRelation() }

    private fun CandidateElement.calculateForwardEdges(seenConstraints: Map<String, ConstraintElement>): Map<String, Set<ConstraintElement>> {
        val allConstraints = blocks.filterIsInstance<StageBlockElement>()
            .flatMap { it.elements }.filterIsInstance<ConstraintElement>()
            .filter { seenConstraints[it.renderRelation()] == it }

        return buildMap<_, MutableSet<ConstraintElement>> {
            for (it in allConstraints) {
                for (previous in it.previous) {
                    getOrPut(previous.renderRelation()) { mutableSetOf() }.add(it)
                }
            }
        }
    }

    private fun VariableReadinessBlockElement.render(): RenderingResult? {
        val entries = withIndent { constraints.renderList() } ?: return null
        return subgraph("readiness", "Readiness of Variables", entries.rendered, height = 1, direction = "TB")
    }

    private fun ConstraintElement.toKnownNodeResult(): RenderingResult = printingOptions.judgmentNodeCache[renderRelation()]
        ?: error("No node for ${renderRelation()} has been registered")

    private fun StageBlockElement.render(): RenderingResult? {
        val mainTitle = node("stage", name, outgoingConnectionStyle = ConnectionStyle.Invisible)
        val titleStyle = "\n${indent}class ${mainTitle.firstNodeId} stageStyle;"
        val titledGraphs = elements.partitionIntoTitledGraphs(mainTitle).takeIf { it.isNotEmpty() } ?: return null

        val first = titledGraphs.first()
        val rest = titledGraphs.drop(1)

        val firstRendered = first.renderConstraintsOfStage(titleStyle)
        val restRendered = rest.map { it.renderConstraintsOfStage() ?: it.title }
            .takeIf { it.isNotEmpty() }?.join("\n\n")

        if (restRendered == null) {
            return firstRendered
        }

        val mainTitleGraph = firstRendered ?: RenderingResult(
            rendered = first.title.rendered + titleStyle,
            firstNodeId = first.title.firstNodeId,
            lastNodes = first.title.lastNodes,
        )
        return listOf(mainTitleGraph, restRendered).join("\n\n")
    }

    private class TitledGraph(
        val title: RenderingResult,
        val constraints: MutableList<ConstraintElement> = mutableListOf(),
    )

    private fun List<StageElement>.partitionIntoTitledGraphs(first: RenderingResult): List<TitledGraph> {
        val graphs = mutableListOf(TitledGraph(first))
        var index = 0

        for (it in this) {
            if (it !is ConstraintElement) {
                val rendered = it.render(index)?.also { index++ }
                    ?.mapLasts { it.copy(outgoingConnectionStyle = ConnectionStyle.Invisible) }
                    ?: continue
                graphs.add(TitledGraph(rendered))
            } else {
                graphs.last().constraints.add(it)
            }
        }

        return graphs
    }

    private fun TitledGraph.renderConstraintsOfStage(titleStyle: String = ""): RenderingResult? {
        val ownConstraints = constraints.calculateOwnConstraints()

        fun ConstraintElement.hasPreviousFromThisStage(): Boolean =
            previous.any { it in ownConstraints }

        fun ConstraintElement.hasNextFromThisStage(): Boolean =
            printingOptions.forwardEdges[renderRelation()].orEmpty().any { it in ownConstraints }

        val ranks = calculateRanks(constraints)
        val maxRank = ranks.values.maxOrNull() ?: 0
        val tailNodes = mutableListOf<RenderingResult>()

        val rendered = constraints.renderListWithoutJoining { element, rendered ->
            val rank = ranks[element.renderRelation()] ?: error("Missing rank")

            val connections = element.previous.map {
                val previousRank = ranks[it.renderRelation()] ?: (rank - 1)
                val previous = it.toKnownNodeResult().lastNodes.first().copy(outgoingConnectionLength = rank - previousRank)
                connectLastToFirst(previous, rendered.firstNodeId)
            }
            if (!element.hasNextFromThisStage()) {
                tailNodes += rendered.mapLasts { it.copy(outgoingConnectionLength = maxRank - rank + 1) }
            }
            val titleConnection = when {
                !element.hasPreviousFromThisStage() -> connectLastToFirst(
                    title.lastNodes.first().copy(outgoingConnectionLength = rank + 1),
                    rendered.firstNodeId,
                )
                else -> null
            }

            listOfNotNull(rendered.rendered, titleConnection, *connections.toTypedArray()).joinToString("\n")
        }

        val joined = rendered?.joinToString("\n") ?: return null

        return RenderingResult(
            rendered = title.rendered + titleStyle + "\n" + joined,
            firstNodeId = title.firstNodeId,
            lastNodes = tailNodes.flatMap { it.lastNodes }.map { it.copy(outgoingConnectionStyle = ConnectionStyle.Invisible) },
        )
    }

    private fun calculateRanks(constraints: List<ConstraintElement>): MutableMap<String, Int> {
        val ranks = mutableMapOf<String, Int>()
        val rankToCount = mutableListOf<Int>()

        fun getCountForRank(rank: Int) = rankToCount.getOrNull(rank)
            ?: 0.also(rankToCount::add)

        for (element in constraints) {
            if (printingOptions.seenConstraints[element.renderRelation()] != element) continue
            var rank = 1 + (element.previous.maxOfOrNull { ranks[it.renderRelation()] ?: -1 } ?: -1)

            while (getCountForRank(rank) >= maxElementsPerRank) {
                rank++
            }

            rankToCount[rank] = getCountForRank(rank) + 1
            ranks[element.renderRelation()] = rank
        }

        return ranks
    }

    private fun List<StageElement>.calculateOwnConstraints(): Set<ConstraintElement> =
        filterIsInstance<ConstraintElement>().mapTo(mutableSetOf()) { it }

    private fun ConstraintElement.renderRelation() = when (this) {
        is InitialConstraintElement -> constraint
        is IncorporatedConstraintElement -> constraint
        is ConstraintSubstitutionElement -> constraint
    }

    private fun InitialConstraintElement.render(): RenderingResult? {
        if (printingOptions.seenConstraints[constraint] != this) return null
        val position = sanitizeFqNames(position)
        return node("constraint", formatConstraint(constraint) + "<br> <i>from ${position}</i>")
            .also { printingOptions.judgmentNodeCache.putIfAbsent(renderRelation(), it) }
    }

    private fun IncorporatedConstraintElement.render(): RenderingResult? {
        if (printingOptions.seenConstraints[constraint] != this) return null
        return node("constraint", formatConstraint(constraint))
            .also { printingOptions.judgmentNodeCache.putIfAbsent(renderRelation(), it) }
    }

    private fun ConstraintSubstitutionElement.render(): RenderingResult? {
        if (printingOptions.seenConstraints[constraint] != this) return null
        return node("constraint", formatConstraint(constraint))
            .also { printingOptions.judgmentNodeCache.putIfAbsent(renderRelation(), it) }
    }

    private fun ErrorElement.render(): RenderingResult =
        node("error", "<b>${renderErrorTitle(error)}<b>")

    private fun NewVariableElement.render(): RenderingResult =
        node("newVariable", renderVariableTitle(variable))

    private fun VariableReadinessElement.render(): RenderingResult =
        node("variableReadiness", formatCode(variable) + " is " + formatCode(readiness))

    private var printingOptions = PrintingOptions()
    private val indent get() = printingOptions.indent

    private data class PrintingOptions(
        val indent: String = "",
        val seenConstraints: Map<String, ConstraintElement> = mutableMapOf(),
        val judgmentNodeCache: MutableMap<String, RenderingResult> = mutableMapOf(),
        val forwardEdges: Map<String, Set<ConstraintElement>> = emptyMap(),
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

    private inline fun <T> withCandidate(
        seenConstraints: Map<String, ConstraintElement>,
        forwardEdges: Map<String, Set<ConstraintElement>>,
        block: () -> T,
    ): T {
        val oldOptions = printingOptions
        return try {
            printingOptions = oldOptions.copy(
                seenConstraints = seenConstraints,
                judgmentNodeCache = mutableMapOf(),
                forwardEdges = forwardEdges,
            )
            block()
        } finally {
            printingOptions = oldOptions
        }
    }
}

/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.frontend.fir.handlers

import org.jetbrains.kotlin.config.ConstraintsDumpFormat
import org.jetbrains.kotlin.fir.resolve.inference.constraintsLogger
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.frontend.fir.FirOutputArtifact
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.assertions
import org.jetbrains.kotlin.test.services.configuration.constraintsDumpFormats
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.test.utils.constraintslogger.MarkdownConstraintsDumper
import org.jetbrains.kotlin.test.utils.constraintslogger.MermaidConstraintsDumper
import org.jetbrains.kotlin.test.utils.originalTestDataFile
import org.jetbrains.kotlin.test.utils.withExtension
import java.io.File

class FirConstraintsDumpHandler(
    testServices: TestServices
) : FirAnalysisHandler(testServices) {
    override val directiveContainers: List<DirectivesContainer>
        get() = listOf(FirDiagnosticsDirectives)

    private val modulePartDumps = mutableMapOf<ConstraintsDumpFormat, MutableList<String>>()

    override fun processModule(module: TestModule, info: FirOutputArtifact) {
        if (FirDiagnosticsDirectives.DUMP_CONSTRAINTS !in testServices.moduleStructure.allDirectives) return

        for (format in testServices.moduleStructure.allDirectives.constraintsDumpFormats) {
            val dumper = when (format) {
                ConstraintsDumpFormat.MARKDOWN -> MarkdownConstraintsDumper()
                ConstraintsDumpFormat.MERMAID -> MermaidConstraintsDumper()
            }

            for (part in info.partsForDependsOnModules) {
                modulePartDumps.getOrPut(format) { mutableListOf() } += part.session.constraintsLogger
                    ?.let { dumper.renderDump(it.topLevelElements, part.session) }
                    ?: continue
            }
        }
    }

    override fun processAfterAllModules(someAssertionWasFailed: Boolean) {
        ensureNoStrayDumps()
        if (FirDiagnosticsDirectives.DUMP_CONSTRAINTS !in testServices.moduleStructure.allDirectives) return

        for (format in testServices.moduleStructure.allDirectives.constraintsDumpFormats) {
            val dumpFile = format.file

            // Check if the logger even exists as we have runners that don't set it because they don't need it.
            if (modulePartDumps.isNotEmpty()) {
                testServices.assertions.assertEqualsToFile(dumpFile, modulePartDumps[format]?.joinToString("\n\n") ?: "")
            }
        }
    }

    private fun ensureNoStrayDumps() {
        val allowedFormats = testServices.moduleStructure.allDirectives.constraintsDumpFormats

        for (format in ConstraintsDumpFormat.entries) {
            if (format !in allowedFormats) {
                testServices.assertions.assertFileDoesntExist(format.file) { "`$format` dump file detected but '${FirDiagnosticsDirectives.DUMP_CONSTRAINTS}' is not set to emit it." }
            }
        }
    }

    private val ConstraintsDumpFormat.file: File
        get() {
            // K1 doesn't support constraint dumps, no need to care about ".fir.inference.md"
            val originalFile = testServices.moduleStructure.originalTestDataFiles.first().originalTestDataFile
            val dumpFile = originalFile.withExtension(fileExtension)
            return dumpFile
        }

    private val ConstraintsDumpFormat.fileExtension: String
        get() = when (this) {
            ConstraintsDumpFormat.MARKDOWN -> ".inference.md"
            ConstraintsDumpFormat.MERMAID -> ".inference.mmd"
        }
}

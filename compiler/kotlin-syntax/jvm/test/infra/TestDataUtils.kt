/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package infra

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

object TestDataUtils {
    val userDir = File(System.getProperty("user.dir") ?: ".").parent
    val testDataDirs: List<Path> = listOf(
        Paths.get(userDir, "testData"),
        Paths.get(userDir, "tests-spec/testData"),
    )

    // TODO: for some reason, it's not possible to depend on `:compiler:test-infrastructure-utils` here
    // See org.jetbrains.kotlin.codeMetaInfo.CodeMetaInfoParser
    private val openingDiagnosticRegex = """(<!([^"]*?((".*?")(, ".*?")*?)?[^"]*?)!>)""".toRegex()
    private val closingDiagnosticRegex = """(<!>)""".toRegex()

    private val xmlLikeTagsRegex = """(</?(?:selection|expr|caret)>)""".toRegex()

    private val allMetadataRegex =
        """(${closingDiagnosticRegex.pattern}|${openingDiagnosticRegex.pattern}|${xmlLikeTagsRegex.pattern})""".toRegex()

    fun checkKotlinFiles(kotlinFileChecker: (String, Path) -> Unit) {
        for (testDataDir in testDataDirs) {
            testDataDir.toFile().walkTopDown()
                .filter { it.isFile && it.extension.let { ext -> ext == "kt" || ext == "kts" || ext == "nkt" } }
                .forEach {
                    val text = it.readText()
                    val refinedText = text.replace(allMetadataRegex, "")
                    kotlinFileChecker(refinedText, it.toPath())
                }
        }
    }
}


/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.kmp.parser

import fleet.com.intellij.platform.syntax.SyntaxElementType
import fleet.com.intellij.platform.syntax.parser.SyntaxTreeBuilder
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.utils.KotlinParsing
import org.jetbrains.kotlin.kmp.parser.utils.SemanticWhitespaceAwareSyntaxBuilderImpl

class KotlinParser(val isFile: Boolean, val isLazy: Boolean) : AbstractParser() {
    override val whitespaces: Set<SyntaxElementType> = KtTokens.WHITESPACES
    override val comments: Set<SyntaxElementType> = KtTokens.COMMENTS

    override fun parse(builder: SyntaxTreeBuilder) {
        val whitespaceAwareBuilder = SemanticWhitespaceAwareSyntaxBuilderImpl(builder)
        val builder = if (isLazy) {
            KotlinParsing.createForTopLevel(whitespaceAwareBuilder)
        } else {
            KotlinParsing.createForTopLevelNonLazy(whitespaceAwareBuilder)
        }
        if (isFile) {
            builder.parseFile()
        } else {
            builder.parseScript()
        }
    }
}
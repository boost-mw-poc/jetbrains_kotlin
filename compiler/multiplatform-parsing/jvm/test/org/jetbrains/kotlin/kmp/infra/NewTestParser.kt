/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.kmp.infra

import fleet.com.intellij.platform.syntax.SyntaxElementType
import fleet.com.intellij.platform.syntax.parser.SyntaxTreeBuilder
import fleet.com.intellij.platform.syntax.parser.SyntaxTreeBuilderFactory
import fleet.com.intellij.platform.syntax.parser.prepareProduction
import fleet.com.intellij.platform.syntax.util.lexer.LexerBase
import org.jetbrains.kotlin.kmp.lexer.KDocLexer
import org.jetbrains.kotlin.kmp.lexer.KDocTokens
import org.jetbrains.kotlin.kmp.lexer.KotlinLexer
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.AbstractParser
import org.jetbrains.kotlin.kmp.parser.KDocLinkParser
import org.jetbrains.kotlin.kmp.parser.KDocParser
import org.jetbrains.kotlin.kmp.parser.KotlinParser
import java.util.ArrayDeque

sealed class NewParserTestNode

class NewParserTestToken(val token: SyntaxElementType) : NewParserTestNode()

class NewParserTestParseNode(val production: SyntaxTreeBuilder.Production) : NewParserTestNode()

class NewTestParser(parseMode: ParseMode) : AbstractTestParser<NewParserTestNode>(parseMode) {
    override fun parse(fileName: String, text: String): TestParseNode<out NewParserTestNode> {
        return if (parseMode == ParseMode.KDocOnly) {
            parseKDocOnlyNodes(text).wrapRootsIfNeeded(text.length)
        } else {
            val isLazy = parseMode == ParseMode.NoCollapsableAndKDoc
            val parser = KotlinParser(isScript(fileName), isLazy)
            parseToTestParseElement(text, 0, KotlinLexer(), parser)
        }
    }

    private fun parseKDocOnlyNodes(text: String): List<TestParseNode<out NewParserTestNode>> {
        val kotlinLexer = KotlinLexer()
        kotlinLexer.start(text)

        return buildList {
            var kotlinTokenType = kotlinLexer.getTokenType()
            while (kotlinTokenType != null) {
                if (kotlinTokenType == KtTokens.DOC_COMMENT) {
                    add(
                        parseToTestParseElement(
                            kotlinLexer.getTokenText(),
                            kotlinLexer.getTokenStart(),
                            KDocLexer(),
                            KDocParser,
                        )
                    )
                }

                kotlinLexer.advance()
                kotlinTokenType = kotlinLexer.getTokenType()
            }
        }
    }

    private fun convertToTestParseElement(builder: SyntaxTreeBuilder, start: Int): TestParseNode<out NewParserTestNode> {
        val productions = prepareProduction(builder).productionMarkers
        val tokens = builder.tokens

        val childrenStack = ArrayDeque<MutableList<TestParseNode<out NewParserTestNode>>>().apply {
            add(mutableListOf())
        }
        var prevTokenIndex = 0
        var lastErrorTokenIndex = -1

        fun MutableList<TestParseNode<out NewParserTestNode>>.appendLeafElements(lastTokenIndex: Int) {
            for (leafTokenIndex in prevTokenIndex until lastTokenIndex) {
                val tokenType = tokens.getTokenType(leafTokenIndex)!!
                val tokenStart = tokens.getTokenStart(leafTokenIndex) + start
                val tokenEnd = tokens.getTokenEnd(leafTokenIndex) + start

                // LightTree and PSI builders ignores empty leaf tokens by default
                if (tokenStart == tokenEnd) {
                    continue
                }

                val node = when (tokenType) {
                    // `MARKDOWN_LINK` only can be encountered inside KDoc
                    KDocTokens.MARKDOWN_LINK if (parseMode.isParseKDoc) -> {
                        parseToTestParseElement(
                            tokens.getTokenText(leafTokenIndex)!!,
                            tokenStart,
                            KotlinLexer(),
                            KDocLinkParser,
                        )
                    }
                    KtTokens.DOC_COMMENT if (parseMode.isParseKDoc) -> {
                        parseToTestParseElement(
                            tokens.getTokenText(leafTokenIndex)!!,
                            tokenStart,
                            KDocLexer(),
                            KDocParser,
                        )
                    }
                    else -> {
                        TestParseNode(
                            tokenType.toString(),
                            tokenStart,
                            tokenEnd,
                            NewParserTestToken(tokenType),
                            emptyList()
                        )
                    }
                }

                add(node)
            }
            prevTokenIndex = lastTokenIndex
        }

        for (productionIndex in 0 until productions.size) {
            val production = productions.getMarker(productionIndex)
            val isEndMarker = productions.isDoneMarker(productionIndex)
            val isErrorMarker = production.isErrorMarker()

            when {
                isEndMarker -> {
                    val children = childrenStack.pop().also {
                        it.appendLeafElements(production.getEndTokenIndex())
                    }
                    childrenStack.peek().add(
                        TestParseNode(
                            production.getNodeType().toString(),
                            production.getStartOffset(),
                            production.getEndOffset(),
                            NewParserTestParseNode(production),
                            if (production.isCollapsed()) emptyList() else children,
                        )
                    )
                }

                isErrorMarker -> {
                    val errorTokenIndex = production.getStartTokenIndex()
                    if (errorTokenIndex == lastErrorTokenIndex) {
                        // Prevent inserting of duplicated error elements
                        continue
                    } else {
                        lastErrorTokenIndex = errorTokenIndex
                    }
                    childrenStack.peek().let {
                        it.appendLeafElements(errorTokenIndex)
                        it.add(
                            TestParseNode(
                                production.getNodeType().toString(),
                                production.getStartOffset(),
                                production.getEndOffset(),
                                NewParserTestParseNode(production),
                                emptyList(),
                            )
                        )
                    }
                }

                else -> {
                    // start marker
                    childrenStack.peek().appendLeafElements(production.getStartTokenIndex())
                    childrenStack.push(mutableListOf())
                }
            }
        }
        return childrenStack.single().single()
    }

    private fun parseToTestParseElement(
        charSequence: CharSequence,
        start: Int,
        lexer: LexerBase,
        parser: AbstractParser,
    ): TestParseNode<out NewParserTestNode> {
        val syntaxTreeBuilder = SyntaxTreeBuilderFactory.builder(
            charSequence,
            whitespaces = parser.whitespaces,
            comments = parser.comments,
            lexer
        ).withStartOffset(start)
            .withWhitespaceOrCommentBindingPolicy(parser.whitespaceOrCommentBindingPolicy)
            .build()

        parser.parse(syntaxTreeBuilder)

        return convertToTestParseElement(syntaxTreeBuilder, start)
    }
}
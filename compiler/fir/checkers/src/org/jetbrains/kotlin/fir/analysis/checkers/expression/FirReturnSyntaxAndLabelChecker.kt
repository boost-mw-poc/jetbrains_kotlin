/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.expression

import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtRealSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.diagnostics.ConeSimpleDiagnostic
import org.jetbrains.kotlin.fir.diagnostics.DiagnosticKind
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.impl.FirSingleExpressionBlock
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.utils.addToStdlib.runIf

object FirReturnSyntaxAndLabelChecker : FirReturnExpressionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirReturnExpression) {
        val source = expression.source
        if (source?.kind is KtFakeSourceElementKind.ImplicitReturn || source?.kind is KtFakeSourceElementKind.DelegatedPropertyAccessor) return

        val labeledElement = expression.target.labeledElement
        val targetSymbol = labeledElement.symbol

        when (((labeledElement as? FirErrorFunction)?.diagnostic as? ConeSimpleDiagnostic)?.kind) {
            DiagnosticKind.NotAFunctionLabel -> FirErrors.NOT_A_FUNCTION_LABEL
            DiagnosticKind.UnresolvedLabel -> FirErrors.UNRESOLVED_LABEL
            else -> returnNotAllowedFactoryOrNull(targetSymbol)
        }?.let { reporter.reportOn(source, it)}

        checkBuiltInSuspend(targetSymbol, source)
    }

    private fun FirDeclaration.hasExpressionBody(): Boolean {
        return this is FirFunction && this.body is FirSingleExpressionBlock
    }

    context(context: CheckerContext)
    private fun returnNotAllowedFactoryOrNull(targetSymbol: FirFunctionSymbol<*>): KtDiagnosticFactory0? {
        // The logic with inline lambdas is wrong, but it has been with us forever KT-22786
        var inlineLambdaSeen = false

        for (containingDeclaration in context.containingDeclarations.asReversed()) {
            @OptIn(SymbolInternals::class)
            when (containingDeclaration) {
                // return from member of local class or anonymous object
                is FirClass -> return FirErrors.RETURN_NOT_ALLOWED
                is FirFunction -> {
                    when {
                        containingDeclaration.symbol == targetSymbol -> {
                            return runIf(!inlineLambdaSeen && targetSymbol.fir.hasExpressionBody()) {
                                FirErrors.RETURN_IN_FUNCTION_WITH_EXPRESSION_BODY
                            }
                        }
                        containingDeclaration is FirAnonymousFunction -> {
                            if (!containingDeclaration.inlineStatus.returnAllowed) {
                                return FirErrors.RETURN_NOT_ALLOWED
                            } else {
                                inlineLambdaSeen = true
                            }
                        }
                        else -> return FirErrors.RETURN_NOT_ALLOWED
                    }
                }
                is FirProperty -> if (!containingDeclaration.isLocal) return FirErrors.RETURN_NOT_ALLOWED
                is FirValueParameter -> return FirErrors.RETURN_NOT_ALLOWED
                else -> {}
            }
        }
        return null
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkBuiltInSuspend(targetSymbol: FirFunctionSymbol<FirFunction>, source: KtSourceElement?) {
        if (targetSymbol !is FirAnonymousFunctionSymbol) return
        val label = targetSymbol.label
        if (label?.source?.kind is KtRealSourceElementKind) return

        val functionCall = context.callsOrAssignments.asReversed().find {
            it is FirFunctionCall &&
                    (it.calleeReference.toResolvedNamedFunctionSymbol())?.callableId ==
                    FirSuspendCallChecker.KOTLIN_SUSPEND_BUILT_IN_FUNCTION_CALLABLE_ID
        }
        if (functionCall is FirFunctionCall &&
            functionCall.arguments.any {
                it is FirAnonymousFunctionExpression && it.anonymousFunction.symbol == targetSymbol
            }
        ) {
            reporter.reportOn(source, FirErrors.RETURN_FOR_BUILT_IN_SUSPEND)
        }
    }
}

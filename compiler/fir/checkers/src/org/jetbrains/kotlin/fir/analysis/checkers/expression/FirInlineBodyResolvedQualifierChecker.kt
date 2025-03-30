/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.expression

import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory2
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.isExplicitParentOfResolvedQualifier
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol

object FirInlineBodyResolvedQualifierChecker : FirResolvedQualifierChecker(MppCheckerKind.Common) {
    override fun check(expression: FirResolvedQualifier, context: CheckerContext, reporter: DiagnosticReporter) {
        val inlineFunctionBodyContext = context.inlineFunctionBodyContext ?: return
        val accessedClass = expression.symbol ?: return
        val source = expression.source ?: return
//        if (accessedClass.isCompanion && !expression.isExplicitParentOfResolvedQualifier(context)) {
        if (!expression.isExplicitParentOfResolvedQualifier(context)) {
            inlineFunctionBodyContext.checkAccessedDeclaration(
                source, expression, accessedClass, accessedClass.visibility, context, reporter,
//                { getKtDiagnosticFactory(context, accessedClass) }
            )
        }
    }
// TODO: also check in tests for internal inline + private class. This produces another type of error
    private fun getKtDiagnosticFactory(context: CheckerContext, accessedClass: FirClassLikeSymbol<*>): KtDiagnosticFactory2<FirBasedSymbol<*>, FirBasedSymbol<*>> {
        if (!context.languageVersionSettings.supportsFeature(LanguageFeature.ForbidNonPublicTypeUseInPublicInlineFunctions)) {
            if (!accessedClass.isCompanion) {
                return FirErrors.NON_PUBLIC_TYPE_USE_FROM_PUBLIC_INLINE_DEPRECATION
            }
        }
        return FirErrors.NON_PUBLIC_CALL_FROM_PUBLIC_INLINE
    }
}

/*
Поведение:
- если фича выключена И ошибка новая - депрекейшн эрор
- иначе - всё как раньше
 */

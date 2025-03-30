/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.expression

import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory2
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.toClassLikeSymbol
import org.jetbrains.kotlin.fir.analysis.checkers.type.FirResolvedTypeRefChecker
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef

internal object FirInlineBodyNonPublicTypeRefChecker : FirResolvedTypeRefChecker(MppCheckerKind.Common) {
    override fun check(
        typeRef: FirResolvedTypeRef,
        context: CheckerContext,
        reporter: DiagnosticReporter,
    ) {
        val inlineFunctionBodyContext = context.inlineFunctionBodyContext ?: return
        val source = typeRef.source ?: error("Expected source to be not null for $typeRef")
        val targetClassLikeSymbol = typeRef.toClassLikeSymbol(context.session) ?: return
        val containingStatement = context.containingElements.filterIsInstance<FirStatement>().lastOrNull() ?: return

        inlineFunctionBodyContext.checkAccessedDeclaration(
            source,
            containingStatement,
            targetClassLikeSymbol,
            targetClassLikeSymbol.visibility,
            context,
            reporter,
//            ktDiagnosticFactory = { getKtDiagnosticFactory(context) }
        )
    }

    // TODO: написать тесты с выключенным и включенным флагом
    private fun getKtDiagnosticFactory(context: CheckerContext): KtDiagnosticFactory2<FirBasedSymbol<*>, FirBasedSymbol<*>> {
        if (!context.languageVersionSettings.supportsFeature(LanguageFeature.ForbidUsingExtensionPropertyTypeParameterInDelegate)) {
            return FirErrors.NON_PUBLIC_TYPE_USE_FROM_PUBLIC_INLINE_DEPRECATION
        }
        return FirErrors.NON_PUBLIC_CALL_FROM_PUBLIC_INLINE
    }
}

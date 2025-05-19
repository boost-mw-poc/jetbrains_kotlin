/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.utils.constraintslogger

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRefsOwner
import org.jetbrains.kotlin.fir.resolve.inference.*
import org.jetbrains.kotlin.fir.resolve.inference.FirConstraintsLogger.Companion.sanitizeFqNames
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.resolve.calls.inference.model.ConstraintSystemError
import org.jetbrains.kotlin.types.model.TypeVariableMarker

abstract class FirConstraintsDumper {
    abstract fun renderDump(sessionsToLoggers: Map<FirSession, FirConstraintsLogger>): String

    protected abstract fun monospace(text: String): String

    protected fun ConeTypeParameterBasedTypeVariable.renderReferenceToPrototype(): String {
        @OptIn(SymbolInternals::class)
        val fir = typeParameterSymbol.containingDeclarationSymbol.fir as? FirTypeParameterRefsOwner
        val index = fir?.typeParameters?.indexOfFirst { it.symbol == typeParameterSymbol }

        return when {
            index == null || index == -1 -> monospace(typeParameterSymbol.containingDeclarationSymbol.toString())
            else -> monospace(typeParameterSymbol.containingDeclarationSymbol.toString()) + "s parameter $index"
        }
    }

    protected fun renderErrorTitle(error: ConstraintSystemError): String {
        val naiveString = sanitizeFqNames(error.toString())
        val customRepresentation = when {
            naiveString == error::class.simpleName -> ""
            else -> ": " + monospace(naiveString)
        }
        return error::class.simpleName + customRepresentation
    }

    protected fun renderVariableTitle(variable: TypeVariableMarker): String {
        val variableInfo = when (variable) {
            is ConeTypeParameterBasedTypeVariable -> " for " + variable.renderReferenceToPrototype()
            is ConeTypeVariableForLambdaParameterType -> " for lambda parameter"
            is ConeTypeVariableForLambdaReturnType -> " for lambda return type"
            is ConeTypeVariableForPostponedAtom -> " for postponed argument"
            else -> ""
        }
        return "New " + monospace(variable.toString()) + variableInfo
    }
}

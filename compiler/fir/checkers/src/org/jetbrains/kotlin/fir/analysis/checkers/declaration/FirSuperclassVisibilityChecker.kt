/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.declaration

import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.EffectiveVisibility
import org.jetbrains.kotlin.descriptors.isInterface
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.processAllDeclarations
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.declarations.utils.effectiveVisibility
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.resolve.getContainingDeclaration
import org.jetbrains.kotlin.fir.resolve.toClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeTypeProjection
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.fir.types.typeContext
import kotlin.reflect.full.memberProperties

class IEReporter(
    private val source: KtSourceElement?,
    private val context: CheckerContext,
    private val reporter: DiagnosticReporter,
    private val error: KtDiagnosticFactory1<String>,
) {
    operator fun invoke(v: IEData) {
        val dataStr = buildList {
            add(serializeLocation())
            addAll(serializeData(v))
        }.joinToString("; ")
        val str = "$borderTag $dataStr $borderTag"
        reporter.reportOn(source, error, str, context)
    }

    private val borderTag: String = "ROMANV"

    @OptIn(SymbolInternals::class)
    private fun serializeLocation(): String {
        val filename = context.containingFileSymbol?.sourceFile?.run { path ?: name } ?: "unknown"
        val mapping = context.containingFileSymbol?.fir?.sourceFileLinesMapping
        val loc = source?.startOffset?.let { mapping?.getLineAndColumnByOffset(it) }
        return "loc: ${filename}:${loc?.first}:${loc?.second}"
    }

    private fun serializeData(v: IEData): List<String> = buildList {
        v::class.memberProperties.forEach { property ->
            add("${property.name}: ${property.getter.call(v)}")
        }
    }
}


data class IEData(
    val name: String? = null,
    val visibility: String? = null,
    val topLevel: Boolean? = null,
    val effectiveVisibility: String? = null,
    val exposes: Boolean? = null,
    val exposingFunction: String? = null,
    val minimalRequiredVisibility: String? = null,
    val error: String? = null,
)


object FirSuperclassVisibilityChecker : FirClassChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirClass) {
        val report = IEReporter(declaration.source, context, reporter, FirErrors.MY_ERROR)
        if (declaration !is FirRegularClass) return
        if (!declaration.classKind.isInterface) return
        try {
            val name = declaration.classId.asFqNameString()
            val visibility = declaration.visibility.toString()
            val topLevel = declaration.getContainingClassSymbol() == null
            val selfEffVis = selfEffVis(declaration)
            val exposingFunctions = mutableListOf<String>()
            var minimalRequiredVisibility: EffectiveVisibility = EffectiveVisibility.Public
            declaration.processAllDeclarations(context.session) {
                when (it) {
                    is FirNamedFunctionSymbol -> {
                        var effVis: EffectiveVisibility = EffectiveVisibility.Public
                        effVis = it.valueParameterSymbols.fold(effVis) { acc, new -> lbWithType(new.resolvedReturnType, acc) }
                        effVis = lbWithType(it.resolvedReturnType, effVis)
                        if (effVis != EffectiveVisibility.Public) exposingFunctions.add(it.name.toString())
                        minimalRequiredVisibility =
                            minimalRequiredVisibility.lowerBound(effVis, context.session.typeContext)
                    }
                    is FirPropertySymbol -> {
                        var effVis: EffectiveVisibility = EffectiveVisibility.Public
                        effVis = lbWithType(it.resolvedReturnType, effVis)
                        if (effVis != EffectiveVisibility.Public) exposingFunctions.add(it.name.toString())
                        minimalRequiredVisibility =
                            minimalRequiredVisibility.lowerBound(effVis, context.session.typeContext)
                    }
                }
            }
            report(
                IEData(
                    name = name,
                    visibility = visibility,
                    topLevel = topLevel,
                    effectiveVisibility = selfEffVis.toString(),
                    exposes = exposingFunctions.isNotEmpty(),
                    exposingFunction = exposingFunctions.joinToString(", ") { "'$it'" },
                    minimalRequiredVisibility = minimalRequiredVisibility.toString(),
                )
            )
        } catch (e: Exception) {
            report(IEData(error = e.message))
        }
    }

    context(context: CheckerContext)
    fun selfEffVis(declaration: FirClassLikeDeclaration): EffectiveVisibility {
        var curDecl = declaration
        var selfEffVis = declaration.effectiveVisibility
        while (true) {
            val parDecl = curDecl.getContainingDeclaration(context.session) ?: break
            selfEffVis = selfEffVis.lowerBound(parDecl.effectiveVisibility, context.session.typeContext)
            curDecl = parDecl
        }
        return selfEffVis
    }

    context(context: CheckerContext)
    fun lbWithType(typeProj: ConeTypeProjection, effVis: EffectiveVisibility): EffectiveVisibility {
        val lbEffVis = typeProj.type?.typeArguments?.fold(effVis) { acc, new -> lbWithType(new, acc) } ?: effVis
        return typeProj.type?.toClassLikeSymbol(context.session)?.effectiveVisibility?.lowerBound(lbEffVis, context.session.typeContext)
            ?: lbEffVis
    }
}

/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.declaration

import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.EffectiveVisibility
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.toClassLikeSymbol
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirAnonymousObject
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.FirTypeAlias
import org.jetbrains.kotlin.fir.declarations.processAllDeclarations
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.declarations.utils.effectiveVisibility
import org.jetbrains.kotlin.fir.declarations.utils.modality
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.resolve.getContainingDeclaration
import org.jetbrains.kotlin.fir.resolve.toClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeTypeProjection
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.renderForDebugging
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
    val subclass: String? = null,
    val subclassKind: String? = null,
    val subclassVisibility: String? = null,
    val superclass: String? = null,
    val superclassKind: String? = null,
    val superclassVisibility: String? = null,
    val superclassHasMethods: Boolean? = null,
    val exposingFunction: String? = null,
    val defaultKind: String? = null,
    val defaults: String? = null,
    val error: String? = null,
)


object FirSuperclassVisibilityChecker : FirClassChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirClass) {
        val report = IEReporter(declaration.source, context, reporter, FirErrors.MY_ERROR)
        val selfEffVis = selfEffVis(declaration)
        declaration.superTypeRefs.forEach {
            try {
                checkSupertype(it, selfEffVis) {
                    report(
                        it.copy(
                            subclass = declaration.classId.asFqNameString(),
                            subclassKind = declaration.classKind.toString(),
                            subclassVisibility = declaration.visibility.toString(),
                        )
                    )
                }
            } catch (e: Exception) {
                report(IEData(error = e.message))
            }
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
    fun checkTypeParameter(typeProj: ConeTypeProjection, selfEffVis: EffectiveVisibility, report: (IEData) -> Unit) {
        typeProj.type?.typeArguments?.forEach { checkTypeParameter(it, selfEffVis, report) }
        val classLikeSymbol = typeProj.type?.toClassLikeSymbol(context.session) ?: return
        val effVis = classLikeSymbol.effectiveVisibility
        when (effVis.relation(selfEffVis, context.session.typeContext)) {
            EffectiveVisibility.Permissiveness.LESS, EffectiveVisibility.Permissiveness.UNKNOWN -> {
                report(dataForSymbol(classLikeSymbol))
            }
            else -> return
        }
    }

    context(context: CheckerContext)
    fun checkSigType(typeProj: ConeTypeProjection, selfEffVis: EffectiveVisibility): Boolean {
        val parameters = typeProj.type?.typeArguments?.any { checkSigType(it, selfEffVis) }
        if (parameters == true) return true
        val classLikeSymbol = typeProj.type?.toClassLikeSymbol(context.session) ?: return false
        val effVis = classLikeSymbol.effectiveVisibility
        return when (effVis.relation(selfEffVis, context.session.typeContext)) {
            EffectiveVisibility.Permissiveness.LESS, EffectiveVisibility.Permissiveness.UNKNOWN -> true
            else -> false
        }
    }

    @OptIn(SymbolInternals::class)
    context(context: CheckerContext)
    fun checkSupertype(typeRef: FirTypeRef, selfEffVis: EffectiveVisibility, report: (IEData) -> Unit) {
        typeRef.coneTypeOrNull?.typeArguments?.forEach {
            checkTypeParameter(it, selfEffVis) { d -> report(d) }
        }
        val classLikeSymbol = typeRef.toClassLikeSymbol(context.session) ?: return
        val effVis = classLikeSymbol.effectiveVisibility
        when (effVis.relation(selfEffVis, context.session.typeContext)) {
            EffectiveVisibility.Permissiveness.LESS, EffectiveVisibility.Permissiveness.UNKNOWN -> {
                var exposes: String? = null
                val defaults = mutableListOf<String>()
                var defaultKind: String? = null
                when (val fir = classLikeSymbol.fir) {
                    is FirRegularClass -> fir.processAllDeclarations(context.session) {
                        when (it) {
                            is FirNamedFunctionSymbol -> {
                                if (checkSigType(it.resolvedReturnType, selfEffVis)) {
                                    exposes = it.name.toString()
                                }
                                if (it.valueParameterSymbols.any { checkSigType(it.resolvedReturnType, selfEffVis) }) {
                                    exposes = it.name.toString()
                                }
                                if (it.hasBody) {
                                    defaults.add(it.name.toString())
                                    when (defaultKind) {
                                        null -> defaultKind = "ALL"
                                        "NONE" -> defaultKind = "SOME"
                                        "ALL", "SOME" -> {}
                                        else -> error("Unreachable")
                                    }
                                } else {
                                    when (defaultKind) {
                                        null -> defaultKind = "NONE"
                                        "NONE", "SOME" -> {}
                                        "ALL" -> defaultKind = "SOME"
                                        else -> error("Unreachable")
                                    }
                                }
                            }
                            is FirPropertySymbol -> {
                                if (checkSigType(it.resolvedReturnType, selfEffVis)) {
                                    exposes = it.name.toString()
                                }
                                if (it.hasInitializer || it.hasDelegate || it.getterSymbol?.hasBody == true || it.setterSymbol?.hasBody == true) {
                                    defaults.add(it.name.toString())
                                    when (defaultKind) {
                                        null -> defaultKind = "ALL"
                                        "NONE" -> defaultKind = "SOME"
                                        "ALL", "SOME" -> {}
                                        else -> error("Unreachable")
                                    }
                                } else {
                                    when (defaultKind) {
                                        null -> defaultKind = "NONE"
                                        "NONE", "SOME" -> {}
                                        "ALL" -> defaultKind = "SOME"
                                        else -> error("Unreachable")
                                    }
                                }
                            }
                        }
                    }
                    is FirAnonymousObject -> {}
                    is FirTypeAlias -> {}
                }
                report(
                    dataForSymbol(classLikeSymbol).copy(
                        exposingFunction = exposes,
                        defaultKind = defaultKind,
                        defaults = defaults.joinToString(", ") { "'$it'" },
                    )
                )
            }
            else -> return
        }
    }

    @OptIn(SymbolInternals::class, DirectDeclarationsAccess::class)
    fun dataForSymbol(cls: FirClassLikeSymbol<*>): IEData {
        val fir = cls.fir

        return IEData(
            superclass = fir.classId.asFqNameString(),
            superclassHasMethods = when (fir) {
                is FirAnonymousObject -> null
                is FirRegularClass -> fir.declarations.isNotEmpty()
                is FirTypeAlias -> null
            },
            superclassKind = (fir as? FirClass)?.classKind.toString(),
            superclassVisibility = fir.visibility.toString(),
        )
    }
}

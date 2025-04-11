/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.low.level.api.fir.api

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.analysis.low.level.api.fir.LLResolutionFacadeService
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.diagnostics.KtPsiDiagnostic
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile

/**
 * Returns [LLResolutionFacade] which corresponds to containing module
 */
fun KaModule.getLLResolutionFacade(project: Project): LLResolutionFacade =
    LLResolutionFacadeService.getInstance(project).getLLResolutionFacade(this)


/**
 * Creates [FirBasedSymbol] by [KtDeclaration] .
 * returned [FirDeclaration]  will be resolved at least to [phase]
 *
 */
fun KtDeclaration.resolveToFirSymbol(
    llResolutionFacade: LLResolutionFacade,
    phase: FirResolvePhase = FirResolvePhase.RAW_FIR,
): FirBasedSymbol<*> {
    return llResolutionFacade.resolveToFirSymbol(this, phase)
}

/**
 * Creates [FirBasedSymbol] by [KtDeclaration] .
 * returned [FirDeclaration] will be resolved at least to [phase]
 *
 * If resulted [FirBasedSymbol] is not subtype of [S], throws [InvalidFirElementTypeException]
 */
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
inline fun <reified S : FirBasedSymbol<*>> KtDeclaration.resolveToFirSymbolOfType(
    llResolutionFacade: LLResolutionFacade,
    phase: FirResolvePhase = FirResolvePhase.RAW_FIR,
): @kotlin.internal.NoInfer S {
    val symbol = resolveToFirSymbol(llResolutionFacade, phase)
    if (symbol !is S) {
        throwUnexpectedFirElementError(symbol, this, S::class)
    }
    return symbol
}

/**
 * Creates [FirBasedSymbol] by [KtDeclaration] .
 * returned [FirDeclaration] will be resolved at least to [phase]
 *
 * If resulted [FirBasedSymbol] is not subtype of [S], returns `null`
 */
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
inline fun <reified S : FirBasedSymbol<*>> KtDeclaration.resolveToFirSymbolOfTypeSafe(
    llResolutionFacade: LLResolutionFacade,
    phase: FirResolvePhase = FirResolvePhase.RAW_FIR,
): @kotlin.internal.NoInfer S? {
    return resolveToFirSymbol(llResolutionFacade, phase) as? S
}


/**
 * Returns a list of Diagnostics compiler finds for given [KtElement]
 * This operation could be performance affective because it create FIleStructureElement and resolve non-local declaration into BODY phase
 */
fun KtElement.getDiagnostics(llResolutionFacade: LLResolutionFacade, filter: DiagnosticCheckerFilter): Collection<KtPsiDiagnostic> =
    llResolutionFacade.getDiagnostics(this, filter)

/**
 * Returns a list of Diagnostics compiler finds for given [KtFile]
 * This operation could be performance affective because it create FIleStructureElement and resolve non-local declaration into BODY phase
 */
fun KtFile.collectDiagnosticsForFile(
    llResolutionFacade: LLResolutionFacade,
    filter: DiagnosticCheckerFilter
): Collection<KtPsiDiagnostic> =
    llResolutionFacade.collectDiagnosticsForFile(this, filter)

/**
 * Build [FirElement] node in its final resolved state for a requested element.
 *
 * Note: that it isn't always [BODY_RESOLVE][FirResolvePhase.BODY_RESOLVE]
 * as not all declarations have types/bodies/etc. to resolve.
 *
 * This operation could be time-consuming because it creates
 * [FileStructureElement][org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.FileStructureElement]
 * and may resolve non-local declarations into [BODY_RESOLVE][FirResolvePhase.BODY_RESOLVE] phase.
 *
 * Please use [getOrBuildFirFile] to get [FirFile] in undefined phase.
 *
 * @return associated [FirElement] in final resolved state if it exists.
 *
 * @see getOrBuildFirFile
 * @see LLResolutionFacade.getOrBuildFirFor
 */
fun KtElement.getOrBuildFir(
    llResolutionFacade: LLResolutionFacade,
): FirElement? = llResolutionFacade.getOrBuildFirFor(this)

/**
 * Get a [FirElement] which was created by [KtElement], but only if it is subtype of [E], `null` otherwise
 * Returned [FirElement] is guaranteed to be resolved to [FirResolvePhase.BODY_RESOLVE] phase
 * This operation could be performance affective because it create FIleStructureElement and resolve non-local declaration into BODY phase
 */
inline fun <reified E : FirElement> KtElement.getOrBuildFirSafe(
    llResolutionFacade: LLResolutionFacade,
) = getOrBuildFir(llResolutionFacade) as? E

/**
 * Get a [FirElement] which was created by [KtElement], but only if it is subtype of [E], throws [InvalidFirElementTypeException] otherwise
 * Returned [FirElement] is guaranteed to be resolved to [FirResolvePhase.BODY_RESOLVE] phase
 * This operation could be performance affective because it create FIleStructureElement and resolve non-local declaration into BODY phase
 */
inline fun <reified E : FirElement> KtElement.getOrBuildFirOfType(
    llResolutionFacade: LLResolutionFacade,
): E {
    val fir = getOrBuildFir(llResolutionFacade)
    if (fir is E) return fir
    throwUnexpectedFirElementError(fir, this, E::class)
}

/**
 * Get a [FirFile] which was created by [KtElement]
 * Returned [FirFile] can be resolved to any phase from [FirResolvePhase.RAW_FIR] to [FirResolvePhase.BODY_RESOLVE]
 */
fun KtFile.getOrBuildFirFile(llResolutionFacade: LLResolutionFacade): FirFile =
    llResolutionFacade.getOrBuildFirFile(this)

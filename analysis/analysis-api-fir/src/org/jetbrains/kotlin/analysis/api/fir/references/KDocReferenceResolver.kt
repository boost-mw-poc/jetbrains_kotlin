/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.fir.references

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.fir.references.KDocReferenceResolver.canBeReferencedAsExtensionOn
import org.jetbrains.kotlin.analysis.api.fir.references.KDocReferenceResolver.getTypeQualifiedExtensions
import org.jetbrains.kotlin.analysis.api.scopes.KaScope
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaDeclarationContainerSymbol
import org.jetbrains.kotlin.analysis.api.types.*
import org.jetbrains.kotlin.analysis.utils.printer.parentOfType
import org.jetbrains.kotlin.kdoc.parser.KDocKnownTag
import org.jetbrains.kotlin.load.java.possibleGetMethodNames
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.isOneSegmentFQN
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClass
import org.jetbrains.kotlin.psi.psiUtil.isPropertyParameter
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.utils.addIfNotNull
import org.jetbrains.kotlin.utils.yieldIfNotNull

internal object KDocReferenceResolver {
    /**
     * [symbol] is the symbol referenced by this resolve result.
     *
     * [receiverClassReference] is an optional receiver type in
     * the case of extension function references (see [getTypeQualifiedExtensions]).
     */
    private data class ResolveResult(val symbol: KaSymbol, val receiverClassReference: KaClassLikeSymbol?)

    private fun KaSymbol.toResolveResult(receiverClassReference: KaClassLikeSymbol? = null): ResolveResult =
        ResolveResult(symbol = this, receiverClassReference)

    private fun Sequence<KaSymbol>.toResolveResults(): Sequence<ResolveResult> = this.map { it.toResolveResult() }
    private fun Iterable<KaSymbol>.toResolveResults(): Iterable<ResolveResult> = this.map { it.toResolveResult() }

    /**
     * Resolves the [selectedFqName] of KDoc
     *
     * To properly resolve qualifier parts in the middle,
     * we need to resolve the whole qualifier to understand which parts of the qualifier are package or class qualifiers.
     * And then we will be able to resolve the qualifier selected by the user to the proper class, package or callable.
     *
     * It's possible that the whole qualifier is invalid, in this case we still want to resolve our [selectedFqName].
     * To do this, we are trying to resolve the whole qualifier until we succeed.
     *
     * @param selectedFqName the selected fully qualified name of the KDoc
     * @param fullFqName the whole fully qualified name of the KDoc
     * @param contextElement the context element in which the KDoc is defined
     * @param containedTagSection the containing KDoc tag section (@constructor, @param, @property, etc.)
     *
     * @return the sequence of [KaSymbol](s) resolved from the fully qualified name
     *         based on the selected FqName and context element
     */
    internal fun resolveKdocFqName(
        analysisSession: KaSession,
        selectedFqName: FqName,
        fullFqName: FqName,
        contextElement: KtElement,
        containedTagSection: KDocKnownTag? = null
    ): Sequence<KaSymbol> {
        with(analysisSession) {
            //ensure file context is provided for "non-physical" code as well
            val contextDeclarationOrSelf = PsiTreeUtil.getContextOfType(contextElement, KtDeclaration::class.java, false)
                ?: contextElement
            val fullSymbolsResolved =
                resolveKdocFqName(fullFqName, contextDeclarationOrSelf, containedTagSection)
            if (selectedFqName == fullFqName) return fullSymbolsResolved.map { it.symbol }
            if (fullSymbolsResolved.none()) {
                val parent = fullFqName.parent()
                return resolveKdocFqName(analysisSession, selectedFqName, parent, contextDeclarationOrSelf)
            }
            val goBackSteps = fullFqName.pathSegments().size - selectedFqName.pathSegments().size
            check(goBackSteps > 0) {
                "Selected FqName ($selectedFqName) should be smaller than the whole FqName ($fullFqName)"
            }
            return fullSymbolsResolved.mapNotNull { findParentSymbol(it, goBackSteps, selectedFqName) }
        }
    }

    /**
     * Finds the parent symbol of the given [ResolveResult] by traversing back up the symbol hierarchy a [goBackSteps] steps,
     * or until the containing class or object symbol is found.
     *
     * Knows about the [ResolveResult.receiverClassReference] field and uses it in case it's not empty.
     */
    private fun KaSession.findParentSymbol(resolveResult: ResolveResult, goBackSteps: Int, selectedFqName: FqName): KaSymbol? {
        return if (resolveResult.receiverClassReference != null) {
            findParentSymbol(resolveResult.receiverClassReference, goBackSteps - 1, selectedFqName)
        } else {
            findParentSymbol(resolveResult.symbol, goBackSteps, selectedFqName)
        }
    }

    /**
     * Finds the parent symbol of the given [KaSymbol] by traversing back up the symbol hierarchy a certain number of steps,
     * or until the containing class or object symbol is found.
     *
     * @param symbol The [KaSymbol] whose parent symbol needs to be found.
     * @param goBackSteps The number of steps to go back up the symbol hierarchy.
     * @param selectedFqName The fully qualified name of the selected package.
     * @return The [goBackSteps]-th parent [KaSymbol]
     */
    private fun KaSession.findParentSymbol(symbol: KaSymbol, goBackSteps: Int, selectedFqName: FqName): KaSymbol? {
        if (symbol !is KaDeclarationSymbol && symbol !is KaPackageSymbol) return null

        if (symbol is KaDeclarationSymbol) {
            goToNthParent(symbol, goBackSteps)?.let { return it }
        }

        return findPackage(selectedFqName)
    }

    /**
     * N.B. Works only for [KaClassSymbol] parents chain.
     */
    private fun KaSession.goToNthParent(symbol: KaDeclarationSymbol, steps: Int): KaDeclarationSymbol? {
        var currentSymbol = symbol

        repeat(steps) {
            currentSymbol = currentSymbol.containingDeclaration as? KaClassSymbol ?: return null
        }

        return currentSymbol
    }

    private fun KaSession.resolveKdocFqName(
        fqName: FqName,
        contextElement: KtElement,
        containedTagSection: KDocKnownTag?
    ): Sequence<ResolveResult> {
        val containingFile = contextElement.containingKtFile
        val scopeContext = containingFile.scopeContext(contextElement)
        val scopeContextScopes = scopeContext.scopes.map { it.scope }
        val shortName = fqName.shortName()

        val allScopesContainingName = sequence<KaScope> {
            yieldAll(scopeContextScopes.map { getSymbolsFromMemberScope(fqName, it) })
            yieldAll(getPotentialPackageScopes(fqName))
        }

        val allMatchingSymbols = sequence {
            yieldAll(getExtensionReceiverSymbolByThisQualifier(fqName, contextElement).toResolveResults())
            if (fqName.isOneSegmentFQN()) yieldAll(
                getSymbolsFromDeclaration(
                    shortName,
                    contextElement,
                    containedTagSection
                ).toResolveResults()
            )
            yieldAll(allScopesContainingName.flatMap { scope -> scope.classifiers(shortName) }.toResolveResults())

            yieldIfNotNull(findPackage(fqName)?.toResolveResult())

            val callables = allScopesContainingName.map { scope -> scope.callables(shortName) }
            yieldAll(callables.flatMap { callableSymbols -> callableSymbols.filterIsInstance<KaFunctionSymbol>() }.toResolveResults())
            yieldAll(getSymbolsFromSyntheticProperty(fqName, allScopesContainingName).toResolveResults())
            yieldAll(callables.flatMap { scope -> scope.filterIsInstance<KaVariableSymbol>() }.toResolveResults())

            yieldAll(getTypeQualifiedExtensions(fqName, scopeContextScopes))
            yieldAll(AdditionalKDocResolutionProvider.resolveKdocFqName(useSiteSession, fqName, contextElement).toResolveResults())
        }.distinct()

        return allMatchingSymbols
    }

    private fun KaSession.getPotentialPackageScopes(fqName: FqName): Sequence<KaScope> =
        sequence {
            val fqNameSegments = fqName.pathSegments()
            for (numberOfSegments in fqNameSegments.size - 1 downTo 1) {
                val packageName = FqName.fromSegments(fqNameSegments.take(numberOfSegments).map { it.toString() })
                val declarationNameToFind = FqName.fromSegments(fqNameSegments.drop(numberOfSegments).map { it.toString() })
                yieldIfNotNull(
                    findPackage(packageName)?.packageScope?.let { packageScope ->
                        getSymbolsFromMemberScope(declarationNameToFind, packageScope)
                    }
                )
            }
        }


    private fun KaSession.getSymbolsFromSyntheticProperty(fqName: FqName, scopes: Sequence<KaScope>): Sequence<KaSymbol> {
        val getterNames = possibleGetMethodNames(fqName.shortNameOrSpecial())
        return scopes.map { scope -> scope.callables { it in getterNames } }.flatMap { callables ->
            callables.filter { symbol ->
                val symbolLocation = symbol.location
                val symbolOrigin = symbol.origin
                symbolLocation == KaSymbolLocation.CLASS && (symbolOrigin == KaSymbolOrigin.JAVA_LIBRARY || symbolOrigin == KaSymbolOrigin.JAVA_SOURCE)
            }
        }
    }

    private fun KaSession.getExtensionReceiverSymbolByThisQualifier(
        fqName: FqName,
        contextElement: KtElement,
    ): Collection<KaSymbol> {
        val owner = contextElement.parentOfType<KtDeclaration>(withSelf = true) ?: return emptyList()
        if (fqName.pathSegments().singleOrNull()?.asString() == "this") {
            if (owner is KtCallableDeclaration && owner.receiverTypeReference != null) {
                val symbol = owner.symbol as? KaCallableSymbol ?: return emptyList()
                return listOfNotNull(symbol.receiverParameter)
            }
        }
        return emptyList()
    }

    /**
     * Retrieves suitable symbols from [contextDeclaration].
     *
     * Note that [containedTagSection] directly affects the search result.
     *
     * - `@constructor` makes the constructor symbol and its parameter symbols visible, then prioritizes them in the resulting collection.
     * - `@param` prioritizes parameters.
     *      When attached to a primary constructor, makes the constructor symbol and its parameter symbols visible, then prioritizes them.
     * - `@property` prioritizes class properties.
     */
    private fun KaSession.getSymbolsFromDeclaration(
        name: Name,
        contextDeclaration: KtElement,
        containedTagSection: KDocKnownTag?
    ): List<KaSymbol> = buildList {
        if (contextDeclaration is KtNamedDeclaration && contextDeclaration.nameAsName == name) {
            if (contextDeclaration !is KtPrimaryConstructor || containedTagSection == KDocKnownTag.CONSTRUCTOR || containedTagSection == KDocKnownTag.PARAM) {
                add(contextDeclaration.symbol)
            }
        }

        if (contextDeclaration is KtTypeParameterListOwner) {
            for (typeParameter in contextDeclaration.typeParameters) {
                if (typeParameter.nameAsName == name) {
                    add(typeParameter.symbol)
                }
            }
        }

        if (contextDeclaration is KtCallableDeclaration) {
            for (valueParameter in contextDeclaration.valueParameters) {
                val valueParameterName = valueParameter.nameAsName
                if (valueParameterName == name) {
                    if (valueParameter.isPropertyParameter()) {
                        val propertyByConstructorParameter =
                            contextDeclaration.containingClass()?.classSymbol?.declaredMemberScope?.callables?.firstOrNull { callable ->
                                callable is KaPropertySymbol && callable.isFromPrimaryConstructor && callable.name == valueParameterName
                            }
                        addIfNotNull(propertyByConstructorParameter)
                    }

                    if (contextDeclaration !is KtPrimaryConstructor || containedTagSection == KDocKnownTag.CONSTRUCTOR || containedTagSection == KDocKnownTag.PARAM) {
                        add(valueParameter.symbol)
                    }
                }
            }
        }

        if (contextDeclaration is KtClassOrObject) {
            contextDeclaration.primaryConstructor?.let { addAll(getSymbolsFromDeclaration(name, it, containedTagSection)) }
        }
    }.let { symbols ->
        selfDeclarationsComparator(containedTagSection)?.let { comparator ->
            symbols.sortedWith(comparator)
        } ?: symbols
    }

    private fun selfDeclarationsComparator(containedTagSection: KDocKnownTag?) = when (containedTagSection) {
        KDocKnownTag.CONSTRUCTOR -> compareByDescending<KaSymbol> { it is KaConstructorSymbol }.thenByDescending { it is KaParameterSymbol }
        KDocKnownTag.PARAM -> compareByDescending<KaSymbol> { it is KaParameterSymbol }.thenByDescending { it is KaConstructorSymbol }
        KDocKnownTag.PROPERTY -> compareByDescending { it is KaPropertySymbol }
        else -> null
    }


    private fun KaSession.getCompositeCombinedMemberAndCompanionObjectScope(symbol: KaDeclarationContainerSymbol): KaScope =
        listOfNotNull(
            symbol.combinedMemberScope,
            getCompanionObjectMemberScope(symbol),
        ).asCompositeScope()

    private fun KaSession.getCompanionObjectMemberScope(symbol: KaDeclarationContainerSymbol): KaScope? {
        val namedClassSymbol = symbol as? KaNamedClassSymbol ?: return null
        val companionSymbol = namedClassSymbol.companionObject ?: return null
        return companionSymbol.memberScope
    }

    private fun KaSession.getSymbolsFromMemberScope(fqName: FqName, scope: KaScope): KaScope {
        val finalScope = fqName.pathSegments()
            .dropLast(1)
            .fold(scope) { currentScope, fqNamePart ->
                currentScope
                    .classifiers(fqNamePart)
                    .filterIsInstance<KaDeclarationContainerSymbol>()
                    .map { getCompositeCombinedMemberAndCompanionObjectScope(it) }
                    .toList()
                    .asCompositeScope()
            }

        return finalScope
    }

    /**
     * Tries to resolve [fqName] into available extension callables (functions or properties)
     * prefixed with a suitable extension receiver type (like in `Foo.bar`, or `foo.Foo.bar`).
     *
     * Relies on the fact that in such references only the last qualifier refers to the
     * actual extension callable, and the part before that refers to the receiver type (either fully
     * or partially qualified).
     *
     * For example, `foo.Foo.bar` may only refer to the extension callable `bar` with
     * a `foo.Foo` receiver type, and this function will only look for such combinations.
     *
     * N.B. This function only searches for extension callables qualified by receiver types!
     * It does not try to resolve fully qualified or member functions, because they are dealt
     * with by the other parts of [KDocReferenceResolver].
     */
    private fun KaSession.getTypeQualifiedExtensions(fqName: FqName, scopes: Collection<KaScope>): Sequence<ResolveResult> {
        if (fqName.isRoot) return emptySequence()
        val extensionName = fqName.shortName()

        val receiverTypeName = fqName.parent()
        if (receiverTypeName.isRoot) return emptySequence()

        val scopesContainingPossibleReceivers = sequence<KaScope> {
            yieldAll(scopes.map { getSymbolsFromMemberScope(receiverTypeName, it) })
            yieldAll(getPotentialPackageScopes(receiverTypeName))
        }

        val possibleReceivers =
            scopesContainingPossibleReceivers.flatMap { it.classifiers(receiverTypeName.shortName()) }.filterIsInstance<KaClassLikeSymbol>()
        val possibleExtensions = scopes.asSequence().flatMap { it.callables(extensionName) }.filter { it.isExtension }

        if (possibleExtensions.none() || possibleReceivers.none()) return emptySequence()

        return possibleReceivers.flatMap { receiverClassSymbol ->
            val receiverType = receiverClassSymbol.defaultType
            possibleExtensions.filter { canBeReferencedAsExtensionOn(it, receiverType) }
                .map { it.toResolveResult(receiverClassReference = receiverClassSymbol) }
        }
    }

    /**
     * Returns true if we consider that [this] extension function prefixed with [actualReceiverType] in
     * a KDoc reference should be considered as legal and resolved, and false otherwise.
     *
     * This is **not** an actual type check, it is just an opinionated approximation.
     * The main guideline was K1 KDoc resolve.
     *
     * This check might change in the future, as the Dokka team advances with KDoc rules.
     */
    private fun KaSession.canBeReferencedAsExtensionOn(symbol: KaCallableSymbol, actualReceiverType: KaType): Boolean {
        val extensionReceiverType = symbol.receiverParameter?.returnType ?: return false
        return isPossiblySuperTypeOf(extensionReceiverType, actualReceiverType)
    }

    /**
     * Same constraints as in [canBeReferencedAsExtensionOn].
     *
     * For a similar function in the `intellij` repository, see `isPossiblySubTypeOf`.
     */
    private fun KaSession.isPossiblySuperTypeOf(type: KaType, actualReceiverType: KaType): Boolean {
        // Type parameters cannot act as receiver types in KDoc
        if (actualReceiverType is KaTypeParameterType) return false

        if (type is KaTypeParameterType) {
            return type.symbol.upperBounds.all { isPossiblySuperTypeOf(it, actualReceiverType) }
        }

        val receiverExpanded = actualReceiverType.expandedSymbol
        val expectedExpanded = type.expandedSymbol

        // if the underlying classes are equal, we consider the check successful
        // despite the possibility of different type bounds
        if (
            receiverExpanded != null &&
            receiverExpanded == expectedExpanded
        ) {
            return true
        }

        return actualReceiverType.isSubtypeOf(type) || isSubtypeOfWithTypeParams(type, actualReceiverType)
    }


    /**
     * Performs a subtyping check of two types with respect to their type parameters.
     *
     * ```kotlin
     * interface T<A_0, A_1, ..., A_TN>
     *
     * class S<B_0, B_1, ..., B_SN> : T<...>
     *
     * fun T<E_0, E_1, ..., E_I>.extension() { }
     *
     * /** [S.extension] */
     * fun documented() { }
     * ```
     *
     * We check that `S.extension` is a valid reference with the following algorithm:
     *
     * - From `S<B_0, B_1, ..., B_M>`, derive the supertype instantiation `T<Z_0, Z_1, ..., Z_K>` according to the inheritance relationship.
     *      * Any `Z_x` will either be a type parameter type from `S<B_0, B_1, ..., B_M>`,
     *      or a concrete type from a regular type argument along the way to the supertype.
     *
     * - Compare `T<Z_0, Z_1, ..., Z_K>` against `T<E_0, E_1, ..., E_I>`,
     * finding whether each type argument `E_x` matches with the type `Z_x`:
     *      * If both `Z_x` and `E_x` are concrete types,
     *      check that `Z_x` is a subtype of `E_x` according to the variance of the type parameter at the position.
     *
     *      * If only `E_x` is a concrete type (and vice versa), check that `E_x` fits into the bounds of `Z_x`.
     *      If `E_x` does not fit the bounds of `Z_x`, we cannot find an instantiation of `Z_x` which satisfies `E_x`.
     *
     *      * If both types are type parameter types, check that the bounds of `Z_x` and `E_x` overlap.
     *      That is, there must be at least one type which can be an instantiation of both `Z_x` and `E_x`.
     *
     *      * Unless we are dealing with concrete types on both sides, covariance and contravariance do not need to be taken into account.
     *      Variance is concerned with subtyping of two types when their type arguments have concrete instantiations
     *      (e.g. `Type<Cat>` <: `Type<Animal>`),
     *      but here we are concerned with finding a common instantiation of two type parameters (e.g. `Type<X>` and `Type<Y>`).
     *      As long as the bounds overlap, we can instantiate both type parameters to the same type argument,
     *      making the variance unimportant (all type parameters could be invariant).
     */
    private fun KaSession.isSubtypeOfWithTypeParams(type: KaType, actualReceiverType: KaType): Boolean {
        if (type !is KaClassType || type.typeArguments.none()) {
            return false
        }

        val compatibleSupertypesOfActualReceiverType =
            actualReceiverType
                .allSupertypes(shouldApproximate = true)
                .filter { it.symbol == type.symbol }
                .filterIsInstance<KaClassType>()
                .toList().ifEmpty { return false }

        return compatibleSupertypesOfActualReceiverType.all { compatibleType ->
            compatibleType.typeArguments.zip(type.typeArguments).all { (actualTypeArgument, extensionTypeArgument) ->
                // It implies that the current extension type argument is `KaStarTypeProjection`,
                // so it can be substituted with any actual type argument
                if (extensionTypeArgument is KaStarTypeProjection) {
                    return true
                }

                // Star projections can't be used in the argument lists of receiver types
                if (actualTypeArgument is KaStarTypeProjection) {
                    return false
                }

                if (extensionTypeArgument !is KaTypeArgumentWithVariance || actualTypeArgument !is KaTypeArgumentWithVariance) {
                    return false
                }

                val actualTypeArgumentType = actualTypeArgument.type
                val extensionTypeArgumentType = extensionTypeArgument.type

                when (actualTypeArgumentType) {
                    !is KaTypeParameterType if extensionTypeArgumentType !is KaTypeParameterType -> when (extensionTypeArgument.variance) {
                        Variance.INVARIANT -> actualTypeArgumentType == extensionTypeArgumentType
                        Variance.OUT_VARIANCE -> isPossiblySuperTypeOf(extensionTypeArgumentType, actualTypeArgumentType)
                        Variance.IN_VARIANCE -> isPossiblySuperTypeOf(actualTypeArgumentType, extensionTypeArgumentType)
                    }
                    is KaTypeParameterType if extensionTypeArgumentType is KaTypeParameterType -> actualTypeArgumentType.hasCommonSubtypeWith(
                        extensionTypeArgumentType
                    )
                    is KaTypeParameterType -> isPossiblySuperTypeOf(
                        actualTypeArgumentType,
                        extensionTypeArgumentType
                    )
                    else -> isPossiblySuperTypeOf(
                        extensionTypeArgumentType,
                        actualTypeArgumentType
                    )
                }
            }
        }
    }
}
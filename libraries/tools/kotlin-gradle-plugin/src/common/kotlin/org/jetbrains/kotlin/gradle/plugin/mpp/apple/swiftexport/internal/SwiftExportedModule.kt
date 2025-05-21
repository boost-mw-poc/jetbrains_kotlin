/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftexport.internal

import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.diagnostics.KotlinToolingDiagnostics
import org.jetbrains.kotlin.gradle.plugin.diagnostics.reportDiagnostic
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftexport.SwiftExportedModuleVersionMetadata
import org.jetbrains.kotlin.gradle.utils.LazyResolvedConfiguration
import java.io.File
import java.io.Serializable

/**
 * Represents a module that will be exported to Swift.
 *
 * @property moduleName The name of the module in Swift
 * @property flattenPackage Optional package flattening configuration
 * @property artifact The artifact file containing the module
 * @property shouldBeFullyExported Whether this module was explicitly requested for export through the swiftExport { export("foo:bar") } DSL
 */
internal interface SwiftExportedModule : Serializable {
    val moduleName: String
    val flattenPackage: String?
    val artifact: File
    val shouldBeFullyExported: Boolean
}

internal fun createFullyExportedSwiftExportedModule(
    moduleName: String,
    flattenPackage: String?,
    artifact: File,
): SwiftExportedModule {
    return SwiftExportedModuleImp(
        moduleName,
        flattenPackage,
        artifact,
        true
    )
}

internal fun createTransitiveSwiftExportedModule(
    moduleName: String,
    artifact: File,
): SwiftExportedModule {
    return SwiftExportedModuleImp(
        moduleName,
        null,
        artifact,
        false
    )
}

internal fun Project.collectModules(
    swiftExportConfigurationProvider: Provider<LazyResolvedConfiguration>,
    exportedModulesProvider: Provider<Set<SwiftExportedModuleVersionMetadata>>,
): Provider<List<SwiftExportedModule>> = swiftExportConfigurationProvider.zip(exportedModulesProvider) { configuration, modules ->
    configuration.swiftExportedModules(modules, project)
}

private fun LazyResolvedConfiguration.swiftExportedModules(
    exportedModules: Set<SwiftExportedModuleVersionMetadata>,
    project: Project,
) = project.findAndCreateSwiftExportedModules(exportedModules, artifactComponents(project))

private fun LazyResolvedConfiguration.artifactComponents(project: Project): List<Pair<ResolvedComponentResult, File>> {
    // Use sequence for lazy evaluation and single pass
    return filteredDependencies()
        .mapNotNull { component ->
            val dependencyArtifacts = getArtifacts(component)
                .asSequence()
                .map { it.file }
                .filterNot { it.isCinteropKlib }
                .toList()

            when (dependencyArtifacts.size) {
                1 -> Pair(component, dependencyArtifacts.single())
                else -> {
                    project.reportDiagnostic(
                        KotlinToolingDiagnostics.SwiftExportArtifactResolution(
                            component.moduleVersion?.toString() ?: component.toString(),
                            dependencyArtifacts.map { it.absolutePath }
                        )
                    )
                    null
                }
            }
        }
        .distinctBy { (_, artifact) -> artifact.absolutePath }
        .toList()
}

private fun LazyResolvedConfiguration.filteredDependencies(): Sequence<ResolvedComponentResult> {
    return allResolvedDependencies.asSequence()
        .mapNotNull { dependencyResult ->
            val owner = dependencyResult.resolvedVariant.owner
            when {
                owner is ModuleComponentIdentifier && owner.module == "kotlin-stdlib" -> null
                else -> dependencyResult.selected
            }
        }
}

private val File.isCinteropKlib get() = name.contains("-cinterop-") || name.contains("Cinterop-")

private fun Project.findAndCreateSwiftExportedModules(
    exportedModules: Set<SwiftExportedModuleVersionMetadata>,
    resolvedComponents: List<Pair<ResolvedComponentResult, File>>,
): List<SwiftExportedModule> {
    val result = mutableListOf<SwiftExportedModule>()
    val processedComponentIds = mutableSetOf<String>()
    val missingModules = mutableListOf<SwiftExportedModuleVersionMetadata>()

    // First, process all explicitly exported modules
    for (explicitModule in exportedModules) {
        val matchingComponent = resolvedComponents.find { (component, _) ->
            component.moduleVersion?.let { resolvedModule ->
                resolvedModule.name == explicitModule.moduleVersion.name && resolvedModule.group == explicitModule.moduleVersion.group
            } ?: false
        }

        if (matchingComponent != null) {
            val (_, artifact) = matchingComponent
            result.add(
                createFullyExportedSwiftExportedModule(
                    explicitModule.moduleName.orElse(
                        validatedModuleName(explicitModule.moduleVersion.name)
                    ).get(),
                    explicitModule.flattenPackage.orNull,
                    artifact
                )
            )

            // Track which components we've processed
            processedComponentIds.add("${matchingComponent.first.id}")
        } else {
            missingModules.add(explicitModule)
        }
    }

    if (missingModules.isNotEmpty()) {
        reportDiagnostic(
            KotlinToolingDiagnostics.SwiftExportModuleResolutionError(
                missingModules.map { it.moduleVersion.toString() })
        )
    }

    // Then process remaining components as transitive
    resolvedComponents.filter { !processedComponentIds.contains("${it.first.id}") }.forEach { (component, artifact) ->
        val resolvedModule = requireNotNull(component.moduleVersion)
        result.add(
            createTransitiveSwiftExportedModule(
                validatedModuleName(resolvedModule.name),
                artifact
            )
        )
    }

    return result
}

private data class SwiftExportedModuleImp(
    override val moduleName: String,
    override val flattenPackage: String?,
    override val artifact: File,
    override val shouldBeFullyExported: Boolean,
) : SwiftExportedModule

private fun Project.validatedModuleName(moduleName: String) =
    moduleName.normalizedSwiftExportModuleName.also { validateSwiftExportModuleName(it) }
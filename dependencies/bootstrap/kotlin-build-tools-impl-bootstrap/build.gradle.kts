val resolvedBootstrap = configurations.resolvable("kotlinBuildToolsApiImplBootstrapClasspath") {
    dependencies.addLater(providers.provider {
        project.dependencies.create("org.jetbrains.kotlin:kotlin-build-tools-impl:${bootstrapKotlinVersion}")
    })
}

val outgoingBootstrap = configurations.consumable("buildToolsApiImplElements") {
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
}

resolvedBootstrap.get().incoming.afterResolve {
    files.forEach {
        project.artifacts.add(
            outgoingBootstrap.name,
            it
        )
    }
}

afterEvaluate {
    resolvedBootstrap.get().resolve()
}

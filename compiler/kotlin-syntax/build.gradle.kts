import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import java.net.URI

plugins {
    kotlin("multiplatform")
}

repositories {
    maven {
        name = "IntellijDependencies"
        url = uri("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    }
}

kotlin {
    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(kotlinStdlib())
                implementation("org.jetbrains:syntax-api:0.3.332")
            }
            kotlin {
                srcDir("common/src")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(project(":compiler:psi"))
                implementation(commonDependency("org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm"))
                implementation(intellijCore())
                runtimeOnly(libs.intellij.fastutil)
                implementation(project(":compiler:test-infrastructure-utils"))
                implementation(libs.junit.jupiter.api)
                runtimeOnly(libs.junit.jupiter.engine)
                api(kotlinTest("junit"))
            }
            kotlin {
                srcDir("jvm/test")
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

val flexGeneratorClasspath: Configuration by configurations.creating

dependencies {
    flexGeneratorClasspath(commonDependency("org.jetbrains.intellij.deps.jflex", "jflex")) {
        // Flex brings many unrelated dependencies, so we are dropping them because only a flex `.jar` file is needed.
        // It can be probably removed when https://github.com/JetBrains/intellij-deps-jflex/issues/10 is fixed.
        isTransitive = false
    }
}

tasks.register<JavaExec>("generateKotlinLexer") {
    mainClass = "jflex.Main"
    classpath = files(flexGeneratorClasspath)

    val lexerDir = projectDir.resolve("common/src/org/jetbrains/kotlin/kmp/lexer")

    // TODO: get rid of the skeleton or implement its caching
    // It's blocked by https://github.com/JetBrains/intellij-deps-jflex/issues/9
    // Currently it's forced to use the latest skeleton version from master with the assumption that the latest flex version is used.
    val skeletonFile = layout.buildDirectory.file("idea-flex-kotlin.skeleton").get().asFile

    doFirst {
        val skeletonUrl = "https://raw.githubusercontent.com/JetBrains/intellij-community/master/tools/lexer/idea-flex-kotlin.skeleton"
        println("Downloading skeleton file $skeletonUrl")
        URI.create(skeletonUrl).toURL().openStream().use { input ->
            skeletonFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            lexerDir.resolve("Kotlin.flex").absolutePath,
            "-skel",
            skeletonFile.absolutePath,
            "-d",
            lexerDir.absolutePath,
            "--output-mode",
            "kotlin",
            "--nobak", // Prevent generating backup `.kt~` files
        )
    })
}

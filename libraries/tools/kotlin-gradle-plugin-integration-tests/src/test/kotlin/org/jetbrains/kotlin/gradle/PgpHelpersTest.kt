/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.*
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.tasks.CheckSigningTask
import org.jetbrains.kotlin.gradle.testbase.*
import org.jetbrains.kotlin.gradle.util.awaitInitialization
import org.junit.jupiter.api.DisplayName
import java.nio.file.Path
import java.security.Security
import kotlin.io.path.absolutePathString
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OtherGradlePluginTests
@DisplayName("PGP signing helper tasks")
class PgpHelpersTest : KGPBaseTest() {

    @GradleTest
    @DisplayName("Should complain about missing name parameter")
    internal fun shouldComplainAboutMissingName(gradleVersion: GradleVersion) {
        projectWithJvmPlugin(gradleVersion) {
            buildAndFail("generatePgpKeys", "--password=abc") {
                assertOutputContains("You must provide a value for the '--name' command line option, e.g. --name \"Jane Doe <janedoe@example.com>\"")
            }
        }
    }

    @GradleTest
    @DisplayName("Should complain about missing password parameter")
    internal fun shouldComplainAboutMissingPassword(gradleVersion: GradleVersion) {
        projectWithJvmPlugin(gradleVersion) {
            buildAndFail("generatePgpKeys", "--name='Jane Doe <janedoe@example.com>'") {
                assertOutputContains("You must provide a value for either the '--password' command line option or the 'signing.password' Gradle property")
            }
        }
    }

    @GradleTest
    @DisplayName("Should read password supplied through CLI option")
    internal fun passwordSuppliedThroughOption(gradleVersion: GradleVersion) {
        projectWithJvmPlugin(gradleVersion) {
            build("generatePgpKeys", "--name='Jane Doe <janedoe@example.com>'", "--password=abc") {
                assertPgpKeysWereGenerated()
            }
        }
    }

    @GradleTest
    @DisplayName("Should read password supplied through project property")
    internal fun passwordSuppliedThroughGradleProperty(gradleVersion: GradleVersion) {
        projectWithJvmPlugin(gradleVersion) {
            build("generatePgpKeys", "--name='Jane Doe <janedoe@example.com>'", "-Psigning.password=abc") {
                assertPgpKeysWereGenerated()
            }
        }
    }

    @GradleTest
    @DisplayName("Should not leak BouncyCastle dependency")
    internal fun shouldNotLeakBouncyCastleDependency(gradleVersion: GradleVersion) {
        projectWithJvmPlugin(gradleVersion) {
            val classesLeaked = buildScriptReturn {
                try {
                    this::class.java.classLoader.loadClass("org.bouncycastle.openpgp.operator.PGPContentSignerBuilder")
                    return@buildScriptReturn true
                } catch (_: ClassNotFoundException) {
                    // ignore
                }
                if (Security.getProvider("BC") != null) {
                    return@buildScriptReturn true
                }
                false
            }.buildAndReturn("generatePgpKeys", "--name='Jane Doe <janedoe@example.com>'", "-Psigning.password=abc")

            assertFalse(classesLeaked)
        }
    }

    @GradleTest
    @DisplayName("Should upload public key to server")
    internal fun uploadPublicKeyToServer(gradleVersion: GradleVersion) {
        projectWithJvmPlugin(gradleVersion) {
            build(
                "generatePgpKeys",
                "--name='Jane Doe <janedoe@example.com>'",
                "-Psigning.password=abc",
            )
            val parameters = mutableListOf<Parameters>()

            runWithKtorService(
                routingSetup = {
                    post("/pks/add") {
                        val formParameters: Parameters = call.receiveParameters()
                        parameters += formParameters
                        call.respond(HttpStatusCode.OK)
                    }
                }) { port ->
                build(
                    "uploadPublicPgpKey",
                    "--keyring",
                    findGeneratedKey().absolutePathString(),
                    "--keyserver",
                    "http://localhost:$port",
                )
            }
            assert(parameters.size == 1) { "Exactly one request must be sent to the server, but the number of requests was: ${parameters.size}" }
            val params = parameters.single()
            assertEquals("nm", params["options"])
            assertEquals(findGeneratedKey().readText(), params["keytext"])
        }
    }

    @GradleTest
    @DisplayName("Should fail task when upload public key to server fails")
    internal fun failedUploadPublicKeyToServer(gradleVersion: GradleVersion) {
        projectWithJvmPlugin(gradleVersion) {
            build(
                "generatePgpKeys",
                "--name='Jane Doe <janedoe@example.com>'",
                "-Psigning.password=abc",
            )
            runWithKtorService(
                routingSetup = {
                    post("/pks/add") {
                        call.respond(HttpStatusCode.BadRequest, "Some reason")
                    }
                }) { port ->
                buildAndFail(
                    "uploadPublicPgpKey",
                    "--keyring",
                    findGeneratedKey().absolutePathString(),
                    "--keyserver",
                    "http://localhost:$port",
                ) {
                    assertOutputContains("Failed to upload public key. Server returned:\nSome reason")
                }
            }
        }
    }

    @GradleTest
    @DisplayName("Use generated key from Gradle signing plugin")
    internal fun useGeneratedKeyInSigningPlugin(gradleVersion: GradleVersion) {
        project("empty", gradleVersion, buildOptions = BuildOptions().disableConfigurationCacheForGradle7(gradleVersion)) {
            plugins {
                kotlin("jvm")
                `maven-publish`
                signing
            }
            var keyId: String? = null
            var keyringPath: String? = null
            build("generatePgpKeys", "--name='Jane Doe <janedoe@example.com>'", "-Psigning.password=abc") {
                assertPgpKeysWereGenerated()
                // need to match key ID from this output: "The key ID of the generated key is 'XXXXXXXX'."
                keyId = output.substringAfter("The key ID of the generated key is '").substringBefore("'")
                keyringPath = "build/pgp/secret_$keyId.gpg"
            }
            assertNotNull(keyId)
            assertNotNull(keyringPath)

            buildScriptInjection {
                project.group = "someGroup"
                project.version = "1.0.0"

                publishing.repositories {
                    it.maven(project.layout.buildDirectory.dir("repo")) {
                        name = "repo"
                    }
                }
                publishing.publications {
                    it.create<MavenPublication>("mavenJava")
                }
                signing.sign(publishing.publications["mavenJava"])
            }
            build("publish", "-Psigning.keyId=$keyId", "-Psigning.password=abc", "-Psigning.secretKeyRingFile=$keyringPath") {
                assertTasksExecuted(":signMavenJavaPublication")
            }
        }
    }

    @GradleTest
    @DisplayName("Verify generated pom.xml")
    internal fun verifyGeneratedPom(gradleVersion: GradleVersion) {
        project("empty", gradleVersion) {
            plugins {
                kotlin("jvm")
                `maven-publish`
            }
            buildScriptInjection {
                project.group = "someGroup"
                project.version = "1.0.0"

                publishing.repositories {
                    it.maven(project.layout.buildDirectory.dir("repo")) {
                        name = "repo"
                    }
                }
                publishing.publications.create<MavenPublication>("mavenJava") {
                    pom {
                        it.name.set("My Library")
                        it.description.set("A concise description of my library")
                        it.url.set("http://www.example.com/library")
                        it.licenses { licenses ->
                            licenses.license { license ->
                                license.name.set("The Apache License, Version 2.0")
                                license.url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                            }
                        }
                        it.developers { developers ->
                            developers.developer { developer ->
                                developer.name.set("John Doe")
                                developer.email.set("john.doe@example.com")
                                developer.organization.set("Example")
                                developer.organizationUrl.set("http://example.com")
                            }
                        }
                        it.scm { scm ->
                            scm.connection.set("scm:git:git://example.com/my-library.git")
                            scm.developerConnection.set("scm:git:ssh://example.com/my-library.git")
                            scm.url.set("http://example.com/my-library/")
                        }
                    }
                }
            }
            build("checkPomFileForMavenJavaPublication") {
                assertTasksExecuted(":generatePomFileForMavenJavaPublication")
                println(output)
            }
        }
    }

    @GradleTest
    @DisplayName("Should fail verification of pom.xml with missing tags")
    internal fun shouldFailVerificationGeneratedPom(gradleVersion: GradleVersion) {
        project("empty", gradleVersion) {
            plugins {
                kotlin("jvm")
                `maven-publish`
            }
            buildScriptInjection {
                project.group = "someGroup"
                project.version = "1.0.0"

                publishing.repositories {
                    it.maven(project.layout.buildDirectory.dir("repo")) {
                        name = "repo"
                    }
                }
                publishing.publications.create<MavenPublication>("mavenJava") {
                    pom {
                        it.name.set("My Library")
                        it.description.set("A concise description of my library")
                        it.url.set("http://www.example.com/library")
                        it.licenses { licenses ->
                            licenses.license { license ->
                                license.name.set("The Apache License, Version 2.0")
                                license.url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                            }
                        }
                        it.developers { developers ->
                            developers.developer { developer ->
                                developer.name.set("John Doe")
                                developer.email.set("john.doe@example.com")
                            }
                        }
                        it.scm { scm ->
                            scm.connection.set("scm:git:git://example.com/my-library.git")
                            scm.developerConnection.set("scm:git:ssh://example.com/my-library.git")
                            scm.url.set("http://example.com/my-library/")
                        }
                    }
                }
            }
            buildAndFail("checkPomFileForMavenJavaPublication") {
                assertTasksFailed(":checkPomFileForMavenJavaPublication")
                assertOutputContains(
                    """
                    Missing tags:
                    <developers> <developer> <organization>
                    <developers> <developer> <organizationUrl>
                """.trimIndent()
                )
            }
        }
    }

    @GradleTest
    @DisplayName("Verify signing is configured")
    internal fun verifySigningConfigured(gradleVersion: GradleVersion) {
        project("empty", gradleVersion) {
            plugins {
                kotlin("jvm")
                `maven-publish`
                signing
            }

            build("generatePgpKeys", "--name='Jane Doe <janedoe@example.com>'", "--password=abc")
            val keyring = findGeneratedKey("secret_", ".gpg")
            val keyId = keyring.fileName.toString().substringAfter("secret_").substringBefore(".gpg")

            runWithKtorService(
                routingSetup = {
                    get("/pks/lookup") {
                        if (call.request.queryParameters["op"] == "get" && call.request.queryParameters["search"]?.startsWith("0x") == true) {
                            call.respond(HttpStatusCode.OK, "<ASCII KEY TEXT HERE>")
                        } else {
                            call.respond(HttpStatusCode.BadRequest, "Wrong parameters for getting key.")
                        }
                    }
                }) { port ->
                buildScriptInjection {
                    project.tasks.named<CheckSigningTask>("checkSigningConfiguration").configure {
                        it.keyservers.set(listOf("http://localhost:$port"))
                    }
                }
                build(
                    "checkSigningConfiguration",
                    "-Psigning.keyId=$keyId",
                    "-Psigning.secretKeyRingFile=$keyring",
                    "-Psigning.password=abc"
                ) {
                    assertTasksExecuted(":checkSigningConfiguration")
                }
            }
        }
    }

    private fun TestProject.assertPgpKeysWereGenerated() {
        val expectedFileNames =
            listOf("secret_" to ".gpg", "secret_" to ".asc", "public_" to ".gpg", "public_" to ".asc", "example_" to ".properties")
        val actualFileNames = projectPath.resolve("build/pgp").listDirectoryEntries().map { it.fileName.toString() }
        for (expected in expectedFileNames) {
            assertTrue("File ${expected.first}X${expected.second} not found (where X is key ID).") {
                actualFileNames.any { actual -> actual.startsWith(expected.first) && actual.endsWith(expected.second) }
            }
        }
    }

    private fun TestProject.findGeneratedKey(prefix: String = "public_", suffix: String = ".asc"): Path {
        return projectPath.resolve("build/pgp").listDirectoryEntries()
            .single { it.fileName.toString().startsWith(prefix) && it.fileName.toString().endsWith(suffix) }
    }

    private fun runWithKtorService(
        routingSetup: Routing.() -> Unit,
        action: (Int) -> Unit,
    ) {
        var server: ApplicationEngine? = null
        try {
            server = embeddedServer(CIO, host = "localhost", port = 0) {
                routing {
                    get("/isReady") {
                        call.respond(HttpStatusCode.OK)
                    }
                    routingSetup()
                }
            }.start()
            val port = runBlocking { server.resolvedConnectors().single().port }
            awaitInitialization(port)
            action(port)
        } finally {
            server?.stop(1000, 1000)
        }
    }

    private fun projectWithJvmPlugin(gradleVersion: GradleVersion, test: TestProject.() -> Unit) {
        project("empty", gradleVersion) {
            plugins {
                kotlin("jvm")
            }
            test()
        }
    }
}
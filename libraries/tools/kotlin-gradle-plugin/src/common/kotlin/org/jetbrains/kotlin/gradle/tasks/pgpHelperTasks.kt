/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.tasks

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.BCPGInputStream
import org.bouncycastle.bcpg.CompressionAlgorithmTags
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyPacket.VERSION_4
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.Features
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.openpgp.*
import org.bouncycastle.openpgp.operator.PGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilderProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPairGeneratorProvider
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.plugins.signing.SigningExtension
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import org.jetbrains.kotlin.gradle.plugin.KOTLIN_BOUNCY_CASTLE_CONFIGURATION_NAME
import org.jetbrains.kotlin.gradle.plugin.getExtension
import org.jetbrains.kotlin.gradle.targets.js.internal.toHex
import org.jetbrains.kotlin.gradle.utils.maybeCreateResolvable
import org.jetbrains.kotlin.gradle.utils.named
import org.jetbrains.kotlin.util.capitalizeDecapitalize.capitalizeAsciiOnly
import org.w3c.dom.Node
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.text.set

@DisableCachingByDefault(because = "PGP keys are not supposed to be cached. This task is intended for CLI usage.")
abstract class GeneratePgpKeys @Inject constructor(private val workerExecutor: WorkerExecutor) : DefaultTask() {
    @get:Input
    @get:Option(
        option = "name",
        description = "The name to be used for the generated key. Usually in the form of: 'Name Surname <email>'"
    )
    @get:Optional
    abstract val keyName: Property<String>

    @get:Input
    @get:Option(
        option = "password",
        description = "The password to be used for key encryption. If not provided, the value of the 'signing.password' Gradle property will be used."
    )
    @get:Optional
    abstract val password: Property<String>

    @get:Internal
    abstract val outputDirectory: DirectoryProperty

    @get:Internal
    abstract val bouncyCastleClasspath: ConfigurableFileCollection

    @get:Internal
    abstract val gradleHomePath: Property<String>

    @TaskAction
    fun execute() {
        require(keyName.isPresent) {
            """You must provide a value for the '--name' command line option, e.g. --name "Jane Doe <janedoe@example.com>""""
        }

        require(password.isPresent) {
            "You must provide a value for either the '--password' command line option or the 'signing.password' Gradle property "
        }

        val workQueue: WorkQueue = workerExecutor.classLoaderIsolation {
            it.classpath.from(bouncyCastleClasspath)
        }

        workQueue.submit(GenerateKeys::class.java, Action { parameters ->
            parameters.outputDirectory.set(outputDirectory)
            parameters.keyName.set(keyName)
            parameters.password.set(password.get())
            parameters.gradleHomePath.set(this@GeneratePgpKeys.gradleHomePath)
        })
    }

    internal interface GenerateKeyParameters : WorkParameters {
        val outputDirectory: DirectoryProperty
        val keyName: Property<String>
        val password: Property<String>
        val gradleHomePath: Property<String>
    }

    internal abstract class GenerateKeys : WorkAction<GenerateKeyParameters> {
        override fun execute() {
            val secretKeys =
                generateKeyRing(parameters.keyName.get(), parameters.password.get().toCharArray()) as PGPSecretKeyRing
            val publicKeys = PGPPublicKeyRing(secretKeys.publicKeys.asSequence().toList())
            val keyId = publicKeys.single { it.isMasterKey }.keyIdHex.takeLast(8).uppercase()
            val dir = parameters.outputDirectory.asFile.get()
            val files =
                listOf("secret_$keyId.gpg", "secret_$keyId.asc", "public_$keyId.gpg", "public_$keyId.asc", "example_$keyId.properties")
            files.forEach {
                check(
                    dir.resolve(it).exists().not()
                ) {
                    """
                    The output directory '${dir.absoluteFile}' already contains a file named '$it'.
                    Please move your existing key files to another location then try again.                    
                """.trimIndent()
                }
            }
            if (!dir.exists() && !dir.mkdirs()) {
                error("Failed to create output directory '${dir.absolutePath}'")
            }
            FileOutputStream(dir.resolve("secret_$keyId.gpg")).use { secretOut ->
                secretKeys.encode(secretOut)
            }
            ArmoredOutputStream(FileOutputStream(dir.resolve("secret_$keyId.asc"))).use { secretOut ->
                secretKeys.encode(secretOut)
            }

            FileOutputStream(dir.resolve("public_$keyId.gpg")).use { publicOut ->
                publicKeys.encode(publicOut)
            }
            ArmoredOutputStream(FileOutputStream(dir.resolve("public_$keyId.asc"))).use { publicOut ->
                publicKeys.encode(publicOut)
            }


            val exampleProperties = """
                signing.keyId=$keyId
                signing.password=<YOUR_PASSWORD>
                signing.secretKeyRingFile=/PATH/TO/secret_$keyId.gpg
            """.trimIndent()

            dir.resolve("example_$keyId.properties").writeText(exampleProperties)

            println(
                """
                Generated PGP keys and associated metadata in '${dir.absolutePath}'
                Please move your generated key files to a secure location and do not share the secret key or your password with others.
                The key ID of the generated key is '$keyId'.
                You can use this key ID to configure signing in your build script.
                
                For example, put the following in ${parameters.gradleHomePath.get()}${File.separator}gradle.properties:
                
${exampleProperties.prependIndent("                ")}
                
                More information: https://kotl.in/y470b1
                
                You can also find the armored ASCII version of the generated keys in the 'public_$keyId.asc' and 'secret_$keyId.asc' files.
                
                To upload your key to a PGP keyserver, you can use:
                
                gradlew uploadPublicPgpKey --keyring="${dir.resolve("public_$keyId.asc").absolutePath}"
            """.trimIndent()
            )
        }

        // adapted from the general outline of key pair generation from:
        // https://github.com/bcgit/bc-java/blob/main/pg/src/main/java/org/bouncycastle/openpgp/examples/EllipticCurveKeyPairGenerator.java
        // however, this implementation avoids using the JCA framework to not pollute the `java.security.Security` provider with BC classes
        private fun generateKeyRing(
            identity: String,
            password: CharArray?,
        ): Any {
            val generator = BcPGPKeyPairGeneratorProvider().get(VERSION_4, Date())
            val primaryKey = generator.generateLegacyEd25519KeyPair()

            val sha1Calc = BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1)
            val contentSignerBuilder: PGPContentSignerBuilder =
                BcPGPContentSignerBuilderProvider(HashAlgorithmTags.SHA512).get(primaryKey.publicKey)
            val secretKeyEncryptor = BcPBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1Calc).build(password)
            val primarySubpackets = PGPSignatureSubpacketGenerator()
            primarySubpackets.setKeyFlags(true, KeyFlags.SIGN_DATA)
            primarySubpackets.setPreferredHashAlgorithms(
                false, intArrayOf(
                    HashAlgorithmTags.SHA512, HashAlgorithmTags.SHA384, HashAlgorithmTags.SHA256, HashAlgorithmTags.SHA224
                )
            )
            primarySubpackets.setPreferredSymmetricAlgorithms(
                false, intArrayOf(
                    SymmetricKeyAlgorithmTags.AES_256, SymmetricKeyAlgorithmTags.AES_192, SymmetricKeyAlgorithmTags.AES_128
                )
            )
            primarySubpackets.setPreferredCompressionAlgorithms(
                false, intArrayOf(
                    CompressionAlgorithmTags.ZLIB,
                    CompressionAlgorithmTags.BZIP2,
                    CompressionAlgorithmTags.ZIP,
                    CompressionAlgorithmTags.UNCOMPRESSED
                )
            )
            primarySubpackets.setFeature(false, Features.FEATURE_MODIFICATION_DETECTION)
            primarySubpackets.setIssuerFingerprint(false, primaryKey.publicKey)

            val gen = PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION, primaryKey, identity,
                sha1Calc, primarySubpackets.generate(), null, contentSignerBuilder, secretKeyEncryptor
            )

            val secretKeys = gen.generateSecretKeyRing()
            return secretKeys
        }
    }
}

@DisableCachingByDefault(because = "Uploading keys to a keyserver is not cacheable. This task is intended for CLI usage.")
abstract class UploadPgpKeyTask : DefaultTask() {
    @get:Input
    @get:Option(
        option = "keyring",
        description = "The file that contains the public key to upload to the keyserver in armored ASCII format."
    )
    abstract val keyring: Property<String>

    @get:Input
    @get:Option(
        option = "keyserver",
        description = "The address of the keyserver to upload the key to. Default: 'https://keyserver.ubuntu.com'"
    )
    @get:Optional
    abstract val keyserver: Property<String>

    @TaskAction
    fun execute() {
        val publicKeyringFile = File(keyring.get())
        require(publicKeyringFile.isFile) {
            "The provided public keyring file does not exist or cannot be read: ${publicKeyringFile.absolutePath}"
        }
        val publicKeyringContent = publicKeyringFile.readText()
        require(publicKeyringContent.startsWith("-----BEGIN PGP PUBLIC KEY BLOCK-----")) {
            """
                The provided public keyring file does not start with '-----BEGIN PGP PUBLIC KEY BLOCK-----'.
                Please make sure that the provided file contains a valid public key in armored ASCII format.
            """.trimIndent()
        }
        val connection = URI.create("${keyserver.get()}/pks/add")
            .toURL()
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true
            connection.doInput = true
            connection.allowUserInteraction = true

            connection.outputStream.writer().buffered().use {
                it.write("keytext=")
                it.write(URLEncoder.encode(publicKeyringContent, StandardCharsets.UTF_8.toString()))
                // nm stands for "no modification", as described in the HKP protocol documentation:
                // https://www.ietf.org/archive/id/draft-gallagher-openpgp-hkp-04.html#name-the-nm-no-modification-opti
                it.write("&options=nm")
            }

            val result = connection.inputStream.reader().use { it.readText() }
            println("Key upload successful. Server returned:\n$result")

        } catch (e: IOException) {
            connection.errorStream?.reader()?.use { it.readText() }?.also { result ->
                println("Failed to upload public key. Server returned:\n$result")
            }
            throw e
        } finally {
            connection.disconnect()
        }
    }
}

internal fun Project.addPgpSignatureHelpers() {
    maybeCreateBcConfiguration()

    val pgpDirectory = project.layout.buildDirectory.dir("pgp")
    project.tasks.register("generatePgpKeys", GeneratePgpKeys::class.java) {
        it.notCompatibleWithConfigurationCache("Do not cache password.")
        it.outputDirectory.set(pgpDirectory)
        it.password.set(project.providers.gradleProperty("signing.password"))
        it.bouncyCastleClasspath.from(project.configurations.named(KOTLIN_BOUNCY_CASTLE_CONFIGURATION_NAME))
        it.gradleHomePath.set(project.gradle.gradleUserHomeDir.absolutePath)
        it.group = "signing"
        it.description = """
            Generates a new PGP keypair.
            
            Usage: 
            gradlew generatePgpKeys --name "Jane Doe <janedoe@example.com>" --password YOUR_PASSWORD
        """.trimIndent()
    }

    project.tasks.register("uploadPublicPgpKey", UploadPgpKeyTask::class.java) {
        it.keyserver.set("https://keyserver.ubuntu.com")
        it.group = "signing"
        it.description = "Uploads the public PGP key to a keyserver"
    }
}

private fun Project.maybeCreateBcConfiguration() {
    project
        .configurations
        .maybeCreateResolvable(KOTLIN_BOUNCY_CASTLE_CONFIGURATION_NAME) {
            attributes.attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category.LIBRARY))
            attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage.JAVA_RUNTIME))
            attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements.JAR))
        }
        .defaultDependencies {
            it.add(
                project.dependencies.create("org.bouncycastle:bcpkix-jdk18on:1.80")
            )
            it.add(
                project.dependencies.create("org.bouncycastle:bcpg-jdk18on:1.80")
            )
        }
}

abstract class CheckSigningTask @Inject constructor(private val workerExecutor: WorkerExecutor) : DefaultTask() {
    @get:Input
    abstract val signatoryExists: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val keyId: Property<String>

    @get:Input
    @get:Optional
    abstract val hasKeyPassword: Property<Boolean>

    @get:InputFile
    @get:Optional
    abstract val keyringPath: RegularFileProperty

    @get:Input
    abstract val gradleHomePath: Property<String>

    @get:Input
    @get:Optional
    abstract val signatoryKeyId: Property<String>

    @get:Input
    @get:Optional
    abstract val exampleSignature: Property<ByteArray>

    @get:Internal
    abstract val bouncyCastleClasspath: ConfigurableFileCollection

    @get:Input
    abstract val keyservers: ListProperty<String>

    internal interface CheckKeyserversParameters : WorkParameters {
        val signature: Property<String>
        val keyservers: ListProperty<String>
    }

    internal abstract class CheckKeyservers : WorkAction<CheckKeyserversParameters> {
        override fun execute() {
            val longKeyId = PGPSignature(
                BCPGInputStream(
                    Base64.getDecoder().decode(parameters.signature.get()).inputStream()
                )
            ).keyIdHex
            val keyFound = parameters.keyservers.get().map { URI.create("$it/pks/lookup?op=get&search=0x$longKeyId").toURL() }
                .any { url ->
                    val connection = url.openConnection() as HttpURLConnection
                    try {
                        connection.inputStream.reader().use {
                            println("Keyserver responded with:")
                            println(it.readText())
                        }
                        return@any true
                    } catch (e: IOException) {
                        if (connection.responseCode == 404) {
                            return@any false
                        } else {
                            throw e
                        }
                    } finally {
                        connection.disconnect()
                    }
                }
            check(keyFound) {
                """
                None of the keyservers contain the public key with id: ${longKeyId.takeLast(8)}
                Keyservers checked: ${parameters.keyservers.get().joinToString()}
                
                You can upload your key to a keyserver with:
                
                gradlew uploadPublicPgpKey --keyring=PATH/TO/ARMORED_ASCII_PUBLIC_KEY.asc
                
                Read more: https://central.sonatype.org/publish/requirements/gpg/#distributing-your-public-key
            """.trimIndent() // TODO replace link with kotl.in
            }
        }
    }

    @TaskAction
    fun execute() {
        val errorMessage = StringBuilder()
        if (!signatoryExists.getOrElse(false)) {
            if (keyId.isPresent || keyringPath.isPresent) {
                errorMessage.appendLine("Looks like you are trying to load the PGP key from disk.")
                if (keyId.isPresent) {
                    errorMessage.appendLine("Signing key ID: $keyId")
                } else {
                    errorMessage.appendLine("Key ID is not set. Please ensure you have the 'signing.keyId' property set to your key's ID.")
                }
                if (keyringPath.isPresent) {
                    errorMessage.appendLine("Keyring path: $keyringPath")
                    if (keyringPath.get().asFile.isFile) {
                        errorMessage.appendLine("Looks like the keyring path doesn't exist or cannot be read as a file.")
                    }
                } else {
                    errorMessage.appendLine("Keyring path is not set. Please ensure you have the 'signing.secretKeyRingFile' property set to your keyring's file path.")
                }
                if (hasKeyPassword.get()) {
                    errorMessage.appendLine("Signing key password is set.")
                } else {
                    errorMessage.appendLine("Signing key password is not set. Please ensure you have the 'signing.password' property set to your secret key's password.")
                }
                errorMessage.appendLine("Please double check the settings used for signing.")
                errorMessage.appendLine("For example, put the following in ${gradleHomePath.get()}${File.separator}gradle.properties:")
                errorMessage.appendLine(
                    """
                    signing.keyId=${keyId.getOrElse("<YOUR_KEY_ID>")}
                    signing.password=<YOUR_PASSWORD>
                    signing.secretKeyRingFile=${if (keyringPath.isPresent) keyringPath.get().asFile else "<YOUR_KEYRING_FILE_PATH>"}
                """.trimIndent()
                )
                errorMessage.appendLine("More information: https://kotl.in/XXXXX") // TODO add link
                error(errorMessage.toString())
            }
            errorMessage.append("Signing configuration was not successful.")
            errorMessage.append("Please follow the signing plugin configuration guide: https://kotl.in/XXXXX to configure a keyring file or in-memory key.") // TODO add link
            errorMessage.append("For example, put the following in ${gradleHomePath.get()}${File.separator}gradle.properties:")
            errorMessage.appendLine(
                """
                    signing.keyId=<YOUR_KEY_ID>
                    signing.password=<YOUR_PASSWORD>
                    signing.secretKeyRingFile=<YOUR_KEYRING_FILE_PATH>
                """.trimIndent()
            )
            errorMessage.appendLine("If you do not have a signing key, you can generate one by running the 'generatePgpKeys' task.")
            error(errorMessage.toString())
        }

        check(exampleSignature.isPresent)

        val workQueue: WorkQueue = workerExecutor.classLoaderIsolation {
            it.classpath.from(bouncyCastleClasspath)
        }

//        println("SHORT KEY ID " + keyId.get())
        workQueue.submit(CheckKeyservers::class.java, Action { parameters ->
            parameters.signature.set(Base64.getEncoder().encode(exampleSignature.get()).decodeToString())
            parameters.keyservers.set(keyservers)
        })
    }
}

abstract class CheckPomTask : DefaultTask() {
    @get:InputFile
    abstract val pom: Property<File>

    private fun gatherTags(currentTag: Node, currentPath: List<Node>, missingTags: MutableSet<String>) {
//        print("\t".repeat(currentPath.size) + "${currentTag.nodeName}:")
        currentTag.childrenIterator().forEach { node ->
            if (node.nodeType == Node.ELEMENT_NODE) {
                val newPath = currentPath + node
                missingTags -= newPath.joinToString("/") { it.nodeName }
//                println()
                gatherTags(node, newPath, missingTags)
            }
//            else if (node.nodeType == Node.TEXT_NODE && currentTag.childNodes.length == 1) {
//                print(" ${node.nodeValue}")
//            }
        }
    }

    @TaskAction
    fun execute() {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = builder.parse(pom.get())
        doc.documentElement.normalize()

        val projectTag = doc.getElementsByTagName("project").item(0)
        check(projectTag != null) { "Couldn't parse pom.xml - <project> tag not found!" }

        val missingTags = requiredPomTags.toMutableSet()
        gatherTags(projectTag, listOf(), missingTags)
        check(missingTags.isEmpty()) {
            "Missing tags:\n${missingTags.joinToString("\n") { it.split("/").joinToString(" ") { "<$it>" } }}"
        }


//        val pom = pomMap.get()
//        val missingKeys = requiredPomAttributes - pomMap.get().filter { it.value.isNotBlank() }.keys
//        var failed = false
//        p rintln("✅ [pom.xml] artifact coordinates: ${coordinate.get()}")
//        pom.filter { it.value.isNotBlank() }.forEach { key, value ->
//            println("✅ [pom.xml] $key: $value")
//        }
//        missingKeys.forEach { key ->
//            println("❌ [pom.xml] $key is not set or blank")
//            failed = true
//        }
//        if (failed) {
//            error("pom.xml is missing tags required for publication.")
//        }
    }

    companion object {
        val requiredPomTags = listOf(
            "groupId",
            "artifactId",
            "version",
            "name",
            "description",
            "url",
            "licenses",
            "licenses/license",
            "licenses/license/name",
            "licenses/license/name",
            "licenses/license/url",
            "developers",
            "developers/developer",
            "developers/developer/name",
            "developers/developer/email",
            "developers/developer/organization",
            "developers/developer/organizationUrl",
            "scm",
            "scm/connection",
            "scm/developerConnection",
            "scm/url"
        )
    }
}

internal fun Project.addLibraryPublishingHelpers() {
    maybeCreateBcConfiguration()
    val signingTask = project.tasks.register<CheckSigningTask>("checkSigningConfiguration") {
        group = "validation" // todo change to signing
        description = "Checks that a signing configuration is set up correctly."
        bouncyCastleClasspath.from(project.configurations.named(KOTLIN_BOUNCY_CASTLE_CONFIGURATION_NAME))
        keyservers.set(
            listOf(
                "https://keys.openpgp.org",
                "https://keyserver.ubuntu.com",
//              A third server is listed in the Sonatype docs as allowed,
//              but it regularly gives 502 errors when getting keys.
//                "https://pgp.mit.edu",
            )
        )
    }
    project.pluginManager.withPlugin("signing") {
        project.getExtension<SigningExtension>("signing")?.let { signing ->
            signingTask.configure { task ->
                task.gradleHomePath.set(project.gradle.gradleUserHomeDir.absolutePath)
                task.signatoryExists.set(signing.signatory != null)
                findProperty("signing.keyId")?.toString()?.let { task.keyId.set(it) }
                findProperty("signing.secretKeyRingFile")?.toString()?.let { task.keyringPath.set(file(it)) }
                findProperty("signing.password")?.toString()?.let { task.hasKeyPassword.set(true) } ?: task.hasKeyPassword.set(false)
                signing.signatory?.keyId?.let { task.signatoryKeyId.set(it) }
                signing.signatory?.sign("example".byteInputStream())?.let { task.exampleSignature.set(it) }
            }
        }
    }

    project.pluginManager.withPlugin("maven-publish") {
        project.getExtension<PublishingExtension>("publishing")?.let { publishing ->
            publishing.publications.withType<MavenPublication>().configureEach { publication ->
                val capitalizedPublicationName = "${publication.name.capitalizeAsciiOnly()}Publication"
                val generatePomTaskName = "generatePomFileFor$capitalizedPublicationName"
                val generatePomTask = tasks.withType<GenerateMavenPom>().named(generatePomTaskName)
//                    project.tasks.withType<GenerateMavenPom>().configureEach { task ->
//                        println(task.destination)
//                    }
                project.tasks.register<CheckPomTask>("checkPomFileFor${publication.name.capitalizeAsciiOnly()}Publication") {
                    dependsOn(generatePomTask)
                    group = "validation" //todo change to publishing
                    pom.set(generatePomTask.map { it.destination })
                }
//
//                        coordinate.set("${publication.groupId}:${publication.artifactId}:${publication.version}")
//                        val pom = publication.pom
//                        pomMap.put("name", pom.name.getOrElse(""))
//                        pomMap.put("description", pom.description.getOrElse(""))
//                        pomMap.put("url", pom.url.getOrElse(""))
//                        (pom as? MavenPomInternal)?.licenses?.forEachIndexed { i, license ->
//                            pomMap.put("license ${i + 1} name", license.name.getOrElse(""))
//                            pomMap.put("license ${i + 1} URL", license.url.getOrElse(""))
//                        }
//                        (pom as? MavenPomInternal)?.developers?.forEachIndexed { i, developer ->
//                            pomMap.put("developer ${i + 1} name", developer.name.getOrElse(""))
//                            pomMap.put("developer ${i + 1} email", developer.email.getOrElse(""))
//                            pomMap.put("developer ${i + 1} organization", developer.organization.getOrElse(""))
//                            pomMap.put("developer ${i + 1} organization URL", developer.organizationUrl.getOrElse(""))
//                        }
//
//                        (pom as? MavenPomInternal)?.scm?.let { scm ->
//                            pomMap.put("SCM connection", scm.connection.getOrElse(""))
//                            pomMap.put("SCM developerConnection", scm.developerConnection.getOrElse(""))
//                            pomMap.put("SCM URL", scm.url.getOrElse(""))
//                        }
//                    }
            }
        }
    }
}

private fun Node.childrenIterator(): Iterator<Node> = object : Iterator<Node> {
    private var current = firstChild

    override fun hasNext() = current != null

    override fun next(): Node {
        val next = current
        current = current!!.nextSibling
        return next
    }
}

private fun keyIdToHex(keyId: Long) = keyId.toULong().toString(16)
private val PGPSignature.keyIdHex get() = keyIdToHex(keyID)
private val PGPPublicKey.keyIdHex get() = keyIdToHex(keyID)
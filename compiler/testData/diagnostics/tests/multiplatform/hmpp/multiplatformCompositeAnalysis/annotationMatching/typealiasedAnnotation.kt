// RUN_PIPELINE_TILL: BACKEND
// FIR_IDENTICAL
// MODULE: common
expect annotation class Test()

@Test
expect fun unexpandedOnActual()

@Test
expect fun expandedOnActual()

// MODULE: main()()(common)
annotation class JunitTestInLib

actual typealias Test = JunitTestInLib

@Test
actual fun unexpandedOnActual() {}

@JunitTestInLib
actual fun expandedOnActual() {}

/* GENERATED_FIR_TAGS: actual, annotationDeclaration, expect, functionDeclaration, primaryConstructor,
typeAliasDeclaration */

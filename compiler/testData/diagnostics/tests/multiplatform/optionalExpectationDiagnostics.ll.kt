// IGNORE_FIR_DIAGNOSTICS
// RUN_PIPELINE_TILL: FIR2IR
// WITH_STDLIB
// OPT_IN: kotlin.ExperimentalMultiplatform

// MODULE: common
// FILE: common.kt

@OptionalExpectation
expect annotation class A()

fun useInSignature(a: <!OPTIONAL_DECLARATION_OUTSIDE_OF_ANNOTATION_ENTRY, OPTIONAL_DECLARATION_USAGE_IN_NON_COMMON_SOURCE!>A<!>) = a.toString()

<!WRONG_ANNOTATION_TARGET!>@OptionalExpectation<!>
expect class NotAnAnnotationClass

<!OPTIONAL_EXPECTATION_NOT_ON_EXPECTED!>@OptionalExpectation<!>
annotation class NotAnExpectedClass

annotation class InOtherAnnotation(val a: <!OPTIONAL_DECLARATION_OUTSIDE_OF_ANNOTATION_ENTRY, OPTIONAL_DECLARATION_USAGE_IN_NON_COMMON_SOURCE!>A<!>)

@InOtherAnnotation(<!OPTIONAL_DECLARATION_OUTSIDE_OF_ANNOTATION_ENTRY, OPTIONAL_DECLARATION_USAGE_IN_NON_COMMON_SOURCE!>A()<!>)
fun useInOtherAnnotation() {}

expect class C {
    @OptionalExpectation
    annotation class Nested
}

// MODULE: platform()()(common)
// FILE: platform.kt

@<!OPTIONAL_DECLARATION_USAGE_IN_NON_COMMON_SOURCE!>A<!>
class D

fun useInReturnType(): <!OPTIONAL_DECLARATION_OUTSIDE_OF_ANNOTATION_ENTRY, OPTIONAL_DECLARATION_USAGE_IN_NON_COMMON_SOURCE!>A?<!> = null

annotation class AnotherAnnotation(val a: <!OPTIONAL_DECLARATION_OUTSIDE_OF_ANNOTATION_ENTRY, OPTIONAL_DECLARATION_USAGE_IN_NON_COMMON_SOURCE!>A<!>)

@AnotherAnnotation(<!OPTIONAL_DECLARATION_OUTSIDE_OF_ANNOTATION_ENTRY, OPTIONAL_DECLARATION_USAGE_IN_NON_COMMON_SOURCE!>A()<!>)
fun useInAnotherAnnotation() {}

actual class C {
    actual annotation class Nested
}

/* GENERATED_FIR_TAGS: actual, annotationDeclaration, classDeclaration, expect, functionDeclaration, nestedClass,
nullableType, primaryConstructor, propertyDeclaration */

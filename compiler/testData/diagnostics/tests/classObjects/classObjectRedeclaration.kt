// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL
// DIAGNOSTICS: -DUPLICATE_CLASS_NAMES
package test

class A {
    object <!REDECLARATION!>Companion<!>

    companion <!REDECLARATION!>object<!>
}

class B {
    companion object <!REDECLARATION!>Named<!>

    object <!REDECLARATION!>Named<!>
}

class C {
    class <!REDECLARATION!>Named<!>

    companion object <!REDECLARATION!>Named<!>
}

/* GENERATED_FIR_TAGS: classDeclaration, companionObject, nestedClass, objectDeclaration */

// RUN_PIPELINE_TILL: FRONTEND
// FILE: p/Foo.java
package p;

class Foo {
    public static class Nested {}
}

// FILE: foo.kt
package a

import p.<!INVISIBLE_REFERENCE!>Foo<!>
import p.<!INVISIBLE_REFERENCE!>Foo<!>.Nested

class Bar : <!EXPOSED_SUPER_CLASS, INVISIBLE_REFERENCE, INVISIBLE_REFERENCE!>Foo<!>() {
    protected fun <!EXPOSED_FUNCTION_RETURN_TYPE!>foo<!>(): <!INVISIBLE_REFERENCE!>Nested<!>? = null
}

private fun foo(): <!INVISIBLE_REFERENCE!>Nested<!>? = null
private fun bar(): p.<!INVISIBLE_REFERENCE!>Foo<!>.Nested? = null

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration */

// RUN_PIPELINE_TILL: FRONTEND
package test

class Foo {
    fun <T> bar(x: Int) = x
}

fun test() {
    Foo::<!NEW_INFERENCE_NO_INFORMATION_FOR_PARAMETER!>bar<!> <!SYNTAX!>< <!DEBUG_INFO_MISSING_UNRESOLVED!>Int<!> ><!> <!SYNTAX!>(2 <!DEBUG_INFO_MISSING_UNRESOLVED!>+<!> 2)<!>
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, nullableType, typeParameter */

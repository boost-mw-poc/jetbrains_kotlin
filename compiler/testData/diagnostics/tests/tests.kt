// FIR_IDENTICAL
// RUN_PIPELINE_TILL: BACKEND
// FIR_DUMP
// WITH_STDLIB
// LANGUAGE: +ContextParameters

val a: ()->Unit = {}

fun foo(a: ()->Unit) {}

fun bar(): ()->Unit { return {} }

class A : ()->Unit {
    override fun invoke() {
    }
}

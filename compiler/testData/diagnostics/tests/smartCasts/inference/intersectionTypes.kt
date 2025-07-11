// RUN_PIPELINE_TILL: FRONTEND
// CHECK_TYPE

package a

import checkSubtype

fun <T> id(t: T): T = t

fun <T> two(u: T, v: T): T = u

fun <T> three(a: T, b: T, c: T): T = c

interface A
interface B: A
interface C: A

fun test(a: A, b: B, c: C) {
    if (a is B && a is C) {
        val d: C = id(a)
        val e: Any = id(a)
        val f = id(a)
        checkSubtype<A>(f)
        val g = two(<!DEBUG_INFO_SMARTCAST!>a<!>, b)
        checkSubtype<B>(g)
        checkSubtype<A>(g)

        // smart cast isn't needed, but is reported due to KT-4294
        val h: Any = two(<!DEBUG_INFO_SMARTCAST!>a<!>, b)

        val k = three(a, b, c)
        checkSubtype<A>(k)
        checkSubtype<B>(<!TYPE_MISMATCH!>k<!>)
        val l: Int = <!TYPE_MISMATCH, TYPE_MISMATCH, TYPE_MISMATCH, TYPE_MISMATCH!>three(a, b, c)<!>

        use(d, e, f, g, h, k, l)
    }
}

fun <T> foo(t: T, l: MutableList<T>): T = t

fun testErrorMessages(a: A, ml: MutableList<String>) {
    if (a is B && a is C) {
        foo(a, <!TYPE_MISMATCH!>ml<!>)
    }

    if(a is C) {
        foo(a, <!TYPE_MISMATCH!>ml<!>)
    }
}

fun rr(s: String?) {
    if (s != null) {
        val l = arrayListOf("", <!DEBUG_INFO_SMARTCAST!>s<!>)
        checkSubtype<MutableList<String>>(l)
        checkSubtype<MutableList<String?>>(<!TYPE_MISMATCH!>l<!>)
    }
}

//from library
fun <T> arrayListOf(vararg values: T): MutableList<T> = throw Exception()

fun use(vararg a: Any) = a

/* GENERATED_FIR_TAGS: andExpression, classDeclaration, equalityExpression, funWithExtensionReceiver,
functionDeclaration, functionalType, ifExpression, infix, interfaceDeclaration, intersectionType, isExpression,
localProperty, nullableType, outProjection, propertyDeclaration, smartcast, stringLiteral, typeParameter,
typeWithExtension, vararg */

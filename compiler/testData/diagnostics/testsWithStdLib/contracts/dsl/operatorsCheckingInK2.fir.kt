// RUN_PIPELINE_TILL: FRONTEND
// OPT_IN: kotlin.contracts.ExperimentalContracts

import plusAssign
import kotlin.contracts.*

class A(var v: Int = 0)

operator fun A.plus(a: A?): A {
    contract { returns() implies (a != null) }
    return A(v + a!!.v)
}

operator fun A.plusAssign(a: A?) {
    contract { returns() implies (a != null) }
    a!!
}

fun test_xAssign(newA: () -> A?) {
    val a = A()
    newA(). let {
        a + it
        it.v
    }
    newA().let {
        a += it
        it.v
    }
    var a1 = A()
    newA(). let {
        a1 + it
        it.v
    }
    newA().let {
        a1 <!ASSIGN_OPERATOR_AMBIGUITY!>+=<!> it
        it<!UNSAFE_CALL!>.<!>v
    }
}

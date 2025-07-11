// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL
// LANGUAGE: +WarnAboutNonExhaustiveWhenOnAlgebraicTypes
fun test1() {
    if (true) {
        <!NO_ELSE_IN_WHEN!>when<!> (true) {
            true -> println()
        }
    } else {
        System.out?.println() // kotlin.Unit?
    }
}

fun test2() {
    val mlist = arrayListOf("")
    if (true) {
        <!NO_ELSE_IN_WHEN!>when<!> (true) {
            true -> println()
        }
    } else {
        mlist.add("") // kotlin.Boolean
    }
}

/* GENERATED_FIR_TAGS: equalityExpression, flexibleType, functionDeclaration, ifExpression, javaFunction, javaProperty,
localProperty, nullableType, propertyDeclaration, safeCall, stringLiteral, whenExpression, whenWithSubject */

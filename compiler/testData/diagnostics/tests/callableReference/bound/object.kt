// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL
// CHECK_TYPE

object Obj {
    fun foo() {}
    val bar = 2
}

fun test() {
    checkSubtype<() -> Unit>(Obj::foo)
    checkSubtype<() -> Int>(Obj::bar)
}

/* GENERATED_FIR_TAGS: callableReference, classDeclaration, funWithExtensionReceiver, functionDeclaration,
functionalType, infix, integerLiteral, nullableType, objectDeclaration, propertyDeclaration, typeParameter,
typeWithExtension */

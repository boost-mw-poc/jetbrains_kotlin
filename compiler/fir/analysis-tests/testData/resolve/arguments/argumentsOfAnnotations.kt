// RUN_PIPELINE_TILL: FRONTEND
annotation class Ann(val x: Int, val y: String, val z: String = "z")

@Ann(y = "y", x = 10)
class A

annotation class AnnVarargs(val x: Int, vararg val y: String, val z: Int)

@AnnVarargs(1, "a", "b", "c", <!NO_VALUE_FOR_PARAMETER!><!ARGUMENT_TYPE_MISMATCH!>2<!>)<!>
class B

/* GENERATED_FIR_TAGS: annotationDeclaration, classDeclaration, integerLiteral, outProjection, primaryConstructor,
propertyDeclaration, stringLiteral, vararg */

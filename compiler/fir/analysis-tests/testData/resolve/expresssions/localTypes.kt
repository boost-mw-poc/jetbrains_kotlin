// RUN_PIPELINE_TILL: BACKEND
interface Foo

fun foo() {
    val x: Int = 1
    class Bar : Foo {
        val y: String = ""
        fun Int.bar(s: String): Boolean {
            val z: Double = 0.0
            return true
        }
        val Boolean.w: Char get() = ' '
        fun <T : Foo> id(arg: T): T = arg
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, getter, integerLiteral,
interfaceDeclaration, localClass, localProperty, propertyDeclaration, propertyWithExtensionReceiver, stringLiteral,
typeConstraint, typeParameter */

// RUN_PIPELINE_TILL: BACKEND
// FIR_IDENTICAL
interface Y {
    fun foo(a : Int = 1)
}

interface YSub : Y {

}

class Z2 : Y, YSub {
    override fun foo(a : Int) {}
}

object Z2O : Y, YSub {
    override fun foo(a : Int) {}
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, integerLiteral, interfaceDeclaration, objectDeclaration,
override */

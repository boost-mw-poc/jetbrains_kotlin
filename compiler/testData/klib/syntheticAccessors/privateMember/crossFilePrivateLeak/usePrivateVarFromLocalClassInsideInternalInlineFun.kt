// FILE: A.kt
class A {
    private var privateVar = 22

    @Suppress("NOT_YET_SUPPORTED_IN_INLINE")
    internal inline fun internalGetValue(): Int {
        class LocalGet {
            fun localGet(): Int = privateVar
        }
        return <!LESS_VISIBLE_CONTAINING_CLASS_IN_INLINE_WARNING, LESS_VISIBLE_TYPE_IN_INLINE_ACCESSED_SIGNATURE_WARNING!>LocalGet<!>().<!LESS_VISIBLE_CONTAINING_CLASS_IN_INLINE_WARNING!>localGet<!>()
    }

    @Suppress("NOT_YET_SUPPORTED_IN_INLINE")
    internal inline fun internalSetValue(value: Int) {
        class LocalSet {
            fun localSet(n: Int) { privateVar = n }
        }
        <!LESS_VISIBLE_CONTAINING_CLASS_IN_INLINE_WARNING, LESS_VISIBLE_TYPE_IN_INLINE_ACCESSED_SIGNATURE_WARNING!>LocalSet<!>().<!LESS_VISIBLE_CONTAINING_CLASS_IN_INLINE_WARNING!>localSet<!>(value)
    }
}

// FILE: main.kt
fun box(): String {
    var result = 0
    A().run {
        result += internalGetValue()
        internalSetValue(20)
        result += internalGetValue()
    }
    if (result != 42) return result.toString()
    return "OK"
}

private fun interface I {
    fun foo(): Int
}

@Suppress("NON_PUBLIC_CALL_FROM_PUBLIC_INLINE", "IR_PRIVATE_TYPE_USED_IN_NON_PRIVATE_INLINE_FUNCTION_ERROR")
inline fun publicInlineFun(): Int = (I { 1 }).foo()

@Suppress("PRIVATE_CLASS_MEMBER_FROM_INLINE", "IR_PRIVATE_TYPE_USED_IN_NON_PRIVATE_INLINE_FUNCTION_ERROR")
internal inline fun internalInlineFun(): Int = (<!LESS_VISIBLE_TYPE_IN_INLINE_ACCESSED_SIGNATURE_WARNING!>I<!> { 1 }).<!LESS_VISIBLE_CONTAINING_CLASS_IN_INLINE_WARNING!>foo<!>()

fun box(): String {
    var result = 0
    result += publicInlineFun()
    result += internalInlineFun()
    return if (result == 2) "OK" else result.toString()
}

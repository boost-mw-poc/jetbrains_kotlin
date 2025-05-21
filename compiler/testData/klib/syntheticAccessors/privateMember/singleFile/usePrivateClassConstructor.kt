private class Private{
    fun foo() = "OK"
}

@Suppress("IR_PRIVATE_TYPE_USED_IN_NON_PRIVATE_INLINE_FUNCTION_ERROR")
internal inline fun internalInlineFun(): String {
    @Suppress("PRIVATE_CLASS_MEMBER_FROM_INLINE")
    return <!LESS_VISIBLE_CONTAINING_CLASS_IN_INLINE_WARNING, LESS_VISIBLE_TYPE_IN_INLINE_ACCESSED_SIGNATURE_WARNING!>Private<!>().<!LESS_VISIBLE_CONTAINING_CLASS_IN_INLINE_WARNING!>foo<!>()
}

fun box(): String {
    return internalInlineFun()
}

// RUN_PIPELINE_TILL: FRONTEND
// ISSUE: KT-65029
// DIAGNOSTICS: -NOTHING_TO_INLINE

private class PrivateClass
private object PrivateObject {
    fun foo() {}
}

public inline fun inlineFun(obj: Any) {
    <!NON_PUBLIC_CALL_FROM_PUBLIC_INLINE!>PrivateObject<!>.toString()
    <!NON_PUBLIC_CALL_FROM_PUBLIC_INLINE!>PrivateObject<!>.<!NON_PUBLIC_CALL_FROM_PUBLIC_INLINE!>foo<!>()
    <!NON_PUBLIC_CALL_FROM_PUBLIC_INLINE!>PrivateClass<!>::class
}

internal inline fun internalInlineFun(obj: Any) {
    PrivateObject.toString()
    PrivateObject.<!PRIVATE_CLASS_MEMBER_FROM_INLINE!>foo<!>()
    PrivateClass::class
}

open class Parent {
    protected class ProtectedClass
    protected object ProtectedObject {
        fun foo() {}
    }
}

public class PublicClass : Parent() {
    public inline fun inlineFun() {
        ProtectedObject.toString()
        ProtectedObject.<!PROTECTED_CALL_FROM_PUBLIC_INLINE_ERROR!>foo<!>()
        ProtectedClass::class
    }
}

public class PublicClass2 {
    private object Obj
    private companion object {}

    public inline fun inlineFun() {
        <!NON_PUBLIC_CALL_FROM_PUBLIC_INLINE!>Obj<!>
        <!NON_PUBLIC_CALL_FROM_PUBLIC_INLINE!>Companion<!>
    }

    internal inline fun internalInlineFun() {
        Obj
        Companion
    }
}

// RUN_PIPELINE_TILL: FRONTEND
// ISSUE: KT-65029
// DIAGNOSTICS: -NOTHING_TO_INLINE

private class Private

public inline fun inlineFun(obj: Any) {
    obj is <!NON_PUBLIC_CALL_FROM_PUBLIC_INLINE!>Private<!>
    obj as <!NON_PUBLIC_CALL_FROM_PUBLIC_INLINE!>Private<!>
    useAsTypeArg<<!NON_PUBLIC_CALL_FROM_PUBLIC_INLINE!>Private<!>>()
}

internal inline fun internalInlineFun(obj: Any) {
    obj is Private
    obj as Private
    useAsTypeArg<Private>()
}

open class Parent {
    protected class ProtectedClass
}

public class PublicClass : Parent() {
    public inline fun inlineFun(obj: Any) {
        obj is ProtectedClass
        useAsTypeArg<ProtectedClass>()
    }
}

fun <T> useAsTypeArg() {}

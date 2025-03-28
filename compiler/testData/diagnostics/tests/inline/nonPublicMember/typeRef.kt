// FIR_IDENTICAL
// RUN_PIPELINE_TILL: BACKEND
// ISSUE: KT-65029
// DIAGNOSTICS: -NOTHING_TO_INLINE

private class Private

public inline fun inlineFun(obj: Any) {
    obj is Private
    obj as Private
    useAsTypeArg<Private>()
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

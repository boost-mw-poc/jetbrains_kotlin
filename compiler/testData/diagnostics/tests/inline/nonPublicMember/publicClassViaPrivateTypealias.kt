// RUN_PIPELINE_TILL: BACKEND
// DIAGNOSTICS: -NOTHING_TO_INLINE

class PublicClass
private typealias PrivateTypealias = PublicClass

public inline fun inlineFun(obj: Any) {
    <!NON_PUBLIC_CALL_FROM_PUBLIC_INLINE!>PrivateTypealias<!>()
    obj is PrivateTypealias
}

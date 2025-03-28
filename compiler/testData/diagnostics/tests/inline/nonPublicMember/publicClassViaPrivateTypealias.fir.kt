// RUN_PIPELINE_TILL: BACKEND
// DIAGNOSTICS: -NOTHING_TO_INLINE

class PublicClass
private typealias PrivateTypealias = PublicClass

public inline fun inlineFun(obj: Any) {
    PrivateTypealias()
    obj is PrivateTypealias
}

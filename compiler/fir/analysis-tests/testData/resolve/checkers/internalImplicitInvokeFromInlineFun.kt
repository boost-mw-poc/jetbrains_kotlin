// RUN_PIPELINE_TILL: FRONTEND
// ISSUE: KT-20223

private class Private {
}
private object PrivateObj {
}

public inline fun foo(obj: Any) {
    obj is Private
    obj as? Private
    Private::class
    PrivateObj.toString()
    useAsTypeParam<Private>()
}

fun <T> useAsTypeParam() {}
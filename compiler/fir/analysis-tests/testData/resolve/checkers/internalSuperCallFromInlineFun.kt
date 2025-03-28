// RUN_PIPELINE_TILL: FRONTEND
// ISSUE: KT-54518

class Foo {
    private class Bar
}

fun test() {
    withParam<Foo.Bar>()
}

fun <T> withParam() {}

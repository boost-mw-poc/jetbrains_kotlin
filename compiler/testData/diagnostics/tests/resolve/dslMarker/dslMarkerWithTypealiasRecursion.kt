// RUN_PIPELINE_TILL: FRONTEND
// NI_EXPECTED_FILE
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPEALIAS)
annotation class XMarker

@XMarker
class Foo

class Bar

typealias YBar = <!RECURSIVE_TYPEALIAS_EXPANSION!>ZBar<!>
typealias ZBar = <!RECURSIVE_TYPEALIAS_EXPANSION!>YBar<!>

fun Foo.foo(body: Foo.() -> Unit) = body()
fun Foo.zbar(body: <!RECURSIVE_TYPEALIAS_EXPANSION!>ZBar<!>.() -> Unit) = Bar().body()

fun test() {
    Foo().foo {
        zbar {
            <!DEBUG_INFO_MISSING_UNRESOLVED!>foo<!> {}
        }
    }
}

/* GENERATED_FIR_TAGS: annotationDeclaration, classDeclaration, funWithExtensionReceiver, functionDeclaration,
functionalType, lambdaLiteral, typeAliasDeclaration, typeWithExtension */

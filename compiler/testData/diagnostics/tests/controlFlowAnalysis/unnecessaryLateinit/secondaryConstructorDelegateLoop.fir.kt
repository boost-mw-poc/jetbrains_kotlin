// RUN_PIPELINE_TILL: FRONTEND
// DIAGNOSTICS: -UNUSED_PARAMETER

class Foo {
    lateinit var bar: String

    constructor() {
        bar = ""
    }

    constructor(a: Int) : <!CYCLIC_CONSTRUCTOR_DELEGATION_CALL!>this<!>(a, 0, 0) {
    }

    constructor(a: Int, b: Int) : <!CYCLIC_CONSTRUCTOR_DELEGATION_CALL!>this<!>(a) {
    }

    constructor(a: Int, b: Int, c: Int) : <!CYCLIC_CONSTRUCTOR_DELEGATION_CALL!>this<!>(a, b) {
    }
}

/* GENERATED_FIR_TAGS: assignment, classDeclaration, integerLiteral, lateinit, propertyDeclaration, secondaryConstructor,
stringLiteral */

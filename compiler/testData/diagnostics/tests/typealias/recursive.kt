// RUN_PIPELINE_TILL: FRONTEND
typealias R = <!RECURSIVE_TYPEALIAS_EXPANSION!>R<!>

typealias L = <!RECURSIVE_TYPEALIAS_EXPANSION!>List<L><!>

typealias A = <!RECURSIVE_TYPEALIAS_EXPANSION!>B<!>
typealias B = <!RECURSIVE_TYPEALIAS_EXPANSION!>A<!>

typealias F1 = <!RECURSIVE_TYPEALIAS_EXPANSION!>(Int) -> F2<!>
typealias F2 = <!RECURSIVE_TYPEALIAS_EXPANSION!>(F1) -> Int<!>
typealias F3 = <!RECURSIVE_TYPEALIAS_EXPANSION!>(F1) -> Int<!>

val x: <!RECURSIVE_TYPEALIAS_EXPANSION!>F3<!> = TODO()

/* GENERATED_FIR_TAGS: functionalType, propertyDeclaration, typeAliasDeclaration */

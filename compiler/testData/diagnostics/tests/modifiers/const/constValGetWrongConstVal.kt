// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL

const val static = <!CONST_VAL_WITH_NON_CONST_INITIALIZER!>{ -10 }()<!>
const val copy = <!CONST_VAL_WITH_NON_CONST_INITIALIZER!>static<!>

/* GENERATED_FIR_TAGS: const, integerLiteral, lambdaLiteral, propertyDeclaration */

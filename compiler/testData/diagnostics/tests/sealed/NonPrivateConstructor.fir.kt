// RUN_PIPELINE_TILL: FRONTEND
sealed class Sealed protected constructor(val x: Int) {
    object FIRST : Sealed()

    <!NON_PRIVATE_OR_PROTECTED_CONSTRUCTOR_IN_SEALED!>public constructor(): this(42)<!>

    constructor(y: Int, z: Int): this(y + z)
}

/* GENERATED_FIR_TAGS: additiveExpression, classDeclaration, integerLiteral, nestedClass, objectDeclaration,
primaryConstructor, propertyDeclaration, sealed, secondaryConstructor */

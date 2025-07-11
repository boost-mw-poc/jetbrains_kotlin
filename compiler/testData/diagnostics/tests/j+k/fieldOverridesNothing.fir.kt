// RUN_PIPELINE_TILL: BACKEND
// LANGUAGE: +PreferJavaFieldOverload

// FILE: B.java

public abstract class B implements A {
    public int size = 1;
}

// FILE: main.kt

interface A {
    val size: Int
}

class C : B() {
    override val <!PROPERTY_HIDES_JAVA_FIELD!>size<!>: Int get() = 1
}

fun foo() {
    C().size
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, getter, integerLiteral, interfaceDeclaration, javaType,
override, propertyDeclaration */

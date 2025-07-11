// DISABLE_JAVA_FACADE
// RUN_PIPELINE_TILL: BACKEND
// FIR_IDENTICAL
// DIAGNOSTICS: -UNUSED_VARIABLE
// FILE: A.java

import java.util.*;

class A<T> {
    List<T> x;
    Map<T, T> y;

    A<Map<String, T>> z;

    void foo(T x, List<T> y, List<Map<Integer, T>> z) {}

    A<List<T>> bar() {}
}

// FILE: Test.java

class Test {
    static A rawAField = null;
}

// FILE: main.kt

val strList: List<String> = null!!
val strMap: Map<String, String> = null!!

fun main() {
    val rawA = Test.rawAField
    rawA.x = strList
    rawA.y = strMap
    rawA.foo("", strList, strList)

    val barResult = rawA.bar()

    barResult.x = strList
    barResult.y = strMap
    barResult.foo("", strList, null)
}

/* GENERATED_FIR_TAGS: assignment, checkNotNullCall, flexibleType, functionDeclaration, javaProperty, localProperty,
nullableType, propertyDeclaration, stringLiteral */

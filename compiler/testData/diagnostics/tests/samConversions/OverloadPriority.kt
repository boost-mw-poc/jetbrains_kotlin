// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL
// LANGUAGE: +EliminateAmbiguitiesOnInheritedSamInterfaces
// CHECK_TYPE
// FILE: Fn.java
public interface Fn<T, R> {
    R apply(T t);
}

// FILE: Fn2.java
public interface Fn2<T, R> extends Fn<T, R> {}

// FILE: J.java
public interface J {
    String foo(Fn<String, Object> f, Object o);
    int foo(Fn<Object, Object> f, String s); // (Any) -> Any <: (String) -> Any <=> String <: Any

    String bas(Fn<Object, Object> f, Object o);
    int bas(Fn<Object, String> f, String s); // (Any) -> String <: (Any) -> Any <=> String <: Any

    String bar(Fn<String, Object> f);
    int bar(Fn2<String, Object> f); // Fn2 seems more specific one even function type same
}

// FILE: 1.kt
fun test(j: J) {
    j.foo({ it checkType { _<Any>() }; "" }, "") checkType { _<Int>() }

    j.bas({ it checkType { _<Any>() }; "" }, "") checkType { _<Int>() }

    j.bar { it checkType { _<String>() }; "" } checkType { _<Int>() }
}

/* GENERATED_FIR_TAGS: classDeclaration, flexibleType, funWithExtensionReceiver, functionDeclaration, functionalType,
infix, javaFunction, javaType, lambdaLiteral, nullableType, samConversion, stringLiteral, typeParameter,
typeWithExtension */

// LL_FIR_DIVERGENCE
// diverges until Analysis API implements context parameters
// LL_FIR_DIVERGENCE
// RUN_PIPELINE_TILL: FRONTEND
// LANGUAGE: +ContextParameters


open class A
class B: A()

class C


// 'contextOf'

// green code
context(a: A) fun usage1() { <!NO_CONTEXT_ARGUMENT!>contextOf<!><A>() }
context(a: A) fun usage2(): A = <!NO_CONTEXT_ARGUMENT!>contextOf<!><A>()
context(a: A) fun usage3() = <!NO_CONTEXT_ARGUMENT!>contextOf<!><A>()

// green code
fun A.usage4() { <!NO_CONTEXT_ARGUMENT!>contextOf<!><A>() }
fun A.usage5(): A = <!NO_CONTEXT_ARGUMENT!>contextOf<!><A>()
fun A.usage6() = <!NO_CONTEXT_ARGUMENT!>contextOf<!><A>()

// green code
context(b: B) fun usage7() { <!NO_CONTEXT_ARGUMENT!>contextOf<!><A>() }
context(b: B) fun usage8(): A = <!NO_CONTEXT_ARGUMENT!>contextOf<!><A>()
context(b: B) fun usage9(): A = <!NO_CONTEXT_ARGUMENT!>contextOf<!><B>()

// red code (ambiguous context argument)
context(a: A) fun A.usage10() { <!NO_CONTEXT_ARGUMENT!>contextOf<!><A>() }
context(a: A) fun A.usage11(): A = <!NO_CONTEXT_ARGUMENT!>contextOf<!><A>()
context(a: A, b: B) fun usage12() { <!NO_CONTEXT_ARGUMENT!>contextOf<!><A>() }
context(a: A, b: B) fun usage13(): A = <!NO_CONTEXT_ARGUMENT!>contextOf<!><A>()

// green code
context(a: A, c: C) fun usage14() { <!NO_CONTEXT_ARGUMENT!>contextOf<!><A>() }
context(a: A, c: C) fun usage15(): A = <!NO_CONTEXT_ARGUMENT!>contextOf<!><A>()

// red code (lack of type inference via context argument)
context(a: A) fun usage16() { <!CANNOT_INFER_PARAMETER_TYPE, NO_CONTEXT_ARGUMENT!>contextOf<!>() }
context(a: A) fun usage17() = <!CANNOT_INFER_PARAMETER_TYPE, NO_CONTEXT_ARGUMENT!>contextOf<!>()
fun A.usage18() { <!CANNOT_INFER_PARAMETER_TYPE, NO_CONTEXT_ARGUMENT!>contextOf<!>() }
fun A.usage19() = <!CANNOT_INFER_PARAMETER_TYPE, NO_CONTEXT_ARGUMENT!>contextOf<!>()
context(a: A) fun A.usage20() { <!CANNOT_INFER_PARAMETER_TYPE, NO_CONTEXT_ARGUMENT!>contextOf<!>() }
context(a: A, b: B) fun usage21() { <!CANNOT_INFER_PARAMETER_TYPE, NO_CONTEXT_ARGUMENT!>contextOf<!>() }
context(a: A, c: C) fun usage22() { <!CANNOT_INFER_PARAMETER_TYPE, NO_CONTEXT_ARGUMENT!>contextOf<!>() }

// red code (lack of type inference via return type)
context(a: A) fun usage23(): A = <!CANNOT_INFER_PARAMETER_TYPE, NO_CONTEXT_ARGUMENT!>contextOf<!>()
fun A.usage24(): A = <!CANNOT_INFER_PARAMETER_TYPE, NO_CONTEXT_ARGUMENT!>contextOf<!>()
context(b: B) fun usage25(): A = <!CANNOT_INFER_PARAMETER_TYPE, NO_CONTEXT_ARGUMENT!>contextOf<!>()
context(a: A) fun A.usage26(): A = <!CANNOT_INFER_PARAMETER_TYPE, NO_CONTEXT_ARGUMENT!>contextOf<!>()
context(a: A, b: B) fun usage27(): A = <!CANNOT_INFER_PARAMETER_TYPE, NO_CONTEXT_ARGUMENT!>contextOf<!>()
context(a: A, c: C) fun usage28(): A = <!CANNOT_INFER_PARAMETER_TYPE, NO_CONTEXT_ARGUMENT!>contextOf<!>()


// 'context' w/ one context parameter

// green code
fun context1() { context(B()) { <!NO_CONTEXT_ARGUMENT!>usage1<!>() } }
fun context2(): A = context(B()) { <!NO_CONTEXT_ARGUMENT!>usage2<!>() }
fun context3(): A = context(B()) { <!NO_CONTEXT_ARGUMENT!>usage3<!>() }

// red code (context parameters cannot be used as extension receivers)
fun context4() { <!CANNOT_INFER_PARAMETER_TYPE!>context<!>(B()) { <!UNRESOLVED_REFERENCE!>usage4<!>() } }
fun context5(): A = context(B()) { <!UNRESOLVED_REFERENCE!>usage5<!>() }
fun context6(): A = context(B()) { <!UNRESOLVED_REFERENCE!>usage6<!>() }

// green code
fun context7() { context(B()) { <!NO_CONTEXT_ARGUMENT!>usage7<!>() } }
fun context8(): A = context(B()) { <!NO_CONTEXT_ARGUMENT!>usage8<!>() }
fun context9(): A = context(B()) { <!NO_CONTEXT_ARGUMENT!>usage9<!>() }

// green code
fun context10v1() { context(B()) { with(B()) { usage10() } } }
fun context11v1(): A = context(B()) { with(B()) { usage11() } }

// red code (context parameters cannot be used as extension receivers)
fun context10v2() { <!CANNOT_INFER_PARAMETER_TYPE!>context<!>(B()) { <!UNRESOLVED_REFERENCE!>usage10<!>() } }
fun context11v2(): A = context(B()) { <!UNRESOLVED_REFERENCE!>usage11<!>() }

// green code
fun context12() { context(B()) { <!NO_CONTEXT_ARGUMENT!>usage12<!>() } }
fun context13(): A = context(B()) { <!NO_CONTEXT_ARGUMENT!>usage13<!>() }

// green code
fun context14v1() { context(B()) { context(C()) { <!NO_CONTEXT_ARGUMENT!>usage14<!>() } } }
fun context15v1(): A = context(B()) { context(C()) { <!NO_CONTEXT_ARGUMENT!>usage15<!>() } }

// red code (no context argument)
fun context14v2() { context(B()) { <!NO_CONTEXT_ARGUMENT!>usage14<!>() } }
fun context15v2(): A = context(B()) { <!NO_CONTEXT_ARGUMENT!>usage15<!>() }

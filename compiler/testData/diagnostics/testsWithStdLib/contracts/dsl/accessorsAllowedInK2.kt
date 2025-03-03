// RUN_PIPELINE_TILL: BACKEND
// OPT_IN: kotlin.contracts.ExperimentalContracts

import kotlin.contracts.*

val Boolean.case_1: () -> Unit
    get() {
        <!CONTRACT_NOT_ALLOWED!>contract<!> {
            returns() implies (this@case_1)
        }
        return {}
    }

val (() -> Unit).case_2: () -> Unit
    get() {
        <!CONTRACT_NOT_ALLOWED!>contract<!> {
            callsInPlace(this@case_2, InvocationKind.EXACTLY_ONCE)
        }
        return {}
    }

var Boolean.case_3: () -> Unit
    get() {
        return {}
    }
    set(value: () -> Unit) {
        <!CONTRACT_NOT_ALLOWED!>contract<!> {
            callsInPlace(value, InvocationKind.EXACTLY_ONCE)
        }
    }

var (() -> Unit).case_4: () -> Unit
    get() {
        return {}
    }
    set(value) {
        <!CONTRACT_NOT_ALLOWED!>contract<!> {
            callsInPlace(this@case_4, InvocationKind.EXACTLY_ONCE)
        }
    }

val Boolean.case_5: () -> Unit
    get() {
        <!CONTRACT_NOT_ALLOWED!>contract<!> {
            returns() implies (this@case_5)
        }

        if (!this@case_5)
            throw Exception()

        return {}
    }

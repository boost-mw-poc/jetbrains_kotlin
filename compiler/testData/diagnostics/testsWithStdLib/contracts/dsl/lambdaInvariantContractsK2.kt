// FIR_IDENTICAL
// RUN_PIPELINE_TILL: BACKEND
// OPT_IN: kotlin.contracts.ExperimentalContracts

import kotlin.contracts.*

inline fun <R> runIf(condition: Boolean, block: () -> R): R? {
    contract { inside(block) implies(condition) }
    return if (condition) { block() } else null
}

fun testRunIf(s: Any) {
    val x = runIf(s is String) {
        // smartcast to String
        s.length
    }
}

// RUN_PIPELINE_TILL: BACKEND
// ISSUE: KT-76585

fun test(cond1: Boolean, cond3: Boolean): String = when {
    cond1 -> {
        val result = if (cond3) {
            "bar"
        } else {
            <!RETURN_IN_FUNCTION_WITH_EXPRESSION_BODY!>return<!> "nothing"
        }

        result
    }
    else -> ""
}

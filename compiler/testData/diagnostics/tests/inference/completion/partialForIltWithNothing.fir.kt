// RUN_PIPELINE_TILL: BACKEND
// DIAGNOSTICS: -UNUSED_VARIABLE

fun test(boolean: Boolean) {
    val expectedLong: Long = if (boolean) {
        if (boolean) {
            42
        } else {
            return
        }
    } else {
        return
    }

    val expectedInt: Int = if (boolean) {
        if (boolean) {
            42
        } else {
            return
        }
    } else {
        return
    }

    val expectedShort: Short = if (boolean) {
        if (boolean) {
            42
        } else {
            return
        }
    } else {
        return
    }

    val expectedByte: Byte = if (boolean) {
        if (boolean) {
            42
        } else {
            return
        }
    } else {
        return
    }
}

/* GENERATED_FIR_TAGS: functionDeclaration, ifExpression, integerLiteral, localProperty, propertyDeclaration */

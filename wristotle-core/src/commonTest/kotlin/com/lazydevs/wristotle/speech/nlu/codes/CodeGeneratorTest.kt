// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.codes

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeGeneratorTest {

    private fun code(format: CodeFormat, data: String) = SavedCode("id", "Label", format, data, 0L)

    @Test fun `QR dispatches to a 2D matrix`() {
        assertTrue(CodeGenerator.matrix(code(CodeFormat.QR_CODE, "https://wristotle.app")) is CodeMatrix.TwoD)
    }

    @Test fun `Code128 dispatches to a 1D matrix`() {
        assertTrue(CodeGenerator.matrix(code(CodeFormat.CODE_128, "12345678")) is CodeMatrix.OneD)
    }

    @Test fun `unencodable Code128 data yields null instead of crashing`() {
        assertNull(CodeGenerator.matrix(code(CodeFormat.CODE_128, "café")))
    }
}

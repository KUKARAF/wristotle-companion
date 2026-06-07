// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthHeaderValidationTest {

    @Test fun `empty value does not warn`() {
        assertTrue(looksLikeFullAuthHeader(""))
        assertTrue(looksLikeFullAuthHeader("   "))
    }

    @Test fun `Bearer prefix is fine`() {
        assertTrue(looksLikeFullAuthHeader("Bearer sk-abc123"))
        assertTrue(looksLikeFullAuthHeader("bearer sk-abc123"))
        assertTrue(looksLikeFullAuthHeader("BEARER sk-abc123"))
    }

    @Test fun `other standard schemes pass`() {
        assertTrue(looksLikeFullAuthHeader("Basic dXNlcjpwYXNz"))
        assertTrue(looksLikeFullAuthHeader("Token abc"))
        assertTrue(looksLikeFullAuthHeader("Digest xyz"))
        assertTrue(looksLikeFullAuthHeader("ApiKey xyz"))
        assertTrue(looksLikeFullAuthHeader("api-key xyz"))
    }

    @Test fun `bare token without scheme warns`() {
        assertFalse(looksLikeFullAuthHeader("sk-abc123"))
        assertFalse(looksLikeFullAuthHeader("ghp_xxx"))
        // base64-ish raw value (slashes + plus signs) without a scheme prefix
        assertFalse(looksLikeFullAuthHeader("AbCd/efGh+iJkLmNoPqRs"))
    }

    @Test fun `scheme word without trailing space and value warns`() {
        // "Bearer" alone (no space, no credentials) isn't a usable header
        assertFalse(looksLikeFullAuthHeader("Bearer"))
    }
}
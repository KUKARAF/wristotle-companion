package com.lazydevs.wristotle.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMatchingTest {

    // --- legit matches clear the floor ---

    @Test fun exactMatchScoresTop() {
        assertEquals(1.0f, contactMatchScore("Mom", "Mom"), 0.001f)
    }

    @Test fun caseInsensitiveExact() {
        assertEquals(1.0f, contactMatchScore("mom", "MOM"), 0.001f)
    }

    @Test fun firstNameTokenMatches() {
        assertAccepted("john", "John Smith")
    }

    @Test fun lastNameTokenMatches() {
        assertAccepted("smith", "John Smith")
    }

    @Test fun namePrefixMatches() {
        assertAccepted("jo", "John Smith")
    }

    @Test fun tokenPrefixMatches() {
        assertAccepted("alex", "Alexandra Jones")
    }

    // --- the bug: mid-word substring of an unrelated name is rejected ---

    @Test fun midWordSubstringRejected() {
        // "al" is inside "Michael" but they're not a real match.
        assertRejected("al", "Michael Johnson")
    }

    @Test fun shortJunkAgainstLongNameRejected() {
        assertRejected("xy", "Alexandra Jones")
    }

    @Test fun bestCandidateWinsOverPoorSubstring() {
        // Given both, the real token match must outrank the mid-word substring.
        val good = contactMatchScore("ann", "Ann Smith")     // token == query
        val poor = contactMatchScore("ann", "Joanna Park")   // mid-word substring
        assertTrue("token match should beat substring", good > poor)
        assertTrue("token match accepted", good >= CONTACT_MATCH_FLOOR)
        assertTrue("substring-only rejected", poor < CONTACT_MATCH_FLOOR)
    }

    // --- edge cases ---

    @Test fun emptyQueryScoresZero() {
        assertEquals(0f, contactMatchScore("", "Mom"), 0.001f)
    }

    @Test fun levenshteinBasics() {
        assertEquals(0, levenshtein("abc", "abc"))
        assertEquals(3, levenshtein("", "abc"))
        assertEquals(1, levenshtein("cat", "car"))
        assertEquals(3, levenshtein("kitten", "sitting"))
    }

    private fun assertAccepted(query: String, name: String) {
        val s = contactMatchScore(query, name)
        assertTrue("'$query' vs '$name' = $s should be >= $CONTACT_MATCH_FLOOR", s >= CONTACT_MATCH_FLOOR)
    }

    private fun assertRejected(query: String, name: String) {
        val s = contactMatchScore(query, name)
        assertTrue("'$query' vs '$name' = $s should be < $CONTACT_MATCH_FLOOR", s < CONTACT_MATCH_FLOOR)
    }
}

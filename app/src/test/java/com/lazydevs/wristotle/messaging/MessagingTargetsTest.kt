package com.lazydevs.wristotle.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingTargetsTest {

    @Test fun `find resolves canonical names`() {
        assertEquals(MessagingTargets.WhatsApp, MessagingTargets.find("whatsapp"))
        assertEquals(MessagingTargets.Telegram, MessagingTargets.find("telegram"))
        assertEquals(MessagingTargets.Signal,   MessagingTargets.find("signal"))
    }

    @Test fun `find is case-insensitive`() {
        assertEquals(MessagingTargets.WhatsApp, MessagingTargets.find("WhatsApp"))
        assertEquals(MessagingTargets.WhatsApp, MessagingTargets.find("WHATSAPP"))
    }

    @Test fun `find trims whitespace`() {
        assertEquals(MessagingTargets.Signal, MessagingTargets.find("  signal  "))
    }

    @Test fun `whisper variants of whatsapp resolve`() {
        // The aliases on WhatsApp include common mistranscriptions; assert
        // the popular ones land correctly so we don't quietly stop accepting
        // them in a future refactor.
        assertEquals(MessagingTargets.WhatsApp, MessagingTargets.find("whats app"))
        assertEquals(MessagingTargets.WhatsApp, MessagingTargets.find("whatapp"))
        assertEquals(MessagingTargets.WhatsApp, MessagingTargets.find("watsapp"))
    }

    @Test fun `find returns null for unknown app`() {
        assertNull(MessagingTargets.find("skype"))
        assertNull(MessagingTargets.find(""))
    }

    // Intent internals (action / data / extras) need Robolectric — the
    // JVM unit-test stubs leave them null with isReturnDefaultValues=true.
    // The actual deep-link shapes were validated by the Phase 0 on-device
    // smoke test (see project_watch_launch_bal_wall memory). Add Roboletric-
    // backed tests later if regressions show up.

    @Test fun `named targets list has expected size and packages`() {
        // NAMED = only the speakable apps; SMS is excluded here because
        // the slot extractor picks it as the default, not by name.
        val packages = MessagingTargets.NAMED.map { it.packageId }.toSet()
        assertEquals(3, MessagingTargets.NAMED.size)
        assertTrue("com.whatsapp" in packages)
        assertTrue("org.telegram.messenger" in packages)
        assertTrue("org.thoughtcrime.securesms" in packages)
    }

    @Test fun `ALL includes SMS as the default target`() {
        // Phase A2 added SMS to the registry. It's reachable via
        // findByDisplayName (the slot extractor emits app="SMS") and
        // via the Sms property directly, NOT via find() which walks
        // NAMED only.
        assertEquals(4, MessagingTargets.ALL.size)
        assertTrue(MessagingTargets.Sms in MessagingTargets.ALL)
        assertNull("find() must not return SMS — no spoken aliases", MessagingTargets.find("sms"))
    }

    @Test fun `findByDisplayName resolves every registered target`() {
        assertEquals(MessagingTargets.Sms,      MessagingTargets.findByDisplayName("SMS"))
        assertEquals(MessagingTargets.WhatsApp, MessagingTargets.findByDisplayName("WhatsApp"))
        assertEquals(MessagingTargets.Telegram, MessagingTargets.findByDisplayName("Telegram"))
        assertEquals(MessagingTargets.Signal,   MessagingTargets.findByDisplayName("Signal"))
    }

    @Test fun `findByDisplayName is case-insensitive`() {
        assertEquals(MessagingTargets.WhatsApp, MessagingTargets.findByDisplayName("whatsapp"))
        assertEquals(MessagingTargets.Sms,      MessagingTargets.findByDisplayName("sms"))
    }

    // ── Enabled flag (third-party apps gated until auto-send lands) ───────

    @Test fun `SMS is the only enabled target today`() {
        assertTrue("SMS must stay enabled — it has a real programmatic API",
            MessagingTargets.Sms.enabled)
    }

    @Test fun `WhatsApp Telegram and Signal are disabled`() {
        // Deep-link path opens compose but doesn't send. Flip these to
        // true once an AccessibilityService drives the Send button.
        assertFalse(
            "WhatsApp should be disabled until auto-send is implemented",
            MessagingTargets.WhatsApp.enabled,
        )
        assertFalse(
            "Telegram should be disabled until auto-send is implemented",
            MessagingTargets.Telegram.enabled,
        )
        assertFalse(
            "Signal should be disabled until auto-send is implemented",
            MessagingTargets.Signal.enabled,
        )
    }
}

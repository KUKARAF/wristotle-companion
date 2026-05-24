package com.lazydevs.wristotle.messaging

import org.junit.Assert.assertEquals
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

    @Test fun `all targets list has expected size and packages`() {
        val packages = MessagingTargets.ALL.map { it.packageId }.toSet()
        assertEquals(3, MessagingTargets.ALL.size)
        assertTrue("com.whatsapp" in packages)
        assertTrue("org.telegram.messenger" in packages)
        assertTrue("org.thoughtcrime.securesms" in packages)
    }
}

package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.access.AppAccessPolicy
import com.wangzan101.mika.core.access.AppAccessState
import com.wangzan101.mika.core.access.DEFAULT_HARD_LOCKED
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.knowledge.Link
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.AskPort
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.LinkPolicy
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.SystemAction
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private class IntentTestScreen(
    private val policy: AppAccessPolicy = AppAccessPolicy.ALLOW,
    private val inner: FakeScreen = FakeScreen(),
) : ScreenPort by inner {
    val opened = mutableListOf<Triple<String?, String?, String?>>()
    override val appAccess: AppAccessPolicy get() = policy
    override suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult {
        opened += Triple(action, uri, packageName)
        return ActionResult.Ok()
    }
    private fun snap() = ScreenSnapshot(
        "l1",
        "com.example.target",
        listOf(UiElement("@e1", "text", "Target", 10, 10, listOf(0, 0, 100, 50), null, false, false, false)),
        1080,
        2400,
    )
    override suspend fun snapshot() = snap()
    override suspend fun peek() = snap()
}

class VerifiedIntentRouterTest {
    private fun openLinkCall(uri: String? = null, action: String? = null, pkg: String? = null): Part.ToolCall {
        val parts = buildList {
            uri?.let { add(""""uri":"$it"""") }
            action?.let { add(""""action":"$it"""") }
            pkg?.let { add(""""package":"$it"""") }
        }
        return Part.ToolCall("c1", "phone_open_link", "{${parts.joinToString(",")}}")
    }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.bool(key: String) = this[key]!!.jsonPrimitive.boolean

    @Test
    fun geoAndMailtoOpenDirectlyWithoutKnowledgeNotes() = runTest {
        val screen = IntentTestScreen()
        val dispatcher = toolDispatcher(screen, deepLinks = true)

        val geoUri = VerifiedIntentRouter.buildGeoUri("Coffee Shop, Tokyo")
        val geoRes = dispatcher.execute(openLinkCall(uri = geoUri))
        assertTrue(geoRes.content.toString(), geoRes.content.bool("ok"))

        val mailtoUri = VerifiedIntentRouter.buildMailtoUri(
            to = "support@example.com",
            subject = "Order status",
            body = "Line 1\nLine 2",
        )
        val mailRes = dispatcher.execute(openLinkCall(uri = mailtoUri))
        assertTrue(mailRes.content.toString(), mailRes.content.bool("ok"))

        assertEquals(
            listOf(
                Triple(null, geoUri, null),
                Triple(null, mailtoUri, null),
            ),
            screen.opened,
        )
        assertTrue(dispatcher.linkAllowed(null, geoUri, null))
        assertTrue(dispatcher.linkAllowed(null, mailtoUri, null))
    }

    @Test
    fun malformedGeoAndMailtoFailCleanly() = runTest {
        val screen = IntentTestScreen()
        val dispatcher = toolDispatcher(screen, deepLinks = true)

        for (bad in listOf(
            "geo:",
            "geo:0,0?q=",
            "geo:999,0",
            "geo:abc,def",
            "mailto:",
            "mailto:not-an-email",
            "mailto:@example.com",
            "mailto:user@",
        )) {
            assertFalse(bad, VerifiedIntentRouter.isDirectSafeIntent(bad))
            val res = dispatcher.execute(openLinkCall(uri = bad))
            assertFalse(bad, res.content.bool("ok"))
            assertEquals(bad, "invalid_link", res.content.str("error"))
        }
        assertTrue(screen.opened.isEmpty())
    }

    @Test
    fun communicationLinksRequireConfirmationAndProceedOnlyOnAllow() = runTest {
        for (uri in listOf("tel:10086", "sms:10086", "smsto:10086", "mms:10086", "sip:alice@example.com")) {
            assertTrue(uri, VerifiedIntentRouter.requiresConfirmation(uri))

            // Unattended mode (ctx.ask == null) fails cleanly without opening.
            val unattendedScreen = IntentTestScreen()
            val unattended = toolDispatcher(unattendedScreen, deepLinks = true)
            val unattendedRes = unattended.execute(openLinkCall(uri = uri))
            assertFalse(uri, unattendedRes.content.bool("ok"))
            assertEquals(uri, "confirmation_required", unattendedRes.content.str("error"))
            assertTrue(unattendedScreen.opened.isEmpty())

            // Cancel fails cleanly without opening.
            val cancelScreen = IntentTestScreen()
            val cancelDispatcher = toolDispatcher(cancelScreen, deepLinks = true)
            cancelDispatcher.ctx.ask = object : AskPort {
                override suspend fun awaitAnswer(prompt: String, options: List<String>?): String = "Cancel"
            }
            val cancelRes = cancelDispatcher.execute(openLinkCall(uri = uri))
            assertFalse(uri, cancelRes.content.bool("ok"))
            assertEquals(uri, "confirmation_required", cancelRes.content.str("error"))
            assertTrue(cancelScreen.opened.isEmpty())

            // Allow proceeds and records prompt + options.
            val allowScreen = IntentTestScreen()
            val allowDispatcher = toolDispatcher(allowScreen, deepLinks = true)
            var seenPrompt: String? = null
            var seenOptions: List<String>? = null
            allowDispatcher.ctx.ask = object : AskPort {
                override suspend fun awaitAnswer(prompt: String, options: List<String>?): String {
                    seenPrompt = prompt
                    seenOptions = options
                    return "Allow"
                }
            }
            val allowRes = allowDispatcher.execute(openLinkCall(uri = uri))
            assertTrue(uri, allowRes.content.bool("ok"))
            assertTrue(seenPrompt!!.contains(uri))
            assertEquals(listOf("Allow", "Cancel"), seenOptions)
            assertEquals(listOf(Triple(null, uri, null)), allowScreen.opened)
        }
    }

    @Test
    fun blockedSchemesAndUnlistedLinksStayDeniedWithInvalidLink() = runTest {
        val screen = IntentTestScreen()
        val dispatcher = toolDispatcher(screen, deepLinks = true)

        for (blocked in listOf(
            "intent://x#Intent;end",
            "file:///sdcard/a",
            "content://media/1",
            "javascript:alert(1)",
            "data:text/html,hello",
            "about:blank",
            "blob:https://example.com/abc",
        )) {
            val decision = VerifiedIntentRouter.classify(null, blocked, "com.example.app", listedInKnowledge = true, AppAccessState())
            assertTrue(blocked, decision is IntentRouteDecision.Denied)
            assertEquals(blocked, "invalid_link", (decision as IntentRouteDecision.Denied).code)

            val res = dispatcher.execute(openLinkCall(uri = blocked, pkg = "com.example.app"))
            assertEquals(blocked, "invalid_link", res.content.str("error"))
        }

        // Unlisted custom scheme & unlisted https without Knowledge listing keep OpenLinkTest behavior.
        val unlistedCustom = dispatcher.execute(openLinkCall(uri = "someapp://other/2"))
        assertEquals("invalid_link", unlistedCustom.content.str("error"))
        assertTrue(unlistedCustom.content.str("message").contains("reference notes"))
        assertFalse(dispatcher.linkAllowed(null, "someapp://other/2", null))

        val unlistedHttps = dispatcher.execute(openLinkCall(uri = "https://example.com/unlisted", pkg = "com.example.app"))
        assertEquals("invalid_link", unlistedHttps.content.str("error"))
        assertTrue(unlistedHttps.content.str("message").contains("reference notes"))
        assertFalse(dispatcher.linkAllowed(null, "https://example.com/unlisted", "com.example.app"))

        // Listed link and settings action still succeed cleanly.
        dispatcher.allowLinks(listOf(Link("Page", "https://example.com/a", "com.example.app")))
        assertTrue(dispatcher.linkAllowed(null, "https://example.com/a", "com.example.app"))
        assertTrue(dispatcher.execute(openLinkCall(uri = "https://example.com/a", pkg = "com.example.app")).content.bool("ok"))
        assertTrue(dispatcher.execute(openLinkCall(action = LinkPolicy.SETTINGS_ACTIONS.first())).content.bool("ok"))
    }

    @Test
    fun appAccessBlocklistAndHardLockDenialsAreEnforcedBeforePromptOrLaunch() = runTest {
        val access = AppAccessState(
            blocked = setOf("com.blocked.dialer"),
            hardLocked = DEFAULT_HARD_LOCKED,
        )
        assertEquals("app_blocked", VerifiedIntentRouter.checkAppAccess("com.blocked.dialer", access)?.code)
        assertEquals("app_blocked", VerifiedIntentRouter.checkAppAccess("io.metamask", access)?.code)
        assertNull(VerifiedIntentRouter.checkAppAccess("com.google.android.apps.maps", access))

        // Hard-locked package on geo: is denied before opening.
        val geoDecision = VerifiedIntentRouter.classify(null, "geo:0,0?q=cafe", "io.metamask", listedInKnowledge = false, access)
        assertTrue(geoDecision is IntentRouteDecision.Denied)
        assertEquals("app_blocked", (geoDecision as IntentRouteDecision.Denied).code)

        // Blocked package on tel: is denied BEFORE prompting ctx.ask.
        val screen = IntentTestScreen(policy = AppAccessPolicy { access })
        val dispatcher = toolDispatcher(screen, deepLinks = true)
        var prompted = false
        dispatcher.ctx.ask = object : AskPort {
            override suspend fun awaitAnswer(prompt: String, options: List<String>?): String {
                prompted = true
                return "Allow"
            }
        }
        val res = dispatcher.execute(openLinkCall(uri = "tel:10086", pkg = "com.blocked.dialer"))
        assertFalse(res.content.bool("ok"))
        assertEquals("app_blocked", res.content.str("error"))
        assertFalse(prompted)
        assertTrue(screen.opened.isEmpty())

        // Unavailable AppAccessState denies even without packageName.
        val unavailableScreen = IntentTestScreen(policy = AppAccessPolicy { AppAccessState(available = false) })
        val unavailableDispatcher = toolDispatcher(unavailableScreen, deepLinks = true)
        val unavailableRes = unavailableDispatcher.execute(openLinkCall(uri = "geo:0,0"))
        assertEquals("app_access_unavailable", unavailableRes.content.str("error"))
        assertTrue(unavailableScreen.opened.isEmpty())
    }

    @Test
    fun uriBuildersAndCalendarValidationEnforceEncodingAndConstraints() {
        val geo = VerifiedIntentRouter.buildGeoUri("1600 Amphitheatre Pkwy, Mountain View")
        assertTrue(geo.startsWith("geo:0,0?q="))
        assertFalse(geo.contains(" "))
        assertNull(LinkPolicy.check(null, geo, null))
        assertTrue(VerifiedIntentRouter.isDirectSafeIntent(geo))

        val coordsGeo = VerifiedIntentRouter.buildGeoUri(35.6812, 139.7671, "Tokyo Station")
        assertEquals("geo:35.6812,139.7671?q=Tokyo%20Station", coordsGeo)
        assertTrue(VerifiedIntentRouter.isDirectSafeIntent(coordsGeo))

        val mailto = VerifiedIntentRouter.buildMailtoUri("a@b.com", subject = "Hi there", body = "Line 1\nLine 2")
        assertEquals("mailto:a@b.com?subject=Hi%20there&body=Line%201%0ALine%202", mailto)
        assertNull(LinkPolicy.check(null, mailto, null))
        assertTrue(VerifiedIntentRouter.isDirectSafeIntent(mailto))

        val (httpsUrl, httpsPkg) = VerifiedIntentRouter.buildHttpsUri("https://example.com/path", "com.example.browser")
        assertEquals("https://example.com/path", httpsUrl)
        assertEquals("com.example.browser", httpsPkg)
        assertTrue(VerifiedIntentRouter.isAllowedSettingsAction("android.settings.WIFI_SETTINGS"))
        assertFalse(VerifiedIntentRouter.isAllowedSettingsAction("android.intent.action.VIEW"))

        assertThrows(IllegalArgumentException::class.java) { VerifiedIntentRouter.buildGeoUri("   ") }
        assertThrows(IllegalArgumentException::class.java) { VerifiedIntentRouter.buildGeoUri(91.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { VerifiedIntentRouter.buildMailtoUri("not-an-email") }
        assertThrows(IllegalArgumentException::class.java) { VerifiedIntentRouter.buildMailtoUri("a@b.com", subject = "bad\nsubject") }
        assertThrows(IllegalArgumentException::class.java) { VerifiedIntentRouter.buildHttpsUri("http://example.com", "com.example.app") }

        assertNull(VerifiedIntentRouter.validateCalendarEvent(SystemAction.AddCalendarEvent("Standup", 1_000L, 2_000L)))
        assertNotNull(VerifiedIntentRouter.validateCalendarEvent(SystemAction.AddCalendarEvent("   ", 1_000L)))
        assertNotNull(VerifiedIntentRouter.validateCalendarEvent(SystemAction.AddCalendarEvent("Bad", 2_000L, 1_000L)))
    }
}

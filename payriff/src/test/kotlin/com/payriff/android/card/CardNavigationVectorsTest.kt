package com.payriff.android.card

import com.payriff.android.internal.card.NextAction
import com.payriff.android.internal.card.ThreeDsNav
import com.payriff.android.internal.card.ThreeDsNavigator
import com.payriff.android.testing.Vectors
import com.payriff.android.testing.Vectors.objects
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `card_next_action.json` and `card_3ds_navigation.json` (CONTRACT §16.7–§16.8): every case and step. */
class CardNavigationVectorsTest {

    @Test
    fun everyNextActionCase() {
        val cases = Vectors.cases("card_next_action.json")
        assertEquals(15, cases.size)
        for (case in cases) {
            val name = case.getString("name")
            val expected = case.getJSONObject("expected")
            val action = NextAction.parse(case.getJSONObject("payload"))
            when (expected.getString("type")) {
                "challenge" -> {
                    assertTrue(name, action is NextAction.Challenge)
                    action as NextAction.Challenge
                    assertEquals(name, expected.getString("url"), action.url)
                    val form = expected.getJSONArray("form")
                    val pairs = (0 until form.length()).map { i -> form.getJSONArray(i).let { it.getString(0) to it.getString(1) } }
                    assertEquals(name, pairs, action.form)
                }
                "method" -> {
                    assertTrue(name, action is NextAction.Method)
                    action as NextAction.Method
                    assertEquals(name, expected.opt("methodUrl").asString(), action.methodUrl)
                    assertEquals(name, expected.opt("methodData").asString(), action.methodData)
                    assertEquals(name, expected.opt("methodUrlServer").asString(), action.methodUrlServer)
                }
                "redirect" -> {
                    assertTrue(name, action is NextAction.Redirect)
                    assertEquals(name, expected.getString("url"), (action as NextAction.Redirect).url)
                }
                "wait" -> assertTrue(name, action === NextAction.Wait)
                "checkStatus" -> assertTrue(name, action === NextAction.CheckStatus)
                "invalid" -> assertTrue(name, action === NextAction.Invalid)
                else -> error("unknown type in $name")
            }
        }
        assertTrue(NextAction.parse(null) === NextAction.Invalid)
        assertTrue(NextAction.parse(JSONObject().put("nextAction", JSONArray())) === NextAction.Invalid)
    }

    @Test
    fun nextActionsNeverPrintBankData() {
        val challenge = NextAction.parse(
            JSONObject("""{"nextAction":"THREE_DS","accessUrl":"https://acs.bank.example/c?token=QUERY_SECRET","clientRequest":[{"key":"creq","value":"CREQ_SECRET"}]}"""),
        )
        val method = NextAction.parse(
            JSONObject("""{"nextAction":"THREE_DS","clientRequest":[{"key":"threeDSMethodUrl","value":"https://acs.bank.example/m"},{"key":"threeDSMethodData","value":"METHOD_SECRET"}]}"""),
        )
        for (text in listOf(challenge.toString(), method.toString())) {
            assertTrue(text, "CREQ_SECRET" !in text && "METHOD_SECRET" !in text && "QUERY_SECRET" !in text)
        }
    }

    @Test
    fun everyThreeDsNavigationStep() {
        val cases = Vectors.cases("card_3ds_navigation.json")
        assertEquals(14, cases.size)
        for (case in cases) {
            val navigator = ThreeDsNavigator(case.getString("apiBase"))
            for (step in case.getJSONArray("steps").objects()) {
                val actual = navigator.classify(step.getString("url"), step.getBoolean("isMainFrame"))
                val expected = when (step.getString("expected")) {
                    "allow" -> ThreeDsNav.ALLOW
                    "allowCallback" -> ThreeDsNav.ALLOW_CALLBACK
                    "finish" -> ThreeDsNav.FINISH
                    "external" -> ThreeDsNav.EXTERNAL
                    "block" -> ThreeDsNav.BLOCK
                    else -> error("unknown action")
                }
                assertEquals("${case.getString("name")}: ${step.getString("url")}", expected, actual)
            }
        }
    }

    @Test
    fun aCallbackSeenOnTheNetworkThreadArmsTheFlow() {
        // A POST to the callback never reaches shouldOverrideUrlLoading; shouldInterceptRequest marks it instead.
        val navigator = ThreeDsNavigator("https://api.payriff.com")
        assertTrue(navigator.isCallback("https://api.payriff.com/api/v3/gateway/atb/callback"))
        navigator.markCallback()
        assertEquals(ThreeDsNav.FINISH, navigator.classify("https://merchant.example/", isMainFrame = true))
        val sandbox = ThreeDsNavigator("https://sb-api.payriff.com")
        assertEquals(false, sandbox.isCallback("https://api.payriff.com/api/v3/gateway/atb/callback"))
        assertEquals(false, sandbox.isCallback("https://sb-api.payriff.com:8443/api/v3/gateway/atb/callback"))
        assertEquals(false, sandbox.isCallback("https://user@evil.example/api/v3/gateway/atb/callback"))
        assertEquals(true, sandbox.isCallback("https://evil.example@sb-api.payriff.com/api/v3/gateway/atb/callback"))
    }

    private fun Any?.asString(): String? = if (this == null || this == JSONObject.NULL) null else this as String

    @Test
    fun asStringHelper() = assertNull(JSONObject.NULL.asString())
}

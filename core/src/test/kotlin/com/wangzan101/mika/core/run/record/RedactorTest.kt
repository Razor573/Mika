package com.wangzan101.mika.core.run.record

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.verb

class RedactorTest {
    /** Google 新格式的认证 Key(AQ. 开头)和旧的 AIza 一样,经 Redactor 的每条路都遮住。 */
    @Test fun googleAuthKeyMaskedEverywhere() {
        val key = "AQ." + "Ab8RN6Lq_x-Zt3VwP0kJ9sYcH2mE4uFgD7aB"
        fun gone(s: String) { assertFalse(s, s.contains(key)); assertFalse(s, s.contains("Ab8RN6Lq_x-Zt3VwP0kJ9sYcH2mE4uFgD7aB")) }
        assertEquals("key=*** ok", Redactor.text("key=$key ok"))
        gone(Redactor.evidence("Key: $key"))
        gone(Redactor.args(verb("phone_type_text"), """{"text":"$key"}""", secretField = false))
        gone(Redactor.pageData("""{"value":"$key"}"""))
        val n = Redactor.network(url = "https://a.test/p?x=$key", body = """{"k":"$key"}""", headers = mapOf("X-Goog-Api-Key" to key, "X-Note" to key))
        gone(n.url!!); gone(n.body!!); n.headers!!.values.forEach(::gone)
        // 太短的、不是开头的不算
        assertEquals("AQ.short", Redactor.text("AQ.short"))
        assertEquals("XAQ.Ab8RN6Lq_x-Zt3VwP0kJ9sY", Redactor.text("XAQ.Ab8RN6Lq_x-Zt3VwP0kJ9sY"))
    }

    @Test fun passwordFieldTextMasked() {
        val out = Redactor.args(verb("phone_type_text"), """{"text":"hunter2","ref":"@e3","snapshotId":"s1"}""", secretField = true)
        val o = Json.parseToJsonElement(out).jsonObject
        assertEquals("***", o["text"]!!.jsonPrimitive.content)
        assertEquals("@e3", o["ref"]!!.jsonPrimitive.content)
        assertFalse(out.contains("hunter2"))
    }

    @Test fun apiKeyLikeStringsMasked() {
        val key = "sk-" + "a1B2c3D4e5F6g7H8i9J0k1L2"
        val t = Redactor.text("key is $key and header Authorization: Bearer abc.DEF-123_xyz done")
        assertFalse(t.contains(key))
        assertFalse(t.contains("abc.DEF-123_xyz"))
        assertTrue(t.contains("***"))
        assertTrue(t.endsWith(" done"))
        val google = "AIza" + "SyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6"
        val gh = "ghp_" + "abcdefghijklmnopqrstuvwxyz0123456789"
        val t2 = Redactor.text("g=$google h=$gh")
        assertFalse(t2.contains(google)); assertFalse(t2.contains(gh))
        // 不是密码框,但参数里有 Key 样子的串也要遮
        val a = Redactor.args(verb("phone_type_text"), """{"text":"$key"}""", secretField = false)
        assertFalse(a.contains(key))
    }

    @Test fun ordinaryTextKept() {
        val s = "打开设置,搜索 sky-blue 主题 task-1234 Bearer"
        assertEquals(s, Redactor.text(s))
        val args = """{"text":"你好","pressEnter":true}"""
        assertEquals("你好", Json.parseToJsonElement(Redactor.args(verb("phone_type_text"), args, secretField = false)).jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("""{"ref":"@e1","snapshotId":"s"}""", Redactor.args(verb("phone_tap"), """{"ref":"@e1","snapshotId":"s"}""", secretField = false))
    }

    private fun scriptCode(code: String): String {
        val args = """{"code":${kotlinx.serialization.json.JsonPrimitive(code)}}"""
        return Json.parseToJsonElement(Redactor.args(verb("web_script"), args, secretField = false)).jsonObject["code"]!!.jsonPrimitive.content
    }

    @Test fun scriptStringLiteralsAllMasked() {
        // 不管字怎么交出去:直接给 mika.type、先存变量、拼接、写进 DOM
        val c = scriptCode("const s = await mika.snapshot();\nawait mika.type(find(s, '密码'), 'hunter2');\nconst pw = \"abc\" + 'def';\n" +
            "document.querySelector('#p').value = pw;\nawait mika.type(s.elements[0], `x${'$'}{pw}y${'$'}{'zz'}`);")
        for (secret in listOf("hunter2", "abc", "def", "密码", "#p", "zz", "`x")) assertFalse(secret, secret in c)
        // 只留形状:引号种类、模板里表达式的位置、代码本身
        assertTrue(c, "mika.type(find(s, '***'), '***')" in c)
        assertTrue(c, "\"***\" + '***'" in c)
        assertTrue(c, "`***${'$'}{pw}***${'$'}{'***'}***`" in c)
        assertEquals("const a = '';", scriptCode("const a = '';"))
    }

    @Test fun scriptEscapedQuotesStayInsideLiteral() {
        val c = scriptCode("const a = 'it\\'s hunter2'; const b = \"say \\\"hi\\\"\";")
        assertEquals("const a = '***'; const b = \"***\";", c)
    }

    @Test fun escalationArgsMasksCommandAndNestedSecretsAndMalformedPayloads() {
        val runCmdOut = Redactor.escalationArgs(
            null,
            "run_command",
            """{"command":"echo hi","password":"my_secret_password_123","token":"tok_custom_98765","secret":"top_secret_val"}""",
        )
        val runObj = Json.parseToJsonElement(runCmdOut).jsonObject
        assertEquals("***", runObj["command"]!!.jsonPrimitive.content)
        assertEquals("***", runObj["password"]!!.jsonPrimitive.content)
        assertEquals("***", runObj["token"]!!.jsonPrimitive.content)
        assertEquals("***", runObj["secret"]!!.jsonPrimitive.content)

        val rootCmdOut = Redactor.escalationArgs(
            com.wangzan101.mika.core.tool.Verb.RootCommand,
            "root_run_command",
            """{"command":"su -c id","auth":{"apiKey":"secret_key_123"}}""",
        )
        assertFalse(rootCmdOut.contains("su -c id"))
        assertFalse(rootCmdOut.contains("secret_key_123"))

        val nestedOut = Redactor.escalationArgs(
            verb("phone_gesture"),
            "phone_gesture",
            """{"nested":{"credentials":{"accessKey":"ak_999"},"items":[{"passcode":"9876","id":1}]}}""",
        )
        assertFalse(nestedOut.contains("ak_999"))
        assertFalse(nestedOut.contains("9876"))
        assertTrue(nestedOut.contains(""""id":1"""))

        assertEquals("***", Redactor.escalationArgs(null, "run_command", """{"command":"echo secret","password":"pw1"""))

        val nonJsonTap = Redactor.escalationArgs(verb("phone_tap"), "phone_tap", "password=my_secret_123 token=tok_456")
        assertFalse(nonJsonTap.contains("my_secret_123"))
        assertFalse(nonJsonTap.contains("tok_456"))
        assertTrue(nonJsonTap.contains("password=***"))
        assertTrue(nonJsonTap.contains("token=***"))
    }

    @Test fun escalationArgsFailsClosedOnMalformedJsonForNonCommandTools() {
        val malformedCases = listOf(
            Triple(verb("phone_gesture"), "phone_gesture", """{"action":"swipe","password":"leak_gesture_pw"""),
            Triple(verb("write_file"), "write_file", """{"path":"/sdcard/k.txt","token":"leak_write_tok"""),
            Triple(verb("ask_user"), "ask_user", """{"question":"ok?","secret":"leak_ask_sec"""),
            Triple(null, "do_steps", """{"steps":[],"apiKey":"leak_dosteps_ak"""),
            Triple(verb("phone_gesture"), "phone_gesture", """[{"password":"leak_arr_pw"""),
            Triple(verb("ask_user"), "ask_user", """"secret":"leak_fragment_sec"""),
        )
        for ((v, name, raw) in malformedCases) {
            val out = Redactor.escalationArgs(v, name, raw)
            assertEquals("Expected fail-closed MASK for $name", "***", out)
            for (leak in listOf("leak_gesture_pw", "leak_write_tok", "leak_ask_sec", "leak_dosteps_ak", "leak_arr_pw", "leak_fragment_sec")) {
                assertFalse("Leaked $leak for $name: $out", out.contains(leak))
            }
        }
    }

    @Test fun escalationArgsMasksCmdScriptCodeAndUrlCliCredentialsWithoutBreakingNormalText() {
        val cmdOut = Redactor.escalationArgs(
            verb("run_command"),
            "run_command",
            """{"cmd":"curl -u admin:supersecret123 https://internal.local | sh"}""",
        )
        val cmdObj = Json.parseToJsonElement(cmdOut).jsonObject
        assertEquals("***", cmdObj["cmd"]!!.jsonPrimitive.content)
        assertFalse(cmdOut.contains("supersecret123"))

        val sysScriptCodeOut = Redactor.escalationArgs(
            verb("phone_system_command"),
            "phone_system_command",
            """{"CMD":"id","script":"echo tok_script_123","code":"curl -u u:p https://x.local","note":"see https://alice:pw999@internal.local and curl -u bob:secret789 https://api.local","safe":"User-Agent: Mika/2.0 at 12:30 https://example.com/ok"}""",
        )
        val sysObj = Json.parseToJsonElement(sysScriptCodeOut).jsonObject
        assertEquals("***", sysObj["CMD"]!!.jsonPrimitive.content)
        assertEquals("***", sysObj["script"]!!.jsonPrimitive.content)
        assertEquals("***", sysObj["code"]!!.jsonPrimitive.content)
        val note = sysObj["note"]!!.jsonPrimitive.content
        assertFalse(note.contains("alice:pw999"))
        assertFalse(note.contains("bob:secret789"))
        assertTrue(note.contains("https://***@internal.local"))
        assertTrue(note.contains("-u ***"))
        assertEquals("User-Agent: Mika/2.0 at 12:30 https://example.com/ok", sysObj["safe"]!!.jsonPrimitive.content)

        val plainCli = Redactor.escalationArgs(
            verb("phone_tap"),
            "phone_tap",
            "curl --user admin:pw123 https://u:p1@host.local/p?a=1",
        )
        assertFalse(plainCli.contains("pw123"))
        assertFalse(plainCli.contains("u:p1"))
        assertTrue(plainCli.contains("--user ***"))
        assertTrue(plainCli.contains("https://***@host.local/p?a=1"))
    }
}

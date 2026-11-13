package com.wangzan101.mika.personal

import android.content.Context
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.personal.PersonalMessage
import com.wangzan101.mika.core.personal.PersonalTask
import com.wangzan101.mika.core.personal.PersonalSystemException
import com.wangzan101.mika.i18n.ResourceTexts
import com.wangzan101.mika.i18n.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class PersonalSystemTextTest {
    private inline fun <T> withLocale(context: Context, block: () -> T): T {
        val prev = UiText.source
        UiText.source = ResourceTexts { context.resources }
        return try {
            block()
        } finally {
            UiText.source = prev
        }
    }

    @Test fun stopErrorsTranslateKnownFailuresAndPreserveOtherMessages() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        fun context(locale: Locale) = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) })
        val english = context(Locale.ENGLISH)
        val chinese = context(Locale.SIMPLIFIED_CHINESE)
        val known = PersonalSystemException("stop_not_ready", "任务还未就绪或已经结束，请查看任务详情。")
        val enKnown = withLocale(english) { personalStopErrorText(english, known) }
        val zhKnown = withLocale(chinese) { personalStopErrorText(chinese, known) }
        assertNotEquals(enKnown, zhKnown)
        assertEquals(known.message, zhKnown)
        assertNotEquals(
            withLocale(english) { personalStopErrorText(english, Exception()) },
            withLocale(chinese) { personalStopErrorText(chinese, Exception()) },
        )
        assertEquals("external error", withLocale(english) { personalStopErrorText(english, Exception("external error")) })
    }

    @Test fun taskAmendmentLocalizesOnlyTheSystemPrefix() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        fun context(locale: Locale) = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) })
        val english = context(Locale.ENGLISH)
        val chinese = context(Locale.SIMPLIFIED_CHINESE)
        val task = PersonalTask("task", "conversation", "title", "goal", "source", 0,
            detail = "Additional instruction: 补充：用户原文", detailSystemType = "task_amendment")
        assertEquals("Additional instruction: 补充：用户原文", withLocale(english) { task.displayDetail(english) })
        assertEquals("补充：补充：用户原文", withLocale(chinese) { task.displayDetail(chinese) })
        val legacy = task.copy(detail = "补充：补充：用户原文")
        assertEquals("Additional instruction: 补充：用户原文", withLocale(english) { legacy.displayDetail(english) })
        assertEquals("补充：补充：用户原文", withLocale(chinese) { legacy.displayDetail(chinese) })
        assertEquals(task.detail, withLocale(english) { task.copy(detailSystemType = null).displayDetail(english) })
        assertEquals("外部内容", withLocale(english) { task.copy(detail = "外部内容", detailSystemType = null).displayDetail(english) })
    }

    @Test fun systemMessagesFollowLanguageWithoutChangingUnmarkedText() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        fun context(locale: Locale) = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) })
        val english = context(Locale.ENGLISH)
        val chinese = context(Locale.SIMPLIFIED_CHINESE)
        val text = "这次回复超时了。已有任务不会因此重做，你可以继续发消息。"
        val message = PersonalMessage("id", "assistant", text, 0, systemType = "timeout")
        assertNotEquals(
            withLocale(english) { message.displayText(english) },
            withLocale(chinese) { message.displayText(chinese) },
        )
        assertEquals(text, withLocale(chinese) { message.displayText(chinese) })
        assertEquals(text, withLocale(english) { message.copy(systemType = null).displayText(english) })
        assertEquals(text, withLocale(english) { message.copy(systemType = "future_type").displayText(english) })
        assertEquals(text, message.text)

        val task = PersonalTask("task", "conversation", "title", "goal", "source", 0,
            detail = "等待开始", result = "真实结果", detailSystemType = "waiting")
        assertNotEquals(
            withLocale(english) { task.displayDetail(english) },
            withLocale(chinese) { task.displayDetail(chinese) },
        )
        assertEquals("真实结果", withLocale(english) { task.displayResult(english) })
        val cancelled = task.copy(result = "已取消", resultSystemType = "task_cancelled")
        assertNotEquals(
            withLocale(english) { cancelled.displayResult(english) },
            withLocale(chinese) { cancelled.displayResult(chinese) },
        )
        assertEquals("已取消", withLocale(english) { cancelled.copy(resultSystemType = null).displayResult(english) })
        assertEquals("等待开始", task.detail)
    }
}

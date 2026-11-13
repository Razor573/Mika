package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.SubPage
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 许可证页上第三方组件的数据;与仓库 licenses/THIRD_PARTY_NOTICES.md 的条目一一对应(LicensesTest 核对)。 */
object Licenses {
    data class Entry(val name: String, val license: String, val note: String)

    const val GPL_SOURCE_OFFER =
        "PRoot (GPL-2.0-or-later) and talloc (LGPL-3.0-or-later) are distributed alongside the Mika installation package as standalone binaries; Mika does not link against them. " +
            "Their complete source code, patches applied by Mika, and build scripts are included on each Mika release page as mika-<version>-gpl-sources.tar.gz; " +
            "the source URLs, checksums, patches, and build scripts can also be found in tools/sandbox/ in the source repository if you wish to rebuild them yourself."

    const val RUNTIME_NOTE =
        "The system environment used to run Python, Node, and other programs is packaged with Mika and based on Alpine Linux 3.22; each package and its licence are listed below. " +
            "Source code for GPL, LGPL, and MPL packages (Alpine build recipes and upstream sources) is likewise included in mika-<version>-gpl-sources.tar.gz."

    /** 自带运行环境里的软件包(assets/runtime/packages.json,由 tools/sandbox/build-runtime.py 生成)。 */
    fun runtimePackages(json: String): List<Entry> =
        Json.parseToJsonElement(json).jsonObject.getValue("packages").jsonArray.map { e ->
            val o = e.jsonObject
            fun f(k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
            Entry(f("name"), f("license"), "Version ${f("version")}. ${f("url")}")
        }

    val ENTRIES = listOf(
        Entry("Mika", "Apache-2.0", "Mika itself, including the runtime launcher, the mika command inside it, and skill scripts. The full licence text appears at the top of this page."),
        Entry("PRoot", "GPL-2.0-or-later", "Environment used to run Python, Node, and other programs, version 5.4.0, with one patch (terminate all child processes when PRoot exits). $GPL_SOURCE_OFFER"),
        Entry("talloc", "LGPL-3.0-or-later", "Version 2.4.3, unmodified, statically linked into the PRoot binary. Can be relinked using the sources and scripts in tools/sandbox/."),
        Entry("Apache Commons Compress", "Apache-2.0", "Unpacks the bundled runtime environment on first launch. Copyright 2002-2025 The Apache Software Foundation."),
        Entry("Apache Commons Codec", "Apache-2.0", "Dependency of Apache Commons Compress. Copyright 2002-2025 The Apache Software Foundation."),
        Entry("Apache Commons IO", "Apache-2.0", "Dependency of Apache Commons Compress. Copyright 2002-2025 The Apache Software Foundation."),
        Entry("Apache Commons Lang", "Apache-2.0", "Dependency of Apache Commons Compress. Copyright 2001-2025 The Apache Software Foundation."),
        Entry("Kotlin standard library", "Apache-2.0", "kotlin-stdlib 2.1.20 and related artefacts, plus JetBrains annotations 23.0.0. https://kotlinlang.org/"),
        Entry("kotlinx.coroutines", "Apache-2.0", "Version 1.9.0. https://github.com/Kotlin/kotlinx.coroutines"),
        Entry("kotlinx.serialization", "Apache-2.0", "Version 1.7.3. https://github.com/Kotlin/kotlinx.serialization"),
        Entry("OpenTelemetry", "Apache-2.0", "OTLP message definitions for detailed traces. https://opentelemetry.io/"),
        Entry("Protocol Buffers", "BSD-3-Clause", "Protobuf encoding for detailed traces. https://protobuf.dev/"),
        Entry("OkHttp", "Apache-2.0", "Version 4.12.0, used for model API requests. https://square.github.io/okhttp/"),
        Entry("Okio", "Apache-2.0", "Version 3.6.0, dependency of OkHttp. https://github.com/square/okio/"),
        Entry("AndroidX (including Jetpack Compose)", "Apache-2.0", "UI and system libraries including Compose 1.7.6, Material3 1.3.1, Activity 1.9.3, Lifecycle 2.8.3, Core 1.13.1, and WebKit 1.12.1. https://developer.android.com/jetpack/androidx"),
        Entry("Guava ListenableFuture (pulled in by AndroidX)", "Apache-2.0", "listenablefuture 1.0. https://github.com/google/guava"),
        Entry("scrcpy", "Apache-2.0", "The technique for obtaining system context as shell is modelled after scrcpy; attributions are preserved in the relevant source file headers."),
        Entry("Call feature (calls/) implementation", "Apache-2.0", "Call features (dialling, answering, hanging up, AI answering, human takeover, PCM audio bridge, and real-time voice client) are incorporated under the Apache-2.0 licence; real-time voice uses Volcengine's WebSocket interface."),
        Entry("Alpine Linux", "Each package's own licence", RUNTIME_NOTE),
        Entry("XZ for Java", "0BSD", "Version 1.10, decompresses the bundled runtime environment on first launch. https://tukaani.org/xz/java.html"),
        Entry("ZXing", "Apache-2.0", "Version 3.5.3, generates WeChat binding QR codes locally on device. https://github.com/zxing/zxing"),
        Entry("commonmark-java", "BSD-2-Clause", "Version 0.30.1 (with tables and strikethrough extensions), parses Markdown in replies into structured elements for display. Copyright (c) 2015, Atlassian Pty Ltd. https://github.com/commonmark/commonmark-java"),
        Entry("xterm.js", "MIT", "Version 5.5.0 (and @xterm/addon-fit 0.10.0), terminal emulator for the user terminal view. https://github.com/xtermjs/xterm.js"),
    )
}

/**
 * 许可证页(关于页底部「Apache-2.0 开源许可」进来):先是许可证原文(assets/licenses/Apache-2.0.txt),
 * 下面列出随 Mika 发布的第三方组件,一个组件一行(名称、许可证),说明在行下面;页脚指向完整文本。
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val licenseText = remember {
        runCatching { context.assets.open(LICENSE_ASSET).use { it.readBytes().decodeToString() } }.getOrDefault("")
    }
    val runtime = remember {
        runCatching { context.assets.open("runtime/packages.json").use { Licenses.runtimePackages(it.readBytes().decodeToString()) } }
            .getOrDefault(emptyList())
    }
    val thirdParty = Licenses.ENTRIES.filter { it.name != "Mika" } + runtime
    SubPage(AboutCopy.LICENSE_TITLE, onBack) {
        if (licenseText.isNotEmpty()) {
            MikaCard(Modifier.padding(bottom = 12.dp).testTag("license-text")) {
                Text(
                    licenseText.trimEnd(), Modifier.padding(16.dp),
                    style = MikaType.Hint.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 19.sp),
                    color = MikaTheme.colors.ink2,
                )
            }
        }
        GroupLabel(stringResource(R.string.licenses_third_party, thirdParty.size))
        GroupCard {
            thirdParty.forEachIndexed { i, e ->
                Column {
                    ListRow(null, e.name, e.license, first = i == 0)
                    Text(e.note, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), style = MikaType.Hint, color = MikaTheme.colors.ink2)
                }
            }
        }
        FooterNote(stringResource(R.string.licenses_footer))
    }
}

private const val LICENSE_ASSET = "licenses/Apache-2.0.txt"

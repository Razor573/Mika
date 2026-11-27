package com.wangzan101.mika.about.ui

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
import com.wangzan101.mika.settings.ui.models.use

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
        Entry("MCP service brand logos (mcp/mcp-logos.json)", "CC0 1.0", "Monochrome outline glyphs for the MCP service catalog/list/management screens are CC0 1.0; brand trademarks remain property of their respective owners."),
        Entry("MCP service brand logos supplement (mcp/mcp-logos.json)", "MIT", "Lobe Icons v1.106.0 for additional AI/search service monochrome logos. Copyright (c) 2023 LobeHub. https://github.com/lobehub/lobe-icons"),
        Entry("sherpa-onnx", "Apache-2.0", "Version 2.0.3 (AAR statically linked with ONNX Runtime, arm64-v8a only), on-device offline speech recognition. https://github.com/k2-fsa/sherpa-onnx"),
        Entry("ONNX Runtime", "MIT", "Statically linked by sherpa-onnx as the inference engine for offline speech recognition. Copyright (c) Microsoft Corporation. https://github.com/microsoft/onnxruntime"),
        Entry("SenseVoice Small (offline speech pack)", "FunASR Model License 1.1", "Not bundled in the APK; downloaded separately as a voice pack stored on device. FunAudioLLM (Alibaba Tongyi Lab). https://github.com/FunAudioLLM/SenseVoice"),
        Entry("Dolphin Base (offline speech pack)", "Apache-2.0", "Not bundled in the APK; downloaded separately as a voice pack stored on device. Copyright 2025 DataoceanAI. https://github.com/DataoceanAI/Dolphin"),
        Entry("Whisper (offline speech pack)", "MIT", "Not bundled in the APK; downloaded separately as a voice pack stored on device. Copyright (c) 2022 OpenAI. https://github.com/openai/whisper"),
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
                    style = MikaType.Micro.copy(fontFamily = FontFamily.Monospace),
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

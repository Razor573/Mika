package com.wangzan101.mika

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 网络安全配置的守护:允许明文 http 供本机与局域网模型服务使用,同时仅信任系统根证书。
 */
class NetworkSecurityConfigTest {

    private fun baseConfig(): Element {
        val file = File("src/main/res/xml/network_security_config.xml")
        assertTrue("network security config not found from ${File(".").absolutePath}", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val configs = doc.getElementsByTagName("base-config")
        assertEquals("expected exactly one base-config", 1, configs.length)
        return configs.item(0) as Element
    }

    /** 用户自己在本机或局域网跑的模型服务多半只有 http,这条删了它们全连不上。 */
    @Test
    fun cleartextStaysPermitted() {
        assertEquals("true", baseConfig().getAttribute("cleartextTrafficPermitted"))
    }

    /** 仅信任系统根证书,不信任用户安装的 CA。 */
    @Test
    fun trustAnchorsOnlyIncludeSystemCertificates() {
        val anchors = baseConfig().getElementsByTagName("certificates")
        val sources = (0 until anchors.length).map { (anchors.item(it) as Element).getAttribute("src") }
        assertEquals(setOf("system"), sources.toSet())
    }
}

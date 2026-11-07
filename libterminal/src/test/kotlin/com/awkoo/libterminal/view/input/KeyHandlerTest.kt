package com.awkoo.libterminal.view.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 可打印字符转换：死键显示字符修正只对框架 KeyEvent 产物生效，
 * 其余输入源（IME 文本、虚拟键栏）的码点不得被改写。
 */
class KeyHandlerTest {

    @Test
    fun deadKeyDisplayChars_fromKeyEvent_mapToAscii() {
        assertEquals(0x007E, KeyHandler.processPrintableChar(0x02DC, isCtrlDown = false, fromKeyEvent = true))
        assertEquals(0x0060, KeyHandler.processPrintableChar(0x02CB, isCtrlDown = false, fromKeyEvent = true))
        assertEquals(0x005E, KeyHandler.processPrintableChar(0x02C6, isCtrlDown = false, fromKeyEvent = true))
    }

    @Test
    fun deadKeyDisplayChars_fromOtherSources_stayUnchanged() {
        assertEquals(0x02DC, KeyHandler.processPrintableChar(0x02DC, isCtrlDown = false, fromKeyEvent = false))
        assertEquals(0x02CB, KeyHandler.processPrintableChar(0x02CB, isCtrlDown = false, fromKeyEvent = false))
        assertEquals(0x02C6, KeyHandler.processPrintableChar(0x02C6, isCtrlDown = false, fromKeyEvent = false))
    }

    @Test
    fun printableChar_unrelatedToSource_passesThrough() {
        assertEquals('a'.code, KeyHandler.processPrintableChar('a'.code, isCtrlDown = false, fromKeyEvent = true))
        assertEquals('a'.code, KeyHandler.processPrintableChar('a'.code, isCtrlDown = false, fromKeyEvent = false))
    }
}

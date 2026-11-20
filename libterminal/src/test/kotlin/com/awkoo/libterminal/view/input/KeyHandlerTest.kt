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

    @Test
    fun ctrlCombination_mapsLettersAndSpecialCharsToAsciiControl() {
        assertEquals(1, KeyHandler.processPrintableChar('a'.code, isCtrlDown = true, fromKeyEvent = true))
        assertEquals(3, KeyHandler.processPrintableChar('C'.code, isCtrlDown = true, fromKeyEvent = true))
        assertEquals(26, KeyHandler.processPrintableChar('z'.code, isCtrlDown = true, fromKeyEvent = true))
        assertEquals(0, KeyHandler.processPrintableChar(' '.code, isCtrlDown = true, fromKeyEvent = true))
        assertEquals(0, KeyHandler.processPrintableChar('2'.code, isCtrlDown = true, fromKeyEvent = false))
        assertEquals(27, KeyHandler.processPrintableChar('['.code, isCtrlDown = true, fromKeyEvent = true))
        assertEquals(27, KeyHandler.processPrintableChar('3'.code, isCtrlDown = true, fromKeyEvent = false))
        assertEquals(28, KeyHandler.processPrintableChar('\\'.code, isCtrlDown = true, fromKeyEvent = true))
        assertEquals(28, KeyHandler.processPrintableChar('4'.code, isCtrlDown = true, fromKeyEvent = false))
        assertEquals(29, KeyHandler.processPrintableChar(']'.code, isCtrlDown = true, fromKeyEvent = true))
        assertEquals(29, KeyHandler.processPrintableChar('5'.code, isCtrlDown = true, fromKeyEvent = false))
        assertEquals(30, KeyHandler.processPrintableChar('^'.code, isCtrlDown = true, fromKeyEvent = true))
        assertEquals(30, KeyHandler.processPrintableChar('6'.code, isCtrlDown = true, fromKeyEvent = false))
        assertEquals(31, KeyHandler.processPrintableChar('_'.code, isCtrlDown = true, fromKeyEvent = true))
        assertEquals(31, KeyHandler.processPrintableChar('7'.code, isCtrlDown = true, fromKeyEvent = false))
        assertEquals(31, KeyHandler.processPrintableChar('/'.code, isCtrlDown = true, fromKeyEvent = false))
        assertEquals(127, KeyHandler.processPrintableChar('8'.code, isCtrlDown = true, fromKeyEvent = true))
    }

    @Test
    fun c0ControlCharacters_preserveOrNormalizeToCr() {
        assertEquals('\r'.code, KeyHandler.processPrintableChar('\n'.code, isCtrlDown = false, fromKeyEvent = false))
        assertEquals(1, KeyHandler.processPrintableChar(1, isCtrlDown = false, fromKeyEvent = false))
        assertEquals(27, KeyHandler.processPrintableChar(27, isCtrlDown = false, fromKeyEvent = false))
        assertEquals(28, KeyHandler.processPrintableChar(28, isCtrlDown = false, fromKeyEvent = false))
        assertEquals(29, KeyHandler.processPrintableChar(29, isCtrlDown = false, fromKeyEvent = false))
        assertEquals(30, KeyHandler.processPrintableChar(30, isCtrlDown = false, fromKeyEvent = false))
        assertEquals(31, KeyHandler.processPrintableChar(31, isCtrlDown = false, fromKeyEvent = false))
    }
}

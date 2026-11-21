package com.vasmarfas.networkhelm.ui.sync

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class SignInPageTest {
    // the page of the loopback address holds both outcomes, the way back of a phone and the script that picks the outcome and takes the
    // code out of the address; it lies beside the screenshots to be looked at in a browser
    @Test
    fun thePageOfTheLoopbackAddressHoldsBothOutcomes() {
        val back = "<a href=\"intent://signin#Intent;scheme=networkhelm;end\">Back to NetworkHelm</a>"
        val page = signInPage("NetworkHelm got the answer", "Go back to the app.", "The sign-in did not go through", "The cloud gave no access.", back)
        for (part in listOf("NetworkHelm got the answer", "Go back to the app.", "The sign-in did not go through", "The cloud gave no access.", back, "has('error')", "history.replaceState")) {
            assertTrue(part in page, part)
        }
        assertTrue(page.startsWith("<!doctype html>"))
        val directory = File("build/screenshots").also { it.mkdirs() }
        File(directory, "sign-in-page.html").writeText(page)
        File(directory, "sign-in-page-desktop.html").writeText(signInPage("NetworkHelm получил ответ", "Вернитесь в программу: она закончит вход сама. Эту вкладку можно закрыть.", "Вход не состоялся", "Облако не дало доступ. Вернитесь в NetworkHelm: окно синхронизации покажет причину.", ""))
    }
}

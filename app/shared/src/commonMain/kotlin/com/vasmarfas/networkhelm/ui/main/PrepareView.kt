package com.vasmarfas.networkhelm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.routeros.cliValue
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.Checkbox
import com.vasmarfas.networkhelm.ui.theme.Theme
import com.vasmarfas.networkhelm.ui.workspace.VerticalScrollbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.form_apply
import networkhelm.app.shared.generated.resources.prepare_certificate
import networkhelm.app.shared.generated.resources.prepare_done
import networkhelm.app.shared.generated.resources.prepare_done_now
import networkhelm.app.shared.generated.resources.prepare_group
import networkhelm.app.shared.generated.resources.prepare_intro
import networkhelm.app.shared.generated.resources.prepare_plain
import networkhelm.app.shared.generated.resources.prepare_plain_note
import networkhelm.app.shared.generated.resources.prepare_reconnect
import networkhelm.app.shared.generated.resources.prepare_tunnel

@Composable
internal fun PrepareView(session: DeviceSession, onSecure: (suspend () -> Unit)? = null) {
    val palette = Theme.palette
    val scope = rememberCoroutineScope()
    val encrypted = session.capabilities.encrypted
    var certificate by remember { mutableStateOf(true) }
    var tunnel by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf(true) }
    var plain by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val steps = steps(session.device.identity, certificate, tunnel, group, plain && encrypted)
    val scrolled = rememberScrollState()

    @Composable
    fun Choice(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked, {
                onChange(it)
                done = false
            }, enabled = enabled)
            Text(text, color = if (enabled) palette.text else palette.faint)
        }
    }

    Column(Modifier.fillMaxSize().background(palette.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.prepare_intro), color = palette.muted)
        Choice(stringResource(Res.string.prepare_certificate), certificate) { certificate = it }
        Choice(stringResource(Res.string.prepare_tunnel), tunnel) { tunnel = it }
        Choice(stringResource(Res.string.prepare_group), group) { group = it }
        Choice(stringResource(Res.string.prepare_plain), plain && encrypted, enabled = encrypted) { plain = it }
        if (!encrypted) Text(stringResource(Res.string.prepare_plain_note), style = Theme.type.small, color = palette.muted)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer(Modifier.fillMaxSize()) {
                Text(
                    steps.joinToString("\n") { it.command },
                    Modifier.fillMaxSize().background(palette.raised).verticalScroll(scrolled).padding(8.dp),
                    style = Theme.type.mono,
                )
            }
            VerticalScrollbar(scrolled, Modifier.align(Alignment.CenterEnd))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val reopen = onSecure?.takeIf { done && certificate && !encrypted }
            Text(
                problem ?: if (done) stringResource(if (reopen != null) Res.string.prepare_done_now else Res.string.prepare_done) else "",
                Modifier.weight(1f),
                color = if (problem != null) palette.danger else palette.success,
                style = Theme.type.small,
            )
            if (reopen != null) {
                Button(stringResource(Res.string.prepare_reconnect), {
                    busy = true
                    problem = null
                    scope.launch {
                        try {
                            reopen()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // the new connection fails as any connect does, with the errors of the socket and of TLS among them
                            problem = e.message ?: e.toString()
                        } finally {
                            busy = false
                        }
                    }
                }, Modifier.padding(end = 8.dp).testTag("prepare-reconnect"), enabled = !busy)
            }
            Button(stringResource(Res.string.form_apply), {
                busy = true
                problem = null
                scope.launch {
                    try {
                        for (step in steps) step.run(session)
                        done = true
                    } catch (e: DeviceException) {
                        problem = e.message
                    } finally {
                        busy = false
                    }
                }
            }, enabled = steps.isNotEmpty() && !busy && session.device.canWrite, primary = true)
        }
    }
}

private class Step(val command: String, val run: suspend (DeviceSession) -> Unit)

private const val NAME = "networkhelm"

private const val POLICY = "api,ssh,read,write,ftp,test,sniff,sensitive,reboot,password"

private val certificates = MenuPath.parse("certificate")
private val services = MenuPath.parse("ip/service")
private val groups = MenuPath.parse("user/group")

private fun steps(identity: String, certificate: Boolean, tunnel: Boolean, group: Boolean, plain: Boolean): List<Step> = buildList {
    val named = Filter.Equals("name", NAME)
    if (certificate) {
        add(Step("/certificate add name=$NAME common-name=${cliValue(identity)} days-valid=3650") { session ->
            if (session.count(certificates, named) == 0) session.add(certificates, mapOf("name" to NAME, "common-name" to identity, "days-valid" to "3650"))
        })
        add(Step("/certificate sign $NAME") { session ->
            if (session.count(certificates, Filter.And(listOf(named, Filter.Equals("private-key", "true")))) == 0) {
                session.run(certificates, "sign", mapOf("number" to NAME)).collect()
            }
        })
        add(Step("/ip service set api-ssl certificate=$NAME disabled=no") { session ->
            session.set(services, idOf(session, services, "api-ssl"), mapOf("certificate" to NAME, "disabled" to "no"))
        })
    }
    if (tunnel) {
        add(Step("/ip ssh set forwarding-enabled=local") { session -> session.set(MenuPath.parse("ip/ssh"), null, mapOf("forwarding-enabled" to "local")) })
    }
    if (group) {
        add(Step("/user group add name=$NAME policy=$POLICY") { session ->
            if (session.count(groups, named) == 0) session.add(groups, mapOf("name" to NAME, "policy" to POLICY))
        })
    }
    if (plain) {
        for (service in listOf("api", "telnet")) {
            add(Step("/ip service set $service disabled=yes") { session -> session.set(services, idOf(session, services, service), mapOf("disabled" to "yes")) })
        }
    }
}

// from RouterOS 7.19 each connection of a service is a row of the same name with connection=true
private suspend fun idOf(session: DeviceSession, path: MenuPath, name: String): String =
    session.watch(path, Filter.Equals("name", name)).filterIsInstance<ListEvent.Snapshot>().first().items.singleOrNull { it["connection"] != "true" }?.id
        ?: throw DeviceException(ErrorCategory.MissingItem, "no $name in ${path.cli}")

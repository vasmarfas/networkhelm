package com.vasmarfas.networkhelm.ui.main

import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.vpn_servers_error_ike_profile
import networkhelm.app.shared.generated.resources.vpn_servers_error_ike_same_id
import networkhelm.app.shared.generated.resources.vpn_servers_error_l2tp_exists
import networkhelm.app.shared.generated.resources.vpn_servers_error_l2tp_no_connection
import networkhelm.app.shared.generated.resources.vpn_servers_error_l2tp_server
import networkhelm.app.shared.generated.resources.vpn_servers_error_pptp_change
import networkhelm.app.shared.generated.resources.vpn_servers_error_pptp_exists
import networkhelm.app.shared.generated.resources.vpn_servers_error_pptp_insecure
import networkhelm.app.shared.generated.resources.vpn_servers_error_pptp_no_client
import networkhelm.app.shared.generated.resources.vpn_servers_error_pptp_no_connection
import org.jetbrains.compose.resources.StringResource

// the messages of the new servers and the imports of IKEv2 and ocserv on a Linux host, each with its string
internal val vpnServersErrorTemplates: List<Pair<String, StringResource>> = listOf(
    "text: a profile of IKEv2 or the certificate of its CA in PEM is needed" to Res.string.vpn_servers_error_ike_profile,
    "the server %1\$s already answers as %2\$s: clients would get its certificate; give the new server another name or address for clients, or change %3\$s" to
        Res.string.vpn_servers_error_ike_same_id,
    "the host is a server of L2TP and can be no client of another" to Res.string.vpn_servers_error_l2tp_server,
    "the host has an L2TP connection %1\$s already" to Res.string.vpn_servers_error_l2tp_exists,
    "the host has no L2TP connection %1\$s" to Res.string.vpn_servers_error_l2tp_no_connection,
    "PPTP gives the logins and traffic of its users to anyone who records them, as MS-CHAPv2 is broken in hours: tick Insecure, Create It" to
        Res.string.vpn_servers_error_pptp_insecure,
    "PPTP gives the logins and traffic of its users to anyone who records them: tick Insecure, Change It" to Res.string.vpn_servers_error_pptp_change,
    "the host has a PPTP connection %1\$s already" to Res.string.vpn_servers_error_pptp_exists,
    "the host has no PPTP connection %1\$s" to Res.string.vpn_servers_error_pptp_no_connection,
    "the host has no pptp: its package is pptp-linux on Debian and Ubuntu and pptp elsewhere" to Res.string.vpn_servers_error_pptp_no_client,
)

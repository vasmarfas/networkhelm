package com.vasmarfas.networkhelm.ui.main

import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.proxy_error_acme_domain
import networkhelm.app.shared.generated.resources.proxy_error_client_transport
import networkhelm.app.shared.generated.resources.proxy_error_dante_privileged
import networkhelm.app.shared.generated.resources.proxy_error_example_inbound
import networkhelm.app.shared.generated.resources.proxy_error_inbound_exists
import networkhelm.app.shared.generated.resources.proxy_error_inbound_port
import networkhelm.app.shared.generated.resources.proxy_error_link_scheme
import networkhelm.app.shared.generated.resources.proxy_error_link_unreadable
import networkhelm.app.shared.generated.resources.proxy_error_login_in_address
import networkhelm.app.shared.generated.resources.proxy_error_no_build
import networkhelm.app.shared.generated.resources.proxy_error_no_client
import networkhelm.app.shared.generated.resources.proxy_error_no_file
import networkhelm.app.shared.generated.resources.proxy_error_no_inbound
import networkhelm.app.shared.generated.resources.proxy_error_no_key_made
import networkhelm.app.shared.generated.resources.proxy_error_no_link
import networkhelm.app.shared.generated.resources.proxy_error_no_package
import networkhelm.app.shared.generated.resources.proxy_error_no_users_added
import networkhelm.app.shared.generated.resources.proxy_error_no_config
import networkhelm.app.shared.generated.resources.proxy_error_no_hasher
import networkhelm.app.shared.generated.resources.proxy_error_no_json
import networkhelm.app.shared.generated.resources.proxy_error_no_ncsa
import networkhelm.app.shared.generated.resources.proxy_error_no_outbound
import networkhelm.app.shared.generated.resources.proxy_error_no_target
import networkhelm.app.shared.generated.resources.proxy_error_no_user
import networkhelm.app.shared.generated.resources.proxy_error_not_on_path
import networkhelm.app.shared.generated.resources.proxy_error_outbound_exists
import networkhelm.app.shared.generated.resources.proxy_error_outbound_named
import networkhelm.app.shared.generated.resources.proxy_error_port_taken
import networkhelm.app.shared.generated.resources.proxy_error_reality_key
import networkhelm.app.shared.generated.resources.proxy_error_rule_empty
import networkhelm.app.shared.generated.resources.proxy_error_sing_box_mlkem
import networkhelm.app.shared.generated.resources.proxy_error_sing_box_mlkem_outbound
import networkhelm.app.shared.generated.resources.proxy_error_squid_no_users
import networkhelm.app.shared.generated.resources.proxy_error_squid_parent_password
import networkhelm.app.shared.generated.resources.proxy_error_tiny_password
import networkhelm.app.shared.generated.resources.proxy_error_upstream_password
import networkhelm.app.shared.generated.resources.proxy_error_user_exists
import networkhelm.app.shared.generated.resources.proxy_error_xray_client_link
import networkhelm.app.shared.generated.resources.proxy_error_xray_pin
import org.jetbrains.compose.resources.StringResource

// the messages of the proxy windows of a Linux host, each with its string
internal val proxyErrorTemplates: List<Pair<String, StringResource>> = listOf(
    "the host has no config file %1\$s" to Res.string.proxy_error_no_config,
    "the host has no basic_ncsa_auth of Squid" to Res.string.proxy_error_no_ncsa,
    "Squid takes no users of its own: no auth_param of it names basic_ncsa_auth" to Res.string.proxy_error_squid_no_users,
    "the host has neither openssl nor cryptpw to hash the password: the package openssl gives one" to Res.string.proxy_error_no_hasher,
    "%1\$s has the user %2\$s already" to Res.string.proxy_error_user_exists,
    "%1\$s has no user %2\$s" to Res.string.proxy_error_no_user,
    "a password of BasicAuth of tinyproxy takes letters, digits, dots, dashes and underscores alone" to Res.string.proxy_error_tiny_password,
    "the password of the upstream may hold no space, quote, colon or @" to Res.string.proxy_error_upstream_password,
    "the host has no %1\$s to take the proxy" to Res.string.proxy_error_no_target,
    "%1\$s is no JSON object" to Res.string.proxy_error_no_json,
    "%1\$s has no inbound %2\$s" to Res.string.proxy_error_no_inbound,
    "the app adds no users to an inbound of %1\$s" to Res.string.proxy_error_no_users_added,
    "the app makes no link for an inbound of %1\$s" to Res.string.proxy_error_no_link,
    "the host made no key: %1\$s" to Res.string.proxy_error_no_key_made,
    "the method username needs user.privileged root: Dante reads /etc/shadow as that user" to Res.string.proxy_error_dante_privileged,
    "the login of the proxy goes into the fields user and password, not into its address" to Res.string.proxy_error_login_in_address,
    "the repositories of the host have no package of %1\$s" to Res.string.proxy_error_no_package,
    "the release of %1\$s has no build for %2\$s" to Res.string.proxy_error_no_build,
    "%1\$s has an inbound %2\$s already" to Res.string.proxy_error_inbound_exists,
    "%1\$s has an inbound on port %2\$s already" to Res.string.proxy_error_inbound_port,
    "port %1\$s is taken on the host" to Res.string.proxy_error_port_taken,
    "the example inbound of sing-box on port %1\$s has a published password: remove it or give it a password of its own" to Res.string.proxy_error_example_inbound,
    "the link is none of vless://, vmess://, trojan://, ss:// and hy2://" to Res.string.proxy_error_link_scheme,
    "the link names no server, port or secret" to Res.string.proxy_error_link_unreadable,
    "the client takes no transport %1\$s" to Res.string.proxy_error_client_transport,
    "the client of Xray takes no link of %1\$s" to Res.string.proxy_error_xray_client_link,
    "%1\$s has no client %2\$s" to Res.string.proxy_error_no_client,
    "the login finds no %1\$s on its PATH" to Res.string.proxy_error_not_on_path,
    "the private key of Reality is no 32 bytes of base64url" to Res.string.proxy_error_reality_key,
    "sing-box %1\$s leaves X25519MLKEM768 out of its Client Hello of Reality, and servers of Xray refuse such a client: make it on the Clients tab of Xray, which Install Now of Proxy Overview installs" to
        Res.string.proxy_error_sing_box_mlkem,
    "the host has no file %1\$s" to Res.string.proxy_error_no_file,
    "ACME needs a domain of this host in server-name, not %1\$s" to Res.string.proxy_error_acme_domain,
    "the client of Xray checks every certificate, and the link gives no pcs to pin this one" to Res.string.proxy_error_xray_pin,
    "%1\$s has an outbound %2\$s already" to Res.string.proxy_error_outbound_exists,
    "%1\$s has no outbound %2\$s" to Res.string.proxy_error_no_outbound,
    "the outbound %1\$s is still named in the config: a rule, a chain or DNS goes through it" to Res.string.proxy_error_outbound_named,
    "a rule needs a domain, a geosite, a geoip, an ip or an inbound" to Res.string.proxy_error_rule_empty,
    "sing-box %1\$s leaves X25519MLKEM768 out of its Client Hello of Reality, and servers of Xray refuse such a client: make this outbound in Xray" to
        Res.string.proxy_error_sing_box_mlkem_outbound,
    "the password of the parent proxy may hold no space, quote or #" to Res.string.proxy_error_squid_parent_password,
)

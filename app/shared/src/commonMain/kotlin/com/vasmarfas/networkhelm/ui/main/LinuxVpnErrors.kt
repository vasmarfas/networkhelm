package com.vasmarfas.networkhelm.ui.main

import org.jetbrains.compose.resources.StringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.vpn_error_awg_headers
import networkhelm.app.shared.generated.resources.vpn_error_awg_junk
import networkhelm.app.shared.generated.resources.vpn_error_awg_not_kept
import networkhelm.app.shared.generated.resources.vpn_error_awg_padding
import networkhelm.app.shared.generated.resources.vpn_error_awg_unknown
import networkhelm.app.shared.generated.resources.vpn_error_collector_command
import networkhelm.app.shared.generated.resources.vpn_error_collector_runner
import networkhelm.app.shared.generated.resources.vpn_error_geneve_netplan
import networkhelm.app.shared.generated.resources.vpn_error_geneve_nm
import networkhelm.app.shared.generated.resources.vpn_error_ipsec_cert_exists
import networkhelm.app.shared.generated.resources.vpn_error_ipsec_client_kept
import networkhelm.app.shared.generated.resources.vpn_error_ipsec_exists
import networkhelm.app.shared.generated.resources.vpn_error_ipsec_no_connection
import networkhelm.app.shared.generated.resources.vpn_error_ipsec_no_eap
import networkhelm.app.shared.generated.resources.vpn_error_ipsec_no_pki
import networkhelm.app.shared.generated.resources.vpn_error_ipsec_no_plugin
import networkhelm.app.shared.generated.resources.vpn_error_ipsec_not_own
import networkhelm.app.shared.generated.resources.vpn_error_l2tp_ipsec_kept
import networkhelm.app.shared.generated.resources.vpn_error_l2tp_no_swanctl
import networkhelm.app.shared.generated.resources.vpn_error_libreswan_exists
import networkhelm.app.shared.generated.resources.vpn_error_libreswan_no_conn
import networkhelm.app.shared.generated.resources.vpn_error_libreswan_psk
import networkhelm.app.shared.generated.resources.vpn_error_nat_address
import networkhelm.app.shared.generated.resources.vpn_error_nat_manager
import networkhelm.app.shared.generated.resources.vpn_error_no_device
import networkhelm.app.shared.generated.resources.vpn_error_no_management
import networkhelm.app.shared.generated.resources.vpn_error_no_openrc
import networkhelm.app.shared.generated.resources.vpn_error_no_openssl
import networkhelm.app.shared.generated.resources.vpn_error_no_packages
import networkhelm.app.shared.generated.resources.vpn_error_no_session
import networkhelm.app.shared.generated.resources.vpn_error_no_user
import networkhelm.app.shared.generated.resources.vpn_error_not_ended
import networkhelm.app.shared.generated.resources.vpn_error_oc_no_conf
import networkhelm.app.shared.generated.resources.vpn_error_oc_not_plain
import networkhelm.app.shared.generated.resources.vpn_error_openconnect_exists
import networkhelm.app.shared.generated.resources.vpn_error_openconnect_no_connection
import networkhelm.app.shared.generated.resources.vpn_error_ovpn_client_exists
import networkhelm.app.shared.generated.resources.vpn_error_ovpn_exists
import networkhelm.app.shared.generated.resources.vpn_error_ovpn_fixed_address
import networkhelm.app.shared.generated.resources.vpn_error_ovpn_folder_exists
import networkhelm.app.shared.generated.resources.vpn_error_ovpn_nat_device
import networkhelm.app.shared.generated.resources.vpn_error_ovpn_nat_systemd
import networkhelm.app.shared.generated.resources.vpn_error_ovpn_no_ccd
import networkhelm.app.shared.generated.resources.vpn_error_ovpn_no_client
import networkhelm.app.shared.generated.resources.vpn_error_ovpn_no_connection
import networkhelm.app.shared.generated.resources.vpn_error_ovpn_no_easyrsa
import networkhelm.app.shared.generated.resources.vpn_error_ovpn_no_pki
import networkhelm.app.shared.generated.resources.vpn_error_ovpn_no_remote
import networkhelm.app.shared.generated.resources.vpn_error_ovpn_no_server
import networkhelm.app.shared.generated.resources.vpn_error_ovpn_no_service
import networkhelm.app.shared.generated.resources.vpn_error_ovpn_not_started
import networkhelm.app.shared.generated.resources.vpn_error_ovpn_password_break
import networkhelm.app.shared.generated.resources.vpn_error_ovpn_server_import
import networkhelm.app.shared.generated.resources.vpn_error_ovpn_text_server
import networkhelm.app.shared.generated.resources.vpn_error_ovpn_tls_crypt_v2
import networkhelm.app.shared.generated.resources.vpn_error_pptp_no_conf
import networkhelm.app.shared.generated.resources.vpn_error_sstp_exists
import networkhelm.app.shared.generated.resources.vpn_error_sstp_no_bundle
import networkhelm.app.shared.generated.resources.vpn_error_sstp_no_connection
import networkhelm.app.shared.generated.resources.vpn_error_ts_elsewhere
import networkhelm.app.shared.generated.resources.vpn_error_ts_key_blank
import networkhelm.app.shared.generated.resources.vpn_error_ts_no_peer
import networkhelm.app.shared.generated.resources.vpn_error_user_exists
import networkhelm.app.shared.generated.resources.vpn_error_wg_autostart
import networkhelm.app.shared.generated.resources.vpn_error_wg_exists
import networkhelm.app.shared.generated.resources.vpn_error_wg_import_awg
import networkhelm.app.shared.generated.resources.vpn_error_wg_import_dns
import networkhelm.app.shared.generated.resources.vpn_error_wg_import_masked
import networkhelm.app.shared.generated.resources.vpn_error_wg_import_text
import networkhelm.app.shared.generated.resources.vpn_error_wg_key
import networkhelm.app.shared.generated.resources.vpn_error_wg_no_free_address
import networkhelm.app.shared.generated.resources.vpn_error_wg_no_interface
import networkhelm.app.shared.generated.resources.vpn_error_wg_no_key_made
import networkhelm.app.shared.generated.resources.vpn_error_wg_no_peer
import networkhelm.app.shared.generated.resources.vpn_error_wg_no_private_key
import networkhelm.app.shared.generated.resources.vpn_error_wg_no_setting
import networkhelm.app.shared.generated.resources.vpn_error_wg_not_kept
import networkhelm.app.shared.generated.resources.vpn_error_wg_outside_etc
import networkhelm.app.shared.generated.resources.vpn_error_wg_peer_exists
import networkhelm.app.shared.generated.resources.vpn_error_wg_quick_missing
import networkhelm.app.shared.generated.resources.vpn_error_wg_resolvconf
import networkhelm.app.shared.generated.resources.vpn_error_wg_save_config
import networkhelm.app.shared.generated.resources.vpn_error_zt_elsewhere
import networkhelm.app.shared.generated.resources.vpn_error_zt_no_network

// the messages of the VPN and proxy windows of a Linux host, each with its string
internal val vpnErrorTemplates: List<Pair<String, StringResource>> = listOf(
    "a new interface goes to %1\$s, and the host has none" to Res.string.vpn_error_wg_quick_missing,
    "the host has the %1\$s interface %2\$s already" to Res.string.vpn_error_wg_exists,
    "the host has no %1\$s interface %2\$s" to Res.string.vpn_error_wg_no_interface,
    "the host has no %1\$s peer %2\$s" to Res.string.vpn_error_wg_no_peer,
    "%1\$s is in no file of wg-quick, NetworkManager or systemd-networkd" to Res.string.vpn_error_wg_not_kept,
    "%1\$s is in no file of awg-quick" to Res.string.vpn_error_awg_not_kept,
    "%1\$s: %2\$s keeps no such setting of WireGuard" to Res.string.vpn_error_wg_no_setting,
    "%1\$s: no key of WireGuard, which is 44 characters of base64" to Res.string.vpn_error_wg_key,
    "%1\$s made no key" to Res.string.vpn_error_wg_no_key_made,
    "%1\$s has the peer %2\$s already" to Res.string.vpn_error_wg_peer_exists,
    "the network of %1\$s has no free address" to Res.string.vpn_error_wg_no_free_address,
    "%1\$s lies outside /etc, and it stays as it is" to Res.string.vpn_error_wg_outside_etc,
    "autostart: the host has neither systemd nor the OpenRC service of %1\$s" to Res.string.vpn_error_wg_autostart,
    "autostart: the host has neither systemd nor OpenRC" to Res.string.vpn_error_no_openrc,
    "%1\$s has no private key" to Res.string.vpn_error_wg_no_private_key,
    "dns: %1\$s sets these servers on the host itself through resolvconf, which the host lacks; the servers for clients go to Client DNS of their peers" to
        Res.string.vpn_error_wg_resolvconf,
    "%1\$s has SaveConfig = true, and %2\$s writes the running interface over %3\$s as it goes down; take SaveConfig out of the file first" to
        Res.string.vpn_error_wg_save_config,
    "text: a configuration of %1\$s needs a private key and a peer" to Res.string.vpn_error_wg_import_text,
    "the configuration sets DNS, which %1\$s gives the host through resolvconf, and the host lacks it; take the line DNS out of it" to Res.string.vpn_error_wg_import_dns,
    "text: the keys are masked; show them in the window of the configuration or take it by Copy" to Res.string.vpn_error_wg_import_masked,
    "text: the configuration sets %1\$s, which belongs to AmneziaWG; import it in the window of AmneziaWG" to Res.string.vpn_error_wg_import_awg,
    "the awg of the host does not know %1\$s; update amneziawg-tools or leave it out" to Res.string.vpn_error_awg_unknown,
    "jmax: Jmin %1\$s is above Jmax %2\$s" to Res.string.vpn_error_awg_junk,
    "s2: with S2 = S1 + 56 the first two messages of the handshake are of one length" to Res.string.vpn_error_awg_padding,
    "%1\$s: H1, H2, H3 and H4 need values of their own and ranges that do not overlap" to Res.string.vpn_error_awg_headers,
    "NAT for the clients of %1\$s needs an address of the interface" to Res.string.vpn_error_nat_address,
    "NAT for the clients of %1\$s needs ufw or firewalld, or an interface of wg-quick" to Res.string.vpn_error_nat_manager,
    "the host has no VPN session %1\$s" to Res.string.vpn_error_no_session,
    "%1\$s has no management interface to end a session with" to Res.string.vpn_error_no_management,
    "OpenVPN did not end the session %1\$s" to Res.string.vpn_error_not_ended,
    "the host has an OpenVPN configuration %1\$s already" to Res.string.vpn_error_ovpn_exists,
    "the folder %1\$s for the PKI is there already" to Res.string.vpn_error_ovpn_folder_exists,
    "the host has no easy-rsa" to Res.string.vpn_error_ovpn_no_easyrsa,
    "the host has no OpenVPN server %1\$s" to Res.string.vpn_error_ovpn_no_server,
    "the host has no OpenVPN client %1\$s" to Res.string.vpn_error_ovpn_no_client,
    "the host has no OpenVPN connection %1\$s" to Res.string.vpn_error_ovpn_no_connection,
    "%1\$s has the client %2\$s already" to Res.string.vpn_error_ovpn_client_exists,
    "%1\$s has no PKI of easy-rsa beside its CA" to Res.string.vpn_error_ovpn_no_pki,
    "%1\$s keeps no files of client configuration" to Res.string.vpn_error_ovpn_no_ccd,
    "%1\$s gives fixed addresses only with topology subnet" to Res.string.vpn_error_ovpn_fixed_address,
    "%1\$s uses tls-crypt-v2, whose keys of clients are not made here" to Res.string.vpn_error_ovpn_tls_crypt_v2,
    "%1\$s did not start: %2\$s" to Res.string.vpn_error_ovpn_not_started,
    "NAT for the clients of %1\$s needs ufw, firewalld or systemd" to Res.string.vpn_error_ovpn_nat_systemd,
    "NAT for the clients of %1\$s needs a device with a name of its own, such as tun-%2\$s" to Res.string.vpn_error_ovpn_nat_device,
    "the host has neither systemd nor the OpenRC service of OpenVPN" to Res.string.vpn_error_ovpn_no_service,
    "import: %1\$s is the configuration of a server" to Res.string.vpn_error_ovpn_server_import,
    "text: this is the configuration of a server" to Res.string.vpn_error_ovpn_text_server,
    "the configuration names no server by remote" to Res.string.vpn_error_ovpn_no_remote,
    "the password may hold no line break" to Res.string.vpn_error_ovpn_password_break,
    "the host has a VPN user %1\$s already" to Res.string.vpn_error_user_exists,
    "the host has no VPN user %1\$s" to Res.string.vpn_error_no_user,
    "the host has neither systemd nor the crontabs of cron for the collector" to Res.string.vpn_error_collector_runner,
    "the collector has no command %1\$s" to Res.string.vpn_error_collector_command,
    "the host has an IPsec connection %1\$s already" to Res.string.vpn_error_ipsec_exists,
    "the host has no IPsec connection %1\$s" to Res.string.vpn_error_ipsec_no_connection,
    "the certificate %1\$s is there already" to Res.string.vpn_error_ipsec_cert_exists,
    "the host has no pki of strongSwan" to Res.string.vpn_error_ipsec_no_pki,
    "%1\$s is no file of NetworkHelm, and it stays as it is" to Res.string.vpn_error_ipsec_not_own,
    "%1\$s has no secret of EAP for IKEv2" to Res.string.vpn_error_ipsec_no_eap,
    "charon has no plugin %1\$s, which libcharon-extauth-plugins brings on Debian and Ubuntu" to Res.string.vpn_error_ipsec_no_plugin,
    "the host has no /etc/ocserv/ocserv.conf" to Res.string.vpn_error_oc_no_conf,
    "ocserv takes no users of its own: its auth is not plain" to Res.string.vpn_error_oc_not_plain,
    "the host has no openssl" to Res.string.vpn_error_no_openssl,
    "the host has a conn %1\$s of Libreswan already" to Res.string.vpn_error_libreswan_exists,
    "the host has no conn %1\$s of Libreswan" to Res.string.vpn_error_libreswan_no_conn,
    "psk: a quote or a line break is not a value it takes" to Res.string.vpn_error_libreswan_psk,
    "L2TP over IPsec here takes strongSwan with swanctl" to Res.string.vpn_error_l2tp_no_swanctl,
    "%1\$s is the IPsec of L2TP, which its window keeps" to Res.string.vpn_error_l2tp_ipsec_kept,
    "%1\$s is a client connection, which its tab keeps" to Res.string.vpn_error_ipsec_client_kept,
    "the host has an OpenConnect connection %1\$s already" to Res.string.vpn_error_openconnect_exists,
    "the host has no OpenConnect connection %1\$s" to Res.string.vpn_error_openconnect_no_connection,
    "%1\$s made no device %2\$s in 20 s" to Res.string.vpn_error_no_device,
    "this change lasts until a reboot: NetworkManager keeps no GENEVE tunnel" to Res.string.vpn_error_geneve_nm,
    "this change lasts until a reboot: netplan keeps no GENEVE tunnel" to Res.string.vpn_error_geneve_netplan,
    "the tailnet has no peer %1\$s" to Res.string.vpn_error_ts_no_peer,
    "auth-key: a key of Tailscale holds no blank" to Res.string.vpn_error_ts_key_blank,
    "the host has no ZeroTier network %1\$s" to Res.string.vpn_error_zt_no_network,
    "the host has no /etc/pptpd.conf" to Res.string.vpn_error_pptp_no_conf,
    "the host has an SSTP connection %1\$s already" to Res.string.vpn_error_sstp_exists,
    "the host has no SSTP connection %1\$s" to Res.string.vpn_error_sstp_no_connection,
    "the host has no bundle of CA certificates: give the CA File of the server or tick Skip Certificate Check" to Res.string.vpn_error_sstp_no_bundle,
    "the repositories of the host have no %1\$s: it comes from a repository the host does not have yet" to Res.string.vpn_error_no_packages,
    "the repositories of the host have no Tailscale: its own repository comes with curl -fsSL https://tailscale.com/install.sh | sh" to Res.string.vpn_error_ts_elsewhere,
    "the repositories of the host have no ZeroTier: its own repository comes with curl -s https://install.zerotier.com | sudo bash" to Res.string.vpn_error_zt_elsewhere,
)

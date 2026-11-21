package com.vasmarfas.networkhelm.demo

import kotlinx.coroutines.flow.emptyFlow

private val systemExport = listOf(
    "# 2026-10-12 21:14:08 by RouterOS 7.24.1",
    "# software id = DEMO-0000",
    "#",
    "# model = C53UiG+5HPaxD2HPaxD",
    "# serial number = DEMO00000001",
    "/interface bridge",
    "add admin-mac=00:00:5E:00:53:02 auto-mac=no comment=defconf name=bridge",
    "/interface wireguard",
    "add listen-port=13231 mtu=1420 name=wg-home",
    "/interface list",
    "add comment=defconf name=LAN",
    "add comment=defconf name=WAN",
    "/ip pool",
    "add name=dhcp ranges=192.168.88.10-192.168.88.254",
    "add name=guest ranges=192.168.89.10-192.168.89.200",
    "/ip dhcp-server",
    "add address-pool=dhcp interface=bridge lease-time=10m name=defconf",
    "/interface bridge port",
    "add bridge=bridge comment=defconf interface=ether2",
    "add bridge=bridge comment=defconf interface=ether3",
    "add bridge=bridge comment=defconf interface=ether4",
    "add bridge=bridge comment=defconf interface=ether5",
    "add bridge=bridge comment=defconf interface=wifi1",
    "add bridge=bridge comment=defconf interface=wifi2",
    "/interface list member",
    "add comment=defconf interface=bridge list=LAN",
    "add comment=defconf interface=ether1 list=WAN",
    "/ip address",
    "add address=192.168.88.1/24 comment=defconf interface=bridge network=192.168.88.0",
    "add address=10.66.66.1/24 interface=wg-home network=10.66.66.0",
    "/ip dhcp-client",
    "add comment=defconf interface=ether1",
    "/ip dhcp-server network",
    "add address=192.168.88.0/24 comment=defconf dns-server=192.168.88.1 domain=lan gateway=192.168.88.1",
    "/ip dns",
    "set allow-remote-requests=yes servers=1.1.1.1,9.9.9.9",
    "/ip firewall nat",
    "add action=masquerade chain=srcnat comment=\"defconf: masquerade\" ipsec-policy=out,none out-interface-list=WAN",
    "/system identity",
    "set name=demo-hap",
    "/system ntp client",
    "set enabled=yes",
).joinToString("\n")

private val systemModeFeatures = listOf(
    "scheduler", "socks", "fetch", "pptp", "l2tp", "bandwidth-test", "traffic-gen", "sniffer", "ipsec", "romon", "proxy", "hotspot", "smb", "email",
    "zerotier", "container", "install-any-version",
).map { choice(it, "yes", "no") }.toTypedArray()

private val systemResetFlags = listOf("keep-users", "caps-mode", "no-defaults", "skip-backup", "keep-apps", "shutdown", "force-v6-to-v7-configuration-upgrade")
    .map { choice(it, "yes", "no") }.toTypedArray()

internal fun DemoBuilder.systemDemo() {
    systemUpdateDemo()
    systemSettingsDemo()
    systemHistoryDemo()
    systemScriptsDemo()
    systemCommandsDemo()
    systemLoggingDemo()
    systemCertificateDemo()
    systemHardwareDemo()
    systemUsersDemo()
    systemFilesDemo()
}

private fun DemoBuilder.systemUpdateDemo() {
    settings(
        "system/package/update",
        "channel=stable", "mode=https", "check-certificate=yes", "ip-version=auto", "installed-version=7.24.1", "latest-version=7.24.1",
        "status=System is already up to date",
        fields = listOf(
            choice("channel", "stable", "long-term", "testing", "development"), choice("mode", "http", "https"),
            choice("check-certificate", "no", "yes", "yes-without-crl"), choice("ip-version", "auto", "ipv4", "ipv6"),
        ),
    )
    command("system/package/update/check-for-updates", run = { emptyFlow() })
    list("system/package/local-update", writable = false) {
        row("name=routeros", "version=7.24.1", "download=true")
        row("name=wifi-qcom", "version=7.24.1", "download=true")
    }
    list("system/package/local-update/update-package-source") {
        row("address=192.168.88.20", "user=updates")
    }
}

private fun DemoBuilder.systemSettingsDemo() {
    settings("system/clock/manual", "time-zone=+00:00", "dst-delta=00:00", "dst-start=jan/01/1970 00:00:00", "dst-end=jan/01/1970 00:00:00")
    settings("figman", "enabled=false")
    list("system/console") {
        row("port=serial0", "channel=0", "term=vt102", "disabled=false")
    }
    settings(
        "system/device-mode",
        "mode=advanced", "allowed-versions=7.13+,6.49.8+", "flagged=false", "flagging-enabled=true", "scheduler=true", "socks=false", "fetch=true", "pptp=false",
        "l2tp=false", "bandwidth-test=true", "traffic-gen=true", "sniffer=true", "ipsec=true", "romon=true", "proxy=false", "hotspot=false", "smb=true",
        "email=true", "zerotier=false", "container=true", "install-any-version=false", "partitions=false", "routerboard=false", "attempt-count=0",
        writable = false,
    )
    list("system/health", writable = false) {
        row("name=voltage", "value=24.1", "type=V")
        row("name=temperature", "value=47", "type=C")
        row("name=cpu-temperature", "value=58", "type=C")
    }
    settings("system/health/settings", "cpu-overtemp-check=false", "cpu-overtemp-startup-delay=1m", "cpu-overtemp-threshold=105C")
    list(
        "system/leds",
        fields = listOf(
            choice("type", "off", "on", "interface-activity", "interface-status", "interface-speed", "wireless-status", "wireless-signal-strength", "poe-out", "poe-fault"),
            choice("color", "none", "green", "blue", "orange", "red"),
        ),
    ) {
        row("type=interface-activity", "interface=ether1", "leds=user-led", "color=green", "disabled=false")
        row("type=wireless-status", "interface=wifi2", "leds=wifi-led", "color=blue", "disabled=false")
    }
    settings("system/leds/settings", "all-leds-off=never", fields = listOf(choice("all-leds-off", "immediate", "after-1min", "after-1h", "never")))
    settings("system/note", "show-at-login=true", "show-at-cli-login=false", "note=Home gateway. Authorized access only.")
    settings(
        "system/ntp/server",
        "enabled=true", "broadcast=false", "multicast=false", "manycast=false", "broadcast-addresses=", "vrf=main", "use-local-clock=false", "local-clock-stratum=5", "auth-key=none",
    )
    list("system/ntp/key") {
        row("key-id=1", "key-val=ntp-key-1")
    }
    list("system/keymat-provider", fields = listOf(choice("type", "hkdf", "qkd"), choice("hkdf-hash", "sha256", "sha512"))) {
        row("name=ppk-hkdf", "type=hkdf", "hkdf-hash=sha256", "key-size=256", "disabled=false")
        row("name=qkd-lab", "type=qkd", "qkd-address=192.0.2.60:8443", "qkd-kme-id=KME-A-0001", "qkd-peer-sae-id=SAE-B-0001", "qkd-certificate=none", "qkd-cache-size=128", "disabled=true")
    }
    settings(
        "system/routerboard",
        "routerboard=true", "model=C53UiG+5HPaxD2HPaxD", "serial-number=DEMO00000001", "firmware-type=ipq6000", "factory-firmware=7.2.1", "current-firmware=7.24.1", "upgrade-firmware=7.24.1",
        writable = false,
    )
    settings(
        "system/routerboard/settings",
        "auto-upgrade=false", "baud-rate=115200", "boot-device=nand-if-fail-then-ethernet", "boot-protocol=bootp", "cpu-frequency=auto", "enter-setup-on=any-key",
        "force-backup-booter=false", "preboot-etherboot=disabled", "preboot-etherboot-server=any", "protected-routerboot=disabled", "reformat-hold-button=20s",
        "reformat-hold-button-max=10m", "silent-boot=false",
    )
    settings("system/routerboard/reset-button", "enabled=true", "hold-time=0s..30s", "on-event=")
    settings(
        "system/watchdog",
        "watchdog-timer=true", "watch-address=none", "ping-start-after-boot=5m", "ping-timeout=1m", "automatic-supout=true", "auto-send-supout=false",
        "send-email-to=", "send-email-from=", "send-smtp-server=0.0.0.0",
    )
}

private fun DemoBuilder.systemHistoryDemo() {
    list("system/history", writable = false) {
        row("time=2026-10-30 21:15:07", "action=identity changed", "by=admin", "policy=write", "undo=/system/identity/set name=MikroTik", "redo=/system/identity/set name=demo-hap", "undoable=true", "redoable=false", "floating-undo=false")
        row("time=2026-10-31 09:44:02", "action=dhcp lease changed", "by=admin", "policy=write", "undo=/ip/dhcp-server/lease/set numbers=*2 !comment", "redo=/ip/dhcp-server/lease/set numbers=*2 comment=printer", "undoable=true", "redoable=false", "floating-undo=false")
        row("time=2026-10-31 09:47:31", "action=simple queue changed", "by=admin", "policy=write", "undo=/queue/simple/set numbers=*3 max-limit=50M/200M", "redo=/queue/simple/set numbers=*3 max-limit=100M/300M", "undoable=true", "redoable=false", "floating-undo=false")
        row("time=2026-10-31 09:58:44", "action=dns static added", "by=admin", "policy=write", "undo=/ip/dns/static/remove numbers=*4", "redo=/ip/dns/static/add name=files.lan cname=nas.lan ttl=1d type=CNAME", "undoable=true", "redoable=false", "floating-undo=false")
    }
}

private fun DemoBuilder.systemScriptsDemo() {
    list("system/script/job", writable = false) {
        row("script=wan-watch", "owner=admin", "started=2026-10-31 09:15:02", "type=netwatch")
    }
    list("system/script/environment", writable = false) {
        row("name=wanFailures", "value=0", "user=admin")
        row("name=lastWanDown", "value=2026-10-31 09:15:02", "user=admin")
        row("name=guestWifi", "value=off", "user=admin")
    }
    command("system/script/run", run = { emptyFlow() })
}

private fun DemoBuilder.systemCommandsDemo() {
    command("system/device-mode/update", choice("mode", "enterprise", "home", "advanced"), *systemModeFeatures, run = { refusal("it changes no device mode") })
    command("system/sup-output", run = { refusal("it makes no files") })
    command("system/reboot", run = { refusal("it restarts nothing and keeps nothing") })
    command("system/shutdown", run = { refusal("it restarts nothing and keeps nothing") })
    command("system/reset-configuration", *systemResetFlags, run = { refusal("it restarts nothing and keeps nothing") })
    command("password", run = { refusal("passwords stay as they are") })
    command("system/backup/save", text("name"), text("password"), choice("encryption", "aes-sha256", "rc4"), choice("dont-encrypt", "yes", "no"), run = { refusal("it saves no backup") })
    command("system/backup/load", text("name"), text("password"), run = { refusal("it restores nothing") })
}

private fun DemoBuilder.systemLoggingDemo() {
    list(
        "system/logging/action",
        fields = listOf(
            choice("target", "memory", "disk", "echo", "email", "remote", "script"), choice("remote-log-format", "default", "syslog", "cef"),
            choice("remote-protocol", "udp", "tcp", "tls"), choice("syslog-time-format", "bsd-syslog", "iso8601"),
            choice("syslog-severity", "auto", "debug", "info", "notice", "warning", "error", "critical", "alert", "emergency"),
        ),
    ) {
        row("name=memory", "target=memory", "memory-lines=1000", "memory-stop-on-full=false", "default=true")
        row("name=disk", "target=disk", "disk-file-name=log", "disk-lines-per-file=1000", "disk-file-count=2", "disk-stop-on-full=false", "default=true")
        row("name=echo", "target=echo", "remember=true", "default=true")
        row("name=remote", "target=remote", "remote=0.0.0.0", "remote-port=514", "src-address=0.0.0.0", "bsd-syslog=false", "remote-log-format=default", "remote-protocol=udp", "syslog-facility=daemon", "syslog-severity=auto", "syslog-time-format=bsd-syslog", "default=true")
        row("name=syslog-nas", "target=remote", "remote=192.168.88.20", "remote-port=514", "src-address=0.0.0.0", "bsd-syslog=true", "remote-log-format=syslog", "remote-protocol=udp", "syslog-facility=local0", "syslog-severity=auto", "syslog-time-format=iso8601", "add-topics-string=false", "default=false")
    }
}

private fun DemoBuilder.systemCertificateDemo() {
    list("certificate", fields = listOf(choice("digest-algorithm", "md5", "sha1", "sha256", "sha384", "sha512"))) {
        row(
            "name=local-ca", "country=ZZ", "organization=Demo Home", "common-name=demo-hap CA", "key-size=2048", "days-valid=3650", "trusted=true", "key-usage=key-cert-sign,crl-sign",
            "digest-algorithm=sha256", "key-type=rsa", "issuer=CN=demo-hap CA", "serial-number=4D41C0F2A0E15C01", "fingerprint=7c3a0f9be5d2418a66d0e3b14c9f52a7d8e0b1c3f4a5968778695a4b3c2d1e0f",
            "invalid-before=2026-01-04 10:00:00", "invalid-after=2036-01-02 10:00:00", "expires-after=478w4d", "private-key=true", "crl=false", "authority=true", "issued=false", "revoked=false", "expired=false",
        )
        row(
            "name=router.lan", "country=ZZ", "organization=Demo Home", "common-name=router.lan", "subject-alt-name=DNS:router.lan,DNS:demo-hap.lan,IP:192.168.88.1", "key-size=2048", "days-valid=365",
            "trusted=false", "key-usage=digital-signature,key-encipherment,tls-server", "digest-algorithm=sha256", "key-type=rsa", "issuer=CN=demo-hap CA", "serial-number=4D41C0F2A0E15C02",
            "fingerprint=1e8d4b73a09c26f5d7e1b0a39c85f2641d70e3ab58c9f60417d2e3b5a69c80f1", "invalid-before=2026-01-04 10:05:00", "invalid-after=2027-01-04 10:05:00", "expires-after=9w1d21h",
            "private-key=true", "crl=false", "authority=false", "issued=true", "revoked=false", "expired=false",
        )
        row(
            "name=client-laptop-work", "country=ZZ", "organization=Demo Home", "common-name=laptop-work", "key-size=2048", "days-valid=365", "trusted=false", "key-usage=tls-client",
            "digest-algorithm=sha256", "key-type=rsa", "issuer=CN=demo-hap CA", "serial-number=4D41C0F2A0E15C03", "fingerprint=b92f6a0e4d17c8350a1e9f27d6b34c8a5e0f71d2936b48ac5f0e7d1a38b92c64",
            "invalid-before=2026-02-18 18:30:00", "invalid-after=2027-02-18 18:30:00", "expires-after=15w2d6h", "private-key=false", "crl=false", "authority=false", "issued=true", "revoked=false", "expired=false",
        )
    }
    list("certificate/builtin", writable = false) {
        row("name=example-root-r1", "common-name=Example Root CA R1", "trusted=true", "key-type=rsa", "key-size=4096", "invalid-after=2038-01-19 03:14:07")
        row("name=example-root-r2", "common-name=Example Root CA R2", "trusted=true", "key-type=rsa", "key-size=4096", "invalid-after=2040-06-11 12:00:00")
        row("name=sample-global-g2", "common-name=Sample Global Root G2", "trusted=true", "key-type=rsa", "key-size=2048", "invalid-after=2038-01-15 12:00:00")
        row("name=sample-ecc-g3", "common-name=Sample ECC Root G3", "trusted=true", "key-type=ecdsa", "key-size=384", "invalid-after=2037-12-01 23:59:59")
        row("name=test-trust-anchor-2", "common-name=Test Trust Anchor 2", "trusted=true", "key-type=rsa", "key-size=4096", "invalid-after=2035-03-31 23:59:59")
    }
    list("certificate/crl", writable = false) {
        row("ca=local-ca", "last-update=2026-10-30 21:00:00", "next-update=2026-11-29 21:00:00", "url=http://router.lan/ca.crl")
        row("ca=example-root-r1", "last-update=2026-10-28 06:12:44", "next-update=2026-11-04 06:12:44", "url=http://crl.example.org/root-r1.crl")
    }
    list("certificate/scep-server") {
        row("ca-cert=local-ca", "path=/scep/lab", "days-valid=365", "request-lifetime=1d", "next-ca-cert=none", "disabled=true")
    }
    settings(
        "certificate/settings",
        "builtin-trust-store=default", "crl-download=false", "crl-store=ram", "crl-use=false",
        fields = listOf(choice("builtin-trust-store", "default", "all", "untrusted"), choice("crl-store", "ram", "system")),
    )
    command("certificate/sign", run = { refusal("it signs nothing") })
    command("certificate/import", run = { refusal("it imports nothing") })
    command("certificate/export-certificate", choice("type", "pem", "pkcs12", "pkcs1", "pkcs8"), run = { refusal("it exports nothing") })
}

private fun DemoBuilder.systemHardwareDemo() {
    list("disk", writable = false) {
        row(
            "slot=usb1", "mount-point=usb1", "model=USB Flash Disk", "interface=USB", "size=31914983424", "free=28521267200", "fs=ext4", "type=hardware", "serial=DEMO0001", "fw-version=1.00",
            "block-device=true", "mounted=true",
        )
    }
    list("port", fields = listOf(choice("parity", "none", "even", "odd"), choice("flow-control", "none", "hardware", "xon-xoff"))) {
        row("name=serial0", "used-by=Serial Console", "channels=1", "baud-rate=115200", "data-bits=8", "parity=none", "stop-bits=1", "flow-control=none")
    }
    list("port/remote-access", fields = listOf(choice("protocol", "rfc2217", "raw"))) {
        row("port=serial0", "channel=0", "protocol=rfc2217", "local-address=0.0.0.0", "ip-port=4001", "remote-addresses=192.168.88.0/24", "log-file=", "active=false", "disabled=true")
    }
    list("special-login") {
        row("user=admin", "port=serial0", "channel=0", "disabled=true")
    }
    list("system/ups") {
        row(
            "name=ups1", "port=serial0", "model=Demo UPS 650", "offline-time=0s", "min-runtime=5m", "alarm-setting=immediate", "version=1.0", "serial=DEMO-UPS-0001",
            "manufacture-date=2025-03-14", "nominal-battery-voltage=12.0", "disabled=false",
        )
    }
    settings("system/ptp", "enabled=false", "domain=0", "priority1=128", "priority2=128")
    settings("system/regulatory", "label=This device complies with Part 15 of the FCC Rules.", writable = false)
}

private fun DemoBuilder.systemUsersDemo() {
    list("user/active", writable = false) {
        row("name=admin", "when=2026-10-31 09:41:55", "address=192.168.88.101", "via=winbox", "group=full", "radius=false")
        row("name=admin", "when=2026-10-31 08:12:09", "address=10.66.66.3", "via=ssh", "group=full", "radius=false")
    }
    list("user/ssh-keys", writable = false) {
        row("user=admin", "key-type=ed25519", "bits=256", "key-owner=admin@macbook-pro")
    }
    list("user/ssh-keys/private", writable = false) {
        row("user=admin", "key-type=ed25519", "bits=256", "key-owner=admin@demo-hap")
    }
    settings("user/aaa", "accounting=true", "default-group=read", "exclude-groups=", "interim-update=0s", "use-radius=false", fields = listOf(choice("default-group", "read", "write", "full")))
    settings("user/settings", "minimum-categories=0", "minimum-password-length=0")
}

private fun DemoBuilder.systemFilesDemo() {
    list("file") {
        row("name=flash", "type=disk")
        row("name=flash/skins", "type=directory", "creation-time=2026-01-04 09:58:12", "last-modified=2026-01-04 09:58:12")
        row("name=flash/pub", "type=directory", "creation-time=2026-01-04 09:58:12", "last-modified=2026-01-04 09:58:12")
        row("name=usb1", "type=disk")
        row("name=usb1/containers", "type=directory", "creation-time=2026-09-12 20:30:41", "last-modified=2026-10-27 22:03:10")
        row("name=usb1/apps", "type=directory", "creation-time=2026-10-02 18:41:09", "last-modified=2026-10-27 22:03:12")
        row("name=auto-before-reset.backup", "type=backup", "size=61440", "creation-time=2026-01-04 09:58:40", "last-modified=2026-01-04 09:58:40")
        row("name=weekly.backup", "type=backup", "size=43520", "creation-time=2026-10-25 03:00:07", "last-modified=2026-10-25 03:00:07")
        row("name=export-2026-10-12.rsc", "type=script", "size=${systemExport.length}", "creation-time=2026-10-12 21:14:09", "last-modified=2026-10-12 21:14:09", "contents=$systemExport")
        row("name=wifi-5g-capture.pcap", "type=.pcap file", "size=2511872", "creation-time=2026-10-30 22:12:07", "last-modified=2026-10-30 22:12:07")
        row("name=cert_export_router.lan.crt", "type=.crt file", "size=1224", "creation-time=2026-01-04 10:07:31", "last-modified=2026-01-04 10:07:31")
        row("name=cert_export_router.lan.key", "type=.key file", "size=1704", "creation-time=2026-01-04 10:07:31", "last-modified=2026-01-04 10:07:31")
    }
    list("system/backup/cloud", writable = false) {
        row("name=demo-hap", "size=44544", "ros-version=7.24.1", "date=2026-10-25 03:05:02", "status=ok")
    }
}

package com.vasmarfas.networkhelm.demo

private const val PROXY_UUID = "00000000-0000-4000-8000-0000000000"

internal fun DemoBuilder.linuxProxyDemo() {
    list("proxy/overview", writable = false) {
        row(".id=squid", "name=Squid", "installed=true", "state=active", "autostart=enabled", "ports=3128/tcp", "config=/etc/squid/squid.conf", "packages=squid")
        row(".id=tinyproxy", "name=tinyproxy", "installed=true", "state=active", "autostart=enabled", "ports=8888/tcp", "config=/etc/tinyproxy/tinyproxy.conf", "packages=tinyproxy")
        row(".id=3proxy", "name=3proxy", "installed=true", "state=inactive", "autostart=disabled", "ports=", "config=/etc/3proxy/3proxy.cfg", "packages=")
        row(".id=dante", "name=Dante", "installed=true", "state=active", "autostart=enabled", "ports=1080/tcp", "config=/etc/danted.conf", "packages=dante-server")
        row(".id=shadowsocks", "name=shadowsocks-libev", "installed=true", "state=active", "autostart=enabled", "ports=8388/tcp, 8388/udp", "config=/etc/shadowsocks-libev/config.json", "packages=shadowsocks-libev")
        row(".id=sing-box", "name=sing-box", "installed=true", "state=active", "autostart=enabled", "ports=8443/tcp", "config=/etc/sing-box/config.json", "packages=https://github.com/SagerNet/sing-box/releases")
        row(".id=xray", "name=Xray", "installed=true", "state=active", "autostart=enabled", "ports=9443/tcp", "config=/usr/local/etc/xray/config.json", "packages=https://github.com/XTLS/Xray-core/releases")
    }
    command("proxy/overview/install", run = { linuxAnswer("output=Reading package lists...\nAll the chosen proxies are already installed: nothing to do.") })
    settings(
        "proxy/system",
        "http-proxy=http://192.168.10.20:3128", "https-proxy=http://192.168.10.20:3128", "no-proxy=localhost,127.0.0.1,192.168.10.0/24,.example.com", "user=", "password=",
        "environment=true", "apt=true", "dnf=false", "zypper=false", "docker=false",
    )
    proxySquidDemo()
    proxySmallDemo()
    proxyJsonDemo("sing-box", "8443", "/etc/sing-box/config.json", "1.10.1")
    proxyJsonDemo("xray", "9443", "/usr/local/etc/xray/config.json", "24.11.11")
}

private fun DemoBuilder.proxySquidDemo() {
    settings(
        "proxy/squid",
        "state=active", "version=5.7", "http-port=3128", "visible-hostname=demo-server", "autostart=true", "auth=basic", "users=2", "cache=true",
        "cache-dir=ufs /var/spool/squid 2048 16 256", "cache-mem=256 MB", "maximum-object-size=64 MB", "maximum-object-size-in-memory=512 KB", "dns-nameservers=192.168.10.1",
        "forwarded-for=delete", "via=off", "parent=", "parent-user=", "parent-password=", "connect-timeout=1 minute", "read-timeout=15 minutes", "request-timeout=5 minutes",
        "shutdown-lifetime=3 seconds", "access-log=daemon:/var/log/squid/access.log squid", "logfile-rotate=7", "config=/etc/squid/squid.conf",
    )
    list("proxy/squid/access", ordered = true) {
        row(".id=1", "action=deny", "acls=!Safe_ports")
        row(".id=2", "action=deny", "acls=CONNECT !SSL_ports")
        row(".id=3", "action=deny", "acls=blocked")
        row(".id=4", "action=allow", "acls=localnet authenticated")
        row(".id=5", "action=allow", "acls=localhost")
        row(".id=6", "action=deny", "acls=all")
    }
    list("proxy/squid/acl") {
        row(".id=localnet", "name=localnet", "type=src", "values=192.168.10.0/24 10.8.0.0/24 10.9.0.0/24")
        row(".id=SSL_ports", "name=SSL_ports", "type=port", "values=443")
        row(".id=Safe_ports", "name=Safe_ports", "type=port", "values=80 21 443 70 210 1025-65535 280 488 591 777")
        row(".id=blocked", "name=blocked", "type=dstdomain", "values=.ads.example.net .tracker.example.org")
        row(".id=authenticated", "name=authenticated", "type=proxy_auth", "values=REQUIRED")
    }
    list("proxy/squid/user") {
        row(".id=anna", "name=anna")
        row(".id=ivan", "name=ivan")
    }
    list("proxy/squid/client", writable = false) {
        row(".id=192.168.10.101", "name=192.168.10.101", "requests=1842", "day-bytes=412339807", "week-bytes=2210448871", "month-bytes=9120334871")
        row(".id=10.8.0.2", "name=10.8.0.2", "requests=611", "day-bytes=88120433", "week-bytes=402118833", "month-bytes=1204331870")
        row(".id=192.168.30.12", "name=192.168.30.12", "requests=37", "day-bytes=120334", "week-bytes=980112", "month-bytes=4120334")
    }
    list("proxy/squid/domain", writable = false) {
        row(".id=deb.debian.org", "name=deb.debian.org", "requests=412", "day-bytes=310442871", "week-bytes=880412033", "month-bytes=2210448871")
        row(".id=www.example.com", "name=www.example.com", "requests=903", "day-bytes=41204433", "week-bytes=210448871", "month-bytes=884120331")
        row(".id=cdn.example.net", "name=cdn.example.net", "requests=1120", "day-bytes=88120433", "week-bytes=1204331870", "month-bytes=6120334871")
    }
}

private fun DemoBuilder.proxySmallDemo() {
    settings(
        "proxy/tinyproxy",
        "state=active", "version=1.11.1", "port=8888", "listen=192.168.10.20", "bind=", "autostart=true", "users=1", "allow=127.0.0.1 192.168.10.0/24",
        "deny=", "connect-ports=443 563", "max-clients=100", "timeout=600", "log-level=Info", "disable-via-header=true", "via-proxy-name=",
        "xtinyproxy=false", "upstream=", "upstream-user=", "upstream-password=", "config=/etc/tinyproxy/tinyproxy.conf",
    )
    list("proxy/tinyproxy/user") {
        row(".id=kitchen-tablet", "name=kitchen-tablet")
    }
    settings(
        "proxy/3proxy",
        "state=inactive", "nserver=192.168.10.1", "nscache=65536", "log=/var/log/3proxy/3proxy.log D", "rotate=30", "maxconn=200",
        "timeouts=1 5 30 60 180 1800 15 60", "internal=192.168.10.20", "external=192.168.10.20", "autostart=false", "config=/etc/3proxy/3proxy.cfg",
    )
    list("proxy/3proxy/access", ordered = true) {
        row(".id=1", "directive=auth", "arguments=strong")
        row(".id=2", "directive=allow", "arguments=anna,ivan")
        row(".id=3", "directive=proxy", "arguments=-p3129")
        row(".id=4", "directive=socks", "arguments=-p1081")
    }
    list("proxy/3proxy/user") {
        row(".id=anna", "name=anna", "type=CR")
        row(".id=ivan", "name=ivan", "type=CL")
    }
    settings(
        "proxy/dante",
        "state=active", "version=1.4.3", "internal=vmbr0 port = 1080", "external=vmbr0", "external-rotation=none", "autostart=true", "socksmethod=username",
        "clientmethod=none", "user-privileged=root", "user-unprivileged=nobody", "timeout-negotiate=30", "timeout-connect=30", "timeout-io=86400",
        "logoutput=syslog", "config=/etc/danted.conf",
    )
    list("proxy/dante/rule", ordered = true) {
        row(".id=1", "kind=client", "action=pass", "from=192.168.10.0/24", "to=0.0.0.0/0", "options=log: error")
        row(".id=2", "kind=client", "action=pass", "from=10.8.0.0/24", "to=0.0.0.0/0", "options=log: error")
        row(".id=3", "kind=socks", "action=block", "from=0.0.0.0/0", "to=192.168.10.0/24", "options=log: connect error")
        row(".id=4", "kind=socks", "action=pass", "from=0.0.0.0/0", "to=0.0.0.0/0", "options=command: connect udpassociate")
    }
    list("proxy/dante/user") {
        row(".id=socks-anna", "name=socks-anna")
    }
    settings(
        "proxy/shadowsocks",
        "state=active", "server=::", "server-port=8388", "password=", "method=chacha20-ietf-poly1305", "mode=tcp_and_udp", "timeout=300", "autostart=true",
        "fast-open=false", "reuse-port=true", "no-delay=true", "ipv6-first=false", "nameserver=192.168.10.1", "plugin=", "plugin-opts=", "service=shadowsocks-libev",
        "config=/etc/shadowsocks-libev/config.json",
    )
    command("proxy/shadowsocks/client-link", run = { linuxAnswer("conf=ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpkZW1vLXBhc3N3b3Jk@vpn.example.com:8388#demo-server") })
}

// sing-box and Xray keep the same tables over a JSON file of their own
private fun DemoBuilder.proxyJsonDemo(kind: String, port: String, file: String, version: String) {
    val path = "proxy/$kind"
    settings(path, "state=active", "version=$version", "inbounds=2", "autostart=true", "config=$file")
    list("$path/inbound") {
        row(
            ".id=vless-reality", "tag=vless-reality", "type=vless", "listen=::", "port=$port", "security=reality", "network=tcp", "users=2", "scheme=vless-reality",
            "server-name=www.example.com", "dest=www.example.com:443", "short-ids=0123abcd", "private-key=", "certificate=", "certificate-path=", "key-path=",
            "alpn=", "server-names=www.example.com",
        )
        row(
            ".id=shadowsocks-in", "tag=shadowsocks-in", "type=shadowsocks", "listen=::", "port=${port.toInt() + 100}", "security=", "network=tcp,udp", "users=1",
            "scheme=shadowsocks", "method=2022-blake3-aes-128-gcm", "password=",
        )
    }
    list("$path/user") {
        row(".id=vless-reality/anna", "inbound=vless-reality", "type=vless", "name=anna", "secret=${PROXY_UUID}01", "flow=xtls-rprx-vision")
        row(".id=vless-reality/ivan", "inbound=vless-reality", "type=vless", "name=ivan", "secret=${PROXY_UUID}02", "flow=xtls-rprx-vision")
        row(".id=shadowsocks-in/travel", "inbound=shadowsocks-in", "type=shadowsocks", "name=travel", "secret=", "flow=")
    }
    command("$path/inbound/client-link", run = { linuxAnswer("conf=vless://${PROXY_UUID}01@vpn.example.com:$port?type=tcp&security=reality&sni=www.example.com&fp=chrome&pbk=${"demo".repeat(10)}DEM&sid=0123abcd&flow=xtls-rprx-vision#$kind-anna") })
    command("$path/user/client-link", run = {
        val user = it["number"].orEmpty().substringAfter('/')
        linuxAnswer("conf=vless://${PROXY_UUID}0${if (user == "ivan") 2 else 1}@vpn.example.com:$port?type=tcp&security=reality&sni=www.example.com&fp=chrome&pbk=${"demo".repeat(10)}DEM&sid=0123abcd&flow=xtls-rprx-vision#$kind-$user")
    })
    list("$path/outbound") {
        row(".id=direct", "tag=direct", "type=direct", "server=", "through=", "default=true", "kind=direct")
        row(".id=block", "tag=block", "type=block", "server=", "through=", "default=false", "kind=block")
        row(".id=vps-nl", "tag=vps-nl", "type=vless", "server=nl.vpn.example.net:443", "through=", "default=false", "kind=link", "link=")
    }
    list("$path/rule", ordered = true) {
        row(".id=1", "domain=", "geosite=category-ads-all", "geoip=", "ip=", "inbound=", "outbound=block", "more=")
        row(".id=2", "domain=example.org, .stream.example.net", "geosite=", "geoip=", "ip=", "inbound=", "outbound=vps-nl", "more=")
        row(".id=3", "domain=", "geosite=", "geoip=private", "ip=192.168.0.0/16", "inbound=", "outbound=direct", "more=")
    }
    list("$path/client") {
        row(".id=office-socks", "name=office-socks", "type=vless", "server=office.example.org:443", "local-port=1090", "state=inactive", "autostart=false", "link=", "config=/etc/$kind/clients/office-socks.json")
    }
    val text = if (kind == "xray") {
        "{\n  \"log\": { \"loglevel\": \"warning\" },\n  \"inbounds\": [\n    { \"tag\": \"vless-reality\", \"protocol\": \"vless\", \"listen\": \"::\", \"port\": $port,\n" +
            "      \"settings\": { \"clients\": [ { \"email\": \"anna\", \"id\": \"${PROXY_UUID}01\", \"flow\": \"xtls-rprx-vision\" } ], \"decryption\": \"none\" },\n" +
            "      \"streamSettings\": { \"network\": \"tcp\", \"security\": \"reality\",\n" +
            "        \"realitySettings\": { \"dest\": \"www.example.com:443\", \"serverNames\": [ \"www.example.com\" ], \"shortIds\": [ \"0123abcd\" ] } } }\n  ],\n" +
            "  \"outbounds\": [ { \"tag\": \"direct\", \"protocol\": \"freedom\" }, { \"tag\": \"block\", \"protocol\": \"blackhole\" } ]\n}\n"
    } else {
        "{\n  \"log\": { \"level\": \"warn\" },\n  \"inbounds\": [\n    { \"tag\": \"vless-reality\", \"type\": \"vless\", \"listen\": \"::\", \"listen_port\": $port,\n" +
            "      \"users\": [ { \"name\": \"anna\", \"uuid\": \"${PROXY_UUID}01\", \"flow\": \"xtls-rprx-vision\" } ],\n" +
            "      \"tls\": { \"enabled\": true, \"server_name\": \"www.example.com\", \"reality\": { \"enabled\": true, \"short_id\": [ \"0123abcd\" ] } } }\n  ],\n" +
            "  \"outbounds\": [ { \"tag\": \"direct\", \"type\": \"direct\" }, { \"tag\": \"block\", \"type\": \"block\" } ]\n}\n"
    }
    command("$path/config", run = { linuxAnswer("file=$file", "text=$text") })
}

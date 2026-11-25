package com.vasmarfas.networkhelm.demo

internal fun DemoBuilder.packagesDemo() {
    packagesIotDemo()
    packagesDudeDemo()
    packagesAppDemo()
    packagesContainerDemo()
    packagesOpenFlowDemo()
    packagesNetinstallDemo()
}

private fun DemoBuilder.packagesIotDemo() {
    settings("iot/modbus", "disabled=true", "hardware-port=modbus", "tcp-port=502", "timeout=100", "disable-security-rules=false")
    list("iot/mqtt/brokers") {
        row(
            "name=home-assistant", "address=192.168.88.20", "port=1883", "ssl=false", "client-id=demo-hap", "username=router", "password=", "keep-alive=60s", "auto-connect=true",
            "certificate=none", "parallel-scripts-limit=off", "will-topic=routers/demo-hap/status", "will-message=offline", "will-qos=1", "will-retain=true",
        )
        row(
            "name=cloud-broker", "address=mqtt.example.org", "port=8883", "ssl=true", "client-id=demo-hap-cloud", "username=demo-hap", "password=", "keep-alive=60s", "auto-connect=false",
            "certificate=none", "parallel-scripts-limit=off", "will-topic=", "will-message=", "will-qos=0", "will-retain=false",
        )
    }
    list("iot/mqtt/subscriptions") {
        row("broker=home-assistant", "topic=home/+/state", "qos=0", "on-message=:log info \"mqtt message received\"")
        row("broker=home-assistant", "topic=routers/demo-hap/command", "qos=1", "on-message=/system script run wan-watch")
    }
}

private fun DemoBuilder.packagesDudeDemo() {
    settings("dude", "enabled=true", "data-directory=usb1/dude")
    list("dude/device") {
        row("name=demo-hap", "comment=gateway")
        row("name=switch-closet", "comment=CSS610 in the closet")
        row("name=cap-attic", "comment=cAP ax in the attic")
        row("name=nas", "comment=file server")
        row("name=printer-hp", "comment=office printer")
    }
    list("dude/device-type") {
        row("name=router")
        row("name=switch")
        row("name=server")
        row("name=printer")
    }
    list("dude/probe") {
        row("name=ping")
        row("name=dns")
        row("name=http")
        row("name=ssh")
        row("name=snmp")
        row("name=tcp")
    }
    list("dude/service") {
        row("name=ping", "comment=demo-hap")
        row("name=ping", "comment=switch-closet")
        row("name=ping", "comment=nas")
        row("name=http", "comment=nas")
    }
    list("dude/agent") {
        row("name=local-agent", "comment=runs on the NAS")
    }
    list("dude/notification") {
        row("name=mail-admin", "comment=mail on every outage")
    }
}

private fun DemoBuilder.packagesAppDemo() {
    list("app", fields = listOf(choice("network", "internal", "lan", "default"))) {
        row("name=dns-filter", "status=running", "network=lan", "auto-update=true", "use-https=false", "devices=", "extra-mounts=", "environment=", "firewall-redirects=", "disabled=false")
        row("name=mqtt-broker", "status=stopped", "network=internal", "auto-update=false", "use-https=false", "devices=", "extra-mounts=", "environment=", "firewall-redirects=", "disabled=true")
    }
    settings(
        "app/settings",
        "app-store-urls=", "auto-update=true", "disk=usb1", "download-path=usb1/apps/download", "lan-bridge=bridge", "media-path=usb1/apps/media", "registry-mirrors=",
        "router-ip=192.168.88.1", "show-in-webfig=true",
    )
}

private fun DemoBuilder.packagesContainerDemo() {
    list("container") {
        row(
            "name=dns-filter", "status=running", "remote-image=registry.example.org/net/dns-filter:1.4", "interface=veth1", "root-dir=usb1/containers/dns-filter", "mounts=dns-config",
            "envlist=dns-env", "hostname=dns-filter", "dns=192.168.88.1", "logging=true", "start-on-boot=true", "os=linux", "arch=arm64", "stop-signal=SIGTERM", "running=true",
            "comment=DNS filter for the LAN",
        )
        row(
            "name=mqtt-broker", "status=stopped", "remote-image=registry.example.org/iot/mqtt-broker:2.0", "interface=veth2", "root-dir=usb1/containers/mqtt-broker", "mounts=mqtt-data",
            "envlist=mqtt-env", "hostname=mqtt-broker", "dns=192.168.88.1", "logging=true", "start-on-boot=false", "os=linux", "arch=arm64", "stop-signal=SIGTERM", "running=false",
        )
    }
    settings(
        "container/config",
        "registry-url=https://registry.example.org", "username=", "password=", "tmpdir=usb1/containers/tmp", "layer-dir=usb1/containers/layers", "memory-high=256.0MiB",
        "memory-max=unlimited", "swap-max=unlimited",
    )
    list("container/envs") {
        row("name=dns-env", "key=TZ", "value=Europe/Moscow")
        row("name=dns-env", "key=UPSTREAM", "value=1.1.1.1")
        row("name=mqtt-env", "key=TZ", "value=Europe/Moscow")
    }
    list("container/mounts") {
        row("name=dns-config", "src=usb1/containers/dns-config", "dst=/opt/dns/conf")
        row("name=mqtt-data", "src=usb1/containers/mqtt-data", "dst=/var/lib/mqtt")
    }
    list("container/log", writable = false) {
        row("time=2026-10-27 22:03:41", "container=mqtt-broker", "message=*** start")
        row("time=2026-10-27 22:03:42", "container=mqtt-broker", "message=*** started /usr/sbin/mosquitto -c /mosquitto/config/mosquitto.conf")
        row("time=2026-10-27 22:03:42", "container=mqtt-broker", "message=mosquitto version 2.0.18 running")
        row("time=2026-10-27 22:10:05", "container=mqtt-broker", "message=*** stop")
        row("time=2026-10-28 06:15:02", "container=dns-filter", "message=*** start")
        row("time=2026-10-28 06:15:03", "container=dns-filter", "message=*** started /usr/bin/dns-filter --config /opt/dns/conf/filter.yaml")
        row("time=2026-10-28 06:15:03", "container=dns-filter", "message=loaded 182344 blocked names from 3 lists")
        row("time=2026-10-28 06:15:04", "container=dns-filter", "message=listening on 0.0.0.0:53 udp, tcp")
        row("time=2026-10-28 09:41:17", "container=dns-filter", "message=upstream 1.1.1.1 answered in 14 ms")
    }
}

private fun DemoBuilder.packagesOpenFlowDemo() {
    list("openflow", fields = listOf(choice("version", "default", "1", "1.3"), choice("verify-peer", "none", "if-cert-present", "required"))) {
        row(
            "name=sw0", "datapath-id=0000005E00530001", "controllers=tcp:192.168.88.20:6653", "version=1.3", "verify-peer=none", "passive-port=disabled", "certificate=none",
            "isolate-controllers=false", "disabled=false",
        )
    }
    list("openflow/port") {
        row("switch=sw0", "interface=ether3", "port-id=1", "disabled=false")
        row("switch=sw0", "interface=ether5", "port-id=2", "disabled=false")
    }
    list("openflow/flow", writable = false) {
        row("switch=sw0", "table-id=0", "priority=100", "match=in_port=1,eth_dst=00:00:5E:00:53:20", "actions=output:2", "bytes=48118203", "packets=38210", "duration=2d4h11m")
        row("switch=sw0", "table-id=0", "priority=100", "match=in_port=2,eth_dst=00:00:5E:00:53:A3", "actions=output:1", "bytes=3201088", "packets=4120", "duration=2d4h11m")
        row("switch=sw0", "table-id=0", "priority=0", "match=", "actions=controller", "bytes=8904", "packets=212", "duration=3d4h12m")
    }
}

private fun DemoBuilder.packagesNetinstallDemo() {
    list("tool/netinstall", fields = listOf(choice("auto-reboot", "none", "reboot", "shutdown"))) {
        row(
            "mac-address=00:00:5E:00:53:C1", "interface=ether5", "ip-range=192.168.90.2-192.168.90.9", "version=7.24.1", "extra-packages=wifi-qcom", "allow-etherboot=false",
            "allow-flashfig=false", "apply-default-configuration=true", "auto-reboot=reboot", "install-once=true", "keep-old-configuration=false", "remove-branding=false", "wait=false",
            "disabled=true", "comment=spare hAP ax3 reflash",
        )
    }
    settings("tool/netinstall/settings", "cache-directory=usb1/netinstall")
}

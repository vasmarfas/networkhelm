package com.vasmarfas.networkhelm.demo

import com.vasmarfas.networkhelm.model.duration

private const val PROXMOX_UPTIME = 23 * 86_400L + 6 * 3_600L + 39 * 60L

internal fun DemoBuilder.linuxProxmoxDemo() {
    settings(
        "proxmox/node",
        "node=demo-server", "version=8.2.7", "kernel=6.8.12-4-pve", "boot-mode=efi", "uptime=${duration(PROXMOX_UPTIME)}", "cpu-model=Intel(R) N100", "cpu-count=4",
        "sockets=1", "cores=4", "cpu-load=12.4", "io-delay=0.3", "load=0.42 0.51 0.48", "total-memory=16694808576", "used-memory=9382916096",
        "total-swap=8589930496", "used-swap=209715200", "ksm-shared=0", "root-total=105089261568", "root-used=41231876096",
        writable = false,
        live = { _, tick -> mapOf("uptime" to duration(PROXMOX_UPTIME + tick), "cpu-load" to "${wander(124, tick, 3) / 10.0}", "io-delay" to "0.${wander(3, tick, 4)}") },
    )
    list("proxmox/storage", writable = false) {
        row(".id=backup-nas", "name=backup-nas", "type=nfs", "content=backup", "active=true", "enabled=true", "shared=true", "used=2214592512000", "total=3936818806784", "available=1722226294784", "usage=56.3")
        row(".id=data", "name=data", "type=dir", "content=images,iso", "active=true", "enabled=true", "shared=false", "used=1330491723776", "total=1967317852160", "available=536802328576", "usage=67.6")
        row(".id=local", "name=local", "type=dir", "content=iso,vztmpl,backup", "active=true", "enabled=true", "shared=false", "used=41231876096", "total=105089261568", "available=58466525184", "usage=39.2")
        row(".id=local-lvm", "name=local-lvm", "type=lvmthin", "content=rootdir,images", "active=true", "enabled=true", "shared=false", "used=88120433211", "total=374467461120", "available=286347027909", "usage=23.5")
    }
    list("proxmox/vm", live = { row, tick -> if (row["status"] == "running") mapOf("cpu" to "${wander(row["cpu"]?.toDoubleOrNull()?.times(10)?.toLong() ?: 0L, tick, row.id.hashCode()) / 10.0}") else emptyMap() }) {
        row(".id=100", "vmid=100", "name=homeassistant", "status=running", "node=demo-server", "type=qemu", "cpu=7.9", "cpus=2", "memory=2251177984", "max-memory=4294967296", "disk=0", "max-disk=34359738368", "uptime=3w2d6h", "tags=home;prod", "template=false", "lock=", "console=qm terminal 100", "cores=2", "sockets=1", "memory-size=4096", "onboot=true", "description=Home Assistant OS 13.2")
        row(".id=102", "vmid=102", "name=pbs", "status=running", "node=demo-server", "type=qemu", "cpu=0.3", "cpus=2", "memory=1101004800", "max-memory=2147483648", "disk=0", "max-disk=21474836480", "uptime=3w2d6h", "tags=backup", "template=false", "lock=", "console=qm terminal 102", "cores=2", "sockets=1", "memory-size=2048", "onboot=true", "description=Proxmox Backup Server, datastore on backup-nas")
        row(".id=103", "vmid=103", "name=win11-test", "status=stopped", "node=demo-server", "type=qemu", "cpu=0", "cpus=4", "memory=0", "max-memory=8589934592", "disk=0", "max-disk=68719476736", "uptime=", "tags=test", "template=false", "lock=", "console=qm terminal 103", "cores=4", "sockets=1", "memory-size=8192", "onboot=false", "description=")
        row(".id=9000", "vmid=9000", "name=debian-12-cloud", "status=stopped", "node=demo-server", "type=qemu", "cpu=0", "cpus=1", "memory=0", "max-memory=1073741824", "disk=0", "max-disk=3221225472", "uptime=", "tags=template", "template=true", "lock=", "console=qm terminal 9000", "cores=1", "sockets=1", "memory-size=1024", "onboot=false", "description=cloud-init image of Debian 12")
    }
    list("proxmox/ct", live = { row, tick -> if (row["status"] == "running") mapOf("cpu" to "${wander(row["cpu"]?.toDoubleOrNull()?.times(10)?.toLong() ?: 0L, tick, row.id.hashCode()) / 10.0}") else emptyMap() }) {
        row(".id=101", "vmid=101", "name=adguard", "status=running", "node=demo-server", "type=lxc", "cpu=0.4", "cpus=1", "memory=104857600", "max-memory=536870912", "disk=1288490188", "max-disk=4294967296", "uptime=3w2d6h", "tags=dns;prod", "template=false", "lock=", "console=pct enter 101", "cores=1", "memory-size=512", "swap-size=512", "onboot=true", "description=AdGuard Home for the LAN")
        row(".id=104", "vmid=104", "name=nginx-test", "status=running", "node=demo-server", "type=lxc", "cpu=0.1", "cpus=1", "memory=41943040", "max-memory=268435456", "disk=880412033", "max-disk=8589934592", "uptime=2d4h", "tags=test", "template=false", "lock=", "console=pct enter 104", "cores=1", "memory-size=256", "swap-size=256", "onboot=false", "description=")
        row(".id=105", "vmid=105", "name=pihole-old", "status=stopped", "node=demo-server", "type=lxc", "cpu=0", "cpus=1", "memory=0", "max-memory=268435456", "disk=0", "max-disk=4294967296", "uptime=", "tags=", "template=false", "lock=backup", "console=pct enter 105", "cores=1", "memory-size=256", "swap-size=0", "onboot=false", "description=replaced by AdGuard, kept for a month")
    }
    list("proxmox/snapshot") {
        row(".id=demo-server/qemu/100/before-update", "guest=100", "name=before-update", "time=2026-11-10 21:04:12", "ram=false", "description=before Home Assistant 2026.11", "parent=", "node=demo-server", "type=qemu")
        row(".id=demo-server/qemu/100/after-zigbee", "guest=100", "name=after-zigbee", "time=2026-11-14 18:30:55", "ram=true", "description=Zigbee stick moved to USB 3", "parent=before-update", "node=demo-server", "type=qemu")
        row(".id=demo-server/lxc/101/clean-install", "guest=101", "name=clean-install", "time=2026-02-03 10:12:40", "ram=false", "description=", "parent=", "node=demo-server", "type=lxc")
    }
    list("proxmox/backup") {
        row(".id=backup-nas:backup/vzdump-qemu-100-2026_11_19-03_00_02.vma.zst", "guest=100", "type=qemu", "created=2026-11-19 03:00:02", "size=4120334871", "storage=backup-nas", "format=vma.zst", "notes=homeassistant", "protected=false", "verified=", "volid=backup-nas:backup/vzdump-qemu-100-2026_11_19-03_00_02.vma.zst", "mode=snapshot", "compress=zstd")
        row(".id=backup-nas:backup/vzdump-lxc-101-2026_11_19-03_04_41.tar.zst", "guest=101", "type=lxc", "created=2026-11-19 03:04:41", "size=412339807", "storage=backup-nas", "format=tar.zst", "notes=adguard", "protected=false", "verified=", "volid=backup-nas:backup/vzdump-lxc-101-2026_11_19-03_04_41.tar.zst", "mode=snapshot", "compress=zstd")
        row(".id=backup-nas:backup/vzdump-qemu-100-2026_11_01-03_00_01.vma.zst", "guest=100", "type=qemu", "created=2026-11-01 03:00:01", "size=4011209216", "storage=backup-nas", "format=vma.zst", "notes=homeassistant, before the move", "protected=true", "verified=", "volid=backup-nas:backup/vzdump-qemu-100-2026_11_01-03_00_01.vma.zst", "mode=snapshot", "compress=zstd")
        row(".id=local:backup/vzdump-lxc-105-2026_10_20-22_10_03.tar.zst", "guest=105", "type=lxc", "created=2026-10-20 22:10:03", "size=301989888", "storage=local", "format=tar.zst", "notes=pihole-old", "protected=false", "verified=", "volid=local:backup/vzdump-lxc-105-2026_10_20-22_10_03.tar.zst", "mode=stop", "compress=zstd")
    }
    list("proxmox/task", writable = false) {
        row(".id=UPID:demo-server:0000F1A2:01B2C3D4:673C8E02:vzdump:101:root@pam:", "start=2026-11-19 12:04:02", "end=", "type=vzdump", "guest=101", "user=root@pam", "status=", "node=demo-server")
        row(".id=UPID:demo-server:0000E0B1:01A0B1C2:673BD3E2:vzdump::root@pam:", "start=2026-11-19 03:00:02", "end=2026-11-19 03:06:18", "type=vzdump", "guest=", "user=root@pam", "status=OK", "node=demo-server")
        row(".id=UPID:demo-server:0000D9C0:019F0A1B:673A8250:qmsnapshot:100:root@pam:", "start=2026-11-14 18:30:55", "end=2026-11-14 18:31:20", "type=qmsnapshot", "guest=100", "user=root@pam", "status=OK", "node=demo-server")
        row(".id=UPID:demo-server:0000C8B9:019E1C2D:67395A10:qmstart:103:root@pam:", "start=2026-11-13 20:12:00", "end=2026-11-13 20:12:03", "type=qmstart", "guest=103", "user=root@pam", "status=start failed: QEMU exited with code 1", "node=demo-server")
        row(".id=UPID:demo-server:0000B7A8:019D2E3F:673821A0:aptupdate::root@pam:", "start=2026-11-12 04:11:28", "end=2026-11-12 04:11:41", "type=aptupdate", "guest=", "user=root@pam", "status=OK", "node=demo-server")
    }
    command("proxmox/task/log", run = {
        linuxAnswer(
            "output=INFO: starting new backup job: vzdump 101 --storage backup-nas --mode snapshot --compress zstd\nINFO: Starting Backup of VM 101 (lxc)\n" +
                "INFO: Backup started at 2026-11-19 12:04:02\nINFO: status = running\nINFO: CT Name: adguard\nINFO: including mount point rootfs ('/') in backup\n" +
                "INFO: creating vzdump archive '/mnt/pve/backup-nas/dump/vzdump-lxc-101-2026_11_19-12_04_02.tar.zst'",
        )
    })
    list("proxmox/log", writable = false) {
        row("time=2026-11-19 12:04:02", "topics=pvedaemon.info", "message=<root@pam> starting task UPID:demo-server:0000F1A2:01B2C3D4:673C8E02:vzdump:101:root@pam:", "node=demo-server", "user=root@pam")
        row("time=2026-11-19 03:06:18", "topics=pvescheduler.info", "message=INFO: Backup job finished successfully", "node=demo-server", "user=root@pam")
        row("time=2026-11-14 18:31:20", "topics=pvedaemon.info", "message=<root@pam> end task UPID:demo-server:0000D9C0:019F0A1B:673A8250:qmsnapshot:100:root@pam: OK", "node=demo-server", "user=root@pam")
        row("time=2026-11-13 20:12:03", "topics=pvedaemon.err", "message=<root@pam> end task UPID:demo-server:0000C8B9:019E1C2D:67395A10:qmstart:103:root@pam: start failed: QEMU exited with code 1", "node=demo-server", "user=root@pam")
    }
}

package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.ssh.ExecResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// the answers of Proxmox VE 9.2 on a node of the lab, with the names of its guests, its storages, addresses and the node itself
// made neutral; the cluster is that one node, pve1
class LinuxProxmoxTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    // root is the login, unless the answers say otherwise
    private fun host(vararg answers: Pair<String, ExecResult>) = DockerHost(
        answers.toList() + listOf(
            "id -u" to ok("0\n"), "date +%z" to ok("+0300\n"), "pvesh get /cluster/status " to ok(CLUSTER_STATUS),
            "pvesh get /cluster/resources --type vm " to ok(RESOURCES),
        ),
    ) { error("no engine") }

    private fun proxmox(host: DockerHost) = LinuxProxmox(Root(host, null)) { setOf("pvesh") }

    private fun DockerHost.table(path: String) = proxmox(this).tables.getValue(path)

    @Test
    fun theGuestsAreTheVirtualMachinesAndContainersOfTheCluster() = runTest {
        val machines = host().table("proxmox/vm").read()
        assertEquals(listOf("100", "200", "300", "9000"), machines.map { it.id })
        assertEquals(
            listOf("vm-main", "running", "pve1", "7.5", "48", "138198470656", "137434759168", "20h44m15s", "false", "qm terminal 100"),
            listOf("name", "status", "node", "cpu", "cpus", "memory", "max-memory", "uptime", "template", "console").map { machines[0][it] },
        )
        assertEquals(listOf("", "true"), listOf(machines[1]["uptime"], machines[3]["template"]))
        assertEquals("community-script", machines[2]["tags"])
        val containers = host().table("proxmox/ct").read()
        assertEquals(listOf("101", "102"), containers.map { it.id })
        assertEquals(listOf("842235904", "4143677440", "35m12s", "pct enter 101"), listOf("disk", "max-disk", "uptime", "console").map { containers[0][it] })

        // a login that is not root reaches pvesh through sudo, and so does its console
        val sudo = DockerHost(listOf("id -u" to ok("1000\n"), "sudo -n true" to ok(), "sudo -n sh -c 'pvesh get /cluster/resources" to ok(RESOURCES))) { error("no engine") }
        assertEquals("sudo pct enter 101", sudo.table("proxmox/ct").read().first()["console"])
    }

    @Test
    fun aGuestIsToldByATaskAndTheCommandWaitsForItsEnd() = runTest {
        val start = "pvesh create '/nodes/pve1/lxc/102/status/start' "
        val status = "pvesh get '/nodes/pve1/tasks/$UPID_START/status' "
        val good = host(start to ok("\"$UPID_START\"\n"), status to ok(TASK_STOPPED_OK))
        assertNotNull(good.table("proxmox/ct").run)("start", mapOf("numbers" to "102"))
        assertEquals(listOf("$start--output-format json", "$status--output-format json"), good.ran.filter { it.startsWith("pvesh create") || it.startsWith("pvesh get '/nodes") })

        // a task that failed says why by the end of its log; warnings are no failure
        val log = "pvesh get '/nodes/pve1/tasks/$UPID_START/log' --limit 1000 "
        val bad = host(start to ok("\"$UPID_START\"\n"), status to ok(TASK_STOPPED_FAILED), log to ok(TASK_FAILED_LOG))
        val failed = assertFailsWith<DeviceException> { assertNotNull(bad.table("proxmox/ct").run)("start", mapOf("numbers" to "102")) }
        assertEquals("run_buffer: 571 Script exited with status 1\nTASK ERROR: startup for container '102' failed", failed.message)
        val warned = host("pvesh create '/nodes/pve1/qemu/300/status/start' " to ok("\"$UPID_WARNED\"\n"), "pvesh get '/nodes/pve1/tasks/$UPID_WARNED/status' " to ok(TASK_WARNINGS))
        assertNotNull(warned.table("proxmox/vm").run)("start", mapOf("numbers" to "300"))

        // pvesh that refuses says it in its own words; a guest the cluster lacks is not asked about at all
        val gone = host("pvesh get '/nodes/pve1/qemu/200/config' " to ExecResult("", "Configuration file 'nodes/pve1/qemu-server/200.conf' does not exist\n", 2))
        val refused = assertFailsWith<DeviceException> { assertNotNull(gone.table("proxmox/vm").item)("200") }
        assertEquals(ErrorCategory.Api to "Configuration file 'nodes/pve1/qemu-server/200.conf' does not exist", refused.category to refused.message)
        val unknown = assertFailsWith<DeviceException> { assertNotNull(good.table("proxmox/vm").run)("stop", mapOf("numbers" to "999999")) }
        assertEquals("the cluster has no guest 999999", unknown.message)
    }

    @Test
    fun theFormOfAGuestChangesWhatWasTouchedAlone() = runTest {
        val config = "pvesh get '/nodes/pve1/lxc/101/config' "
        val set = "pvesh set '/nodes/pve1/lxc/101/config' "
        val host = host(config to ok(CONFIG_CT), set to ok(), "pvesh set '/nodes/pve1/qemu/200/config' " to ok())
        val containers = host.table("proxmox/ct")
        val item = assertNotNull(assertNotNull(containers.item)("101"))
        assertEquals(listOf("2", "512", "512", "false", "lab host\n", "running"), listOf("cores", "memory-size", "swap-size", "onboot", "description", "status").map { item[it] })
        val change = assertNotNull(containers.set)
        change("101", mapOf("cores" to "4", "description" to "two\nlines"))
        change("101", mapOf("onboot" to ""))
        assertEquals(listOf("$set--cores 4 --description 'two\nlines' --output-format json", "$set--delete 'onboot' --output-format json"), host.ran.filter { it.startsWith("pvesh set") })
        val refused = assertFailsWith<DeviceException> { change("101", mapOf("memory-size" to "lots")) }
        assertEquals(ErrorCategory.ArgumentValue to "memory-size: lots is no number", refused.category to refused.message)
        assertNotNull(host.table("proxmox/vm").set)("200", mapOf("sockets" to "2", "onboot" to "yes"))
        assertEquals("pvesh set '/nodes/pve1/qemu/200/config' --sockets 2 --onboot 1 --output-format json", host.ran.last())
    }

    @Test
    fun theSnapshotsOfTheGuestsAndWhatMakesRemovesAndRollsBackOne() = runTest {
        val host = host(
            "for f in /etc/pve/nodes/" to ok(SNAPSHOTS),
            "pvesh create '/nodes/pve1/qemu/300/snapshot' " to ok("\"$UPID_SNAPSHOT\"\n"),
            "pvesh create '/nodes/pve1/lxc/101/snapshot' " to ok("\"$UPID_SNAPSHOT\"\n"),
            "pvesh delete '/nodes/pve1/lxc/101/snapshot/before-upgrade' " to ok("\"$UPID_SNAPSHOT\"\n"),
            "pvesh create '/nodes/pve1/lxc/101/snapshot/before-upgrade/rollback' " to ok("\"$UPID_SNAPSHOT\"\n"),
            "pvesh get '/nodes/pve1/tasks/$UPID_SNAPSHOT/status' " to ok(TASK_STOPPED_OK),
        )
        val table = host.table("proxmox/snapshot")
        val rows = table.read()
        assertEquals(listOf("101/before-upgrade", "300/clean"), rows.map { it.id })
        assertEquals(
            listOf("101", "before-upgrade", "2026-11-18 12:10:00", "false", "before the upgrade", "lxc"),
            listOf("guest", "name", "time", "ram", "description", "type").map { rows[0][it] },
        )
        assertEquals("true", rows[1]["ram"])

        val add = assertNotNull(table.add)
        assertEquals("300/pre-update", add(mapOf("guest" to "300", "name" to "pre-update", "description" to "before apt", "ram" to "yes")))
        // a container keeps no memory in a snapshot
        add(mapOf("guest" to "101", "name" to "pre-update", "ram" to "yes"))
        assertEquals(
            listOf(
                "pvesh create '/nodes/pve1/qemu/300/snapshot' --snapname 'pre-update' --description 'before apt' --vmstate 1 --output-format json",
                "pvesh create '/nodes/pve1/lxc/101/snapshot' --snapname 'pre-update' --output-format json",
            ),
            host.ran.filter { "--snapname" in it },
        )
        val wrong = assertFailsWith<DeviceException> { add(mapOf("guest" to "101", "name" to "1st")) }
        assertEquals(ErrorCategory.ArgumentValue to "the name of a snapshot is a letter and then letters, digits, - and _, 2 to 40 of them: 1st", wrong.category to wrong.message)
        assertEquals("choose the guest", assertFailsWith<DeviceException> { add(mapOf("name" to "pre-update")) }.message)

        assertNotNull(table.remove)(listOf("101/before-upgrade"))
        assertNotNull(table.run)("rollback", mapOf("number" to "101/before-upgrade"))
        assertTrue(host.ran.contains("pvesh delete '/nodes/pve1/lxc/101/snapshot/before-upgrade' --output-format json"))
        assertTrue(host.ran.contains("pvesh create '/nodes/pve1/lxc/101/snapshot/before-upgrade/rollback' --output-format json"))

        val guests = table.arguments().first()
        assertEquals(listOf("100", "101", "102", "200", "300", "9000"), guests.choices)
        assertEquals("101 ct-debian", guests.labels["101"])
    }

    @Test
    fun theBackupsOfTheStoragesThatKeepThemAndVzdumpOfAGuest() = runTest {
        val host = host(
            "pvesh get '/nodes/pve1/storage' --content backup " to ok(BACKUP_STORAGES),
            "pvesh get '/nodes/pve1/storage/local/content' --content backup " to ok(BACKUPS),
            "pvesh create '/nodes/pve1/vzdump' " to ok("\"$UPID_BACKUP\"\n"),
            "pvesm free " to ok("Removed volume 'local:backup/vzdump-lxc-101-2026_11_18-12_20_00.tar.zst'\n"),
        )
        val table = host.table("proxmox/backup")
        val backup = table.read().single()
        assertEquals("local:backup/vzdump-lxc-101-2026_11_18-12_20_00.tar.zst", backup.id)
        assertEquals(
            listOf("101", "lxc", "2026-11-18 12:20:00", "312475648", "local", "tar.zst", "ct-debian", "false"),
            listOf("guest", "type", "created", "size", "storage", "format", "notes", "protected").map { backup[it] },
        )
        // the storage that is down is left out, else its list would fail them all
        assertTrue(host.ran.none { "/storage/offline/" in it })

        val add = assertNotNull(table.add)
        assertEquals(UPID_BACKUP, add(mapOf("guest" to "101", "storage" to "local", "mode" to "snapshot", "compress" to "zstd", "notes" to "{{guestname}}")))
        assertEquals(
            "pvesh create '/nodes/pve1/vzdump' --vmid 101 --storage 'local' --mode 'snapshot' --compress 'zstd' --notes-template '{{guestname}}' --output-format json",
            host.ran.single { it.startsWith("pvesh create") },
        )
        // vzdump is left to run: the task is followed in the table of tasks
        assertTrue(host.ran.none { "/tasks/" in it })
        assertEquals("choose the storage", assertFailsWith<DeviceException> { add(mapOf("guest" to "101")) }.message)
        assertNotNull(table.remove)(listOf(backup.id))
        assertEquals("pvesm free 'local:backup/vzdump-lxc-101-2026_11_18-12_20_00.tar.zst'", host.ran.last())
        assertEquals(listOf("local"), table.arguments()[1].choices)
    }

    @Test
    fun theNodeItsStoragesItsTasksAndTheLogOfTheCluster() = runTest {
        val log = "pvesh get '/nodes/pve1/tasks/$UPID_START/log' --limit 1000 "
        val host = host(
            "pvesh get '/nodes/pve1/status' " to ok(NODE_STATUS), "pvesh get '/nodes/pve1/storage' " to ok(STORAGES), "pvesh get '/nodes/pve1/tasks' " to ok(TASKS),
            "pvesh get /cluster/log " to ok(CLUSTER_LOG), log to ok("""[{"n":1,"t":"TASK OK"}]"""), "pvesh delete '/nodes/pve1/tasks/" to ok(),
        )
        val node = host.table("proxmox/node").read().single()
        assertEquals(
            listOf("pve1", "9.2.18", "6.8.4-2-pve", "legacy-bios", "20h46m37s", "80", "2", "40", "0.0", "4.21 4.15 4.71", "202674913280", "167469694976"),
            listOf("node", "version", "kernel", "boot-mode", "uptime", "cpu-count", "sockets", "cores", "cpu-load", "load", "total-memory", "used-memory").map { node[it] },
        )
        val storages = host.table("proxmox/storage").read()
        assertEquals(listOf("data-dir", "data-thin", "local", "ssd-thin"), storages.map { it.id })
        assertEquals(listOf("lvmthin", "27.3", "true", "3618252312596"), listOf("type", "usage", "active", "available").map { storages[1][it] })

        val tasks = host.table("proxmox/task")
        val rows = tasks.read()
        assertEquals(UPID_START, rows[0].id)
        assertEquals(
            listOf("2026-11-18 12:25:02", "", "vzstart", "102", "root@pam", ""),
            listOf("start", "end", "type", "guest", "user", "status").map { rows[0][it] },
        )
        assertEquals(listOf("2026-11-18 03:08:48", "2026-11-18 03:08:53", "aptupdate", "", "OK"), listOf("start", "end", "type", "guest", "status").map { rows[1][it] })
        assertEquals("TASK OK", assertNotNull(tasks.run)("log", mapOf("number" to UPID_START)).single()["output"])
        assertNotNull(tasks.run)("stop", mapOf("numbers" to UPID_START))
        assertEquals("pvesh delete '/nodes/pve1/tasks/$UPID_START' --output-format json", host.ran.last())

        val entries = host.table("proxmox/log").read()
        assertEquals(listOf("59:pve1", "60:pve1", "68:pve1"), entries.map { it.id })
        assertEquals(listOf("2026-11-18 11:39:16", "pvedaemon.info", "successful auth for user 'root@pam'"), listOf("time", "topics", "message").map { entries[1][it] })
    }

    @Test
    fun theActionsOfThePackAreCommandsOfItsTables() {
        val tables = proxmox(host()).tables
        val pack = bundledPacks().getValue("ssh-proxmox-ve.yaml")
        assertEquals(emptyList(), pack.window.tabs.map { it.path }.filterNot { it in tables })
        for ((path, shown) in pack.paths) assertEquals(emptyList(), shown.actions.map { it.command }.filterNot { it in tables.getValue(path).commands }, path)
    }

    private companion object {
        const val UPID_START = "UPID:pve1:001575C9:0072AC48:6AFD6EEE:vzstart:102:root@pam:"
        const val UPID_WARNED = "UPID:pve1:00001279:000047F3:6AFC4A16:qmstart:300:root@pam:"
        const val UPID_SNAPSHOT = "UPID:pve1:0015A1B2:0073C0DE:6AFD7001:vzsnapshot:101:root@pam:"
        const val UPID_BACKUP = "UPID:pve1:0015A1C4:0073C1F0:6AFD7010:vzdump:101:root@pam:"

        const val CLUSTER_STATUS = """[{"id":"node/pve1","ip":"192.0.2.10","level":"","local":1,"name":"pve1","nodeid":0,"online":1,"type":"node"}]"""

        // /cluster/resources --type vm: the entries of the guests in the order the node gave them
        const val RESOURCES = """[{"cpu":0.0752656528207461,"disk":0,"diskread":0,"diskwrite":0,"id":"qemu/100","maxcpu":48,"maxdisk":0,"maxmem":137434759168,""" +
            """"mem":138198470656,"memhost":138198470656,"name":"vm-main","netin":0,"netout":0,"node":"pve1","status":"running","template":0,"type":"qemu","uptime":74655,""" +
            """"vmid":100},{"cpu":0,"disk":0,"diskread":0,"diskwrite":0,"id":"qemu/9000","maxcpu":8,"maxdisk":107374182400,"maxmem":17179869184,"mem":0,"memhost":0,""" +
            """"name":"tpl-desktop","netin":0,"netout":0,"node":"pve1","status":"stopped","template":1,"type":"qemu","uptime":0,"vmid":9000},{"cpu":0,"disk":0,""" +
            """"diskread":0,"diskwrite":0,"id":"qemu/200","maxcpu":16,"maxdisk":1610612736000,"maxmem":34358689792,"mem":0,"memhost":0,"name":"vm-desktop","netin":0,""" +
            """"netout":0,"node":"pve1","status":"stopped","template":0,"type":"qemu","uptime":0,"vmid":200},{"cpu":0.00137062756075388,"disk":842235904,""" +
            """"diskread":490668032,"diskwrite":20770816,"id":"lxc/101","maxcpu":2,"maxdisk":4143677440,"maxmem":536870912,"mem":74981376,"memhost":0,"name":"ct-debian",""" +
            """"netin":17735666,"netout":5534325,"node":"pve1","status":"running","tags":"lab","template":0,"type":"lxc","uptime":2112,"vmid":101},{"cpu":0,"disk":0,""" +
            """"diskread":0,"diskwrite":0,"id":"lxc/102","maxcpu":2,"maxdisk":4294967296,"maxmem":1073741824,"mem":0,"memhost":0,"name":"ct-fedora","netin":0,"netout":0,""" +
            """"node":"pve1","status":"stopped","tags":"lab","template":0,"type":"lxc","uptime":0,"vmid":102},{"cpu":0.0116586653162907,"disk":0,"diskread":7064812544,""" +
            """"diskwrite":23059361280,"id":"qemu/300","maxcpu":6,"maxdisk":68719476736,"maxmem":8594128896,"mem":7852785664,"memhost":8064380928,"name":"vm-home",""" +
            """"netin":3750864036,"netout":4826805363,"node":"pve1","status":"running","tags":"community-script","template":0,"type":"qemu","uptime":74596,"vmid":300}]"""

        const val NODE_STATUS = """{"boot-info":{"mode":"legacy-bios"},"cpu":0,"cpuinfo":{"cores":40,"cpus":80,"family":"6","flags":"fpu vme de pse tsc msr pae mce cx8""" +
            """ apic sep mtrr","hvm":"1","mhz":"2599.888","model":"Intel(R) Xeon(R) CPU E5-2673 v4 @ 2.30GHz","sockets":2,"user_hz":100,"vendor":"GenuineIntel"},""" +
            """"current-kernel":{"machine":"x86_64","release":"6.8.4-2-pve","sysname":"Linux","version":"#1 SMP PREEMPT_DYNAMIC PMX 6.8.4-2 (2024-04-10T17:36Z)"},"idle":0,""" +
            """"ksm":{"shared":25824878592},"kversion":"Linux 6.8.4-2-pve #1 SMP PREEMPT_DYNAMIC PMX 6.8.4-2 (2024-04-10T17:36Z)","loadavg":["4.21","4.15","4.71"],""" +
            """"memory":{"available":35205218304,"free":15007318016,"total":202674913280,"used":167469694976},"pveversion":"pve-manager/9.2.18/614bede5d65599c6",""" +
            """"rootfs":{"avail":14788132864,"free":16828661760,"total":39520157696,"used":22691495936},"swap":{"free":8587833344,"total":8589930496,"used":2097152},""" +
            """"uptime":74797,"wait":0}"""

        const val STORAGES = """[{"active":1,"avail":3618252312596,"content":"images,rootdir","enabled":1,"shared":0,"storage":"data-thin","total":4974226440192,""" +
            """"type":"lvmthin","used":1355974127596,"used_fraction":0.272599999999932},{"active":1,"avail":14788132864,"content":"vztmpl,backup,iso","enabled":1,"shared":0,""" +
            """"storage":"local","total":39520157696,"type":"dir","used":22691495936,"used_fraction":0.574175237623019},{"active":1,"avail":895552290816,""" +
            """"content":"snippets,rootdir,vztmpl,backup,images,iso","enabled":1,"shared":0,"storage":"data-dir","total":984305590272,"type":"dir","used":38677819392,""" +
            """"used_fraction":0.039294523747764},{"active":1,"avail":615479550936,"content":"images,rootdir","enabled":1,"shared":0,"storage":"ssd-thin","total":966367641600,""" +
            """"type":"lvmthin","used":350888090664,"used_fraction":0.363099999999007}]"""

        // the storages of /nodes/<node>/storage --content backup, one of them taken offline for the test
        const val BACKUP_STORAGES = """[{"active":1,"avail":14788132864,"content":"vztmpl,backup,iso","enabled":1,"shared":0,"storage":"local","total":39520157696,""" +
            """"type":"dir","used":22691495936,"used_fraction":0.574175237623019},{"active":0,"content":"backup","enabled":1,"shared":1,"storage":"offline","type":"nfs"}]"""

        // the content of a storage as the API describes it: the lab node kept no backup when its answers were read
        const val BACKUPS = """[{"content":"backup","ctime":1794993600,"format":"tar.zst","notes":"ct-debian","size":312475648,"subtype":"lxc","vmid":101,""" +
            """"volid":"local:backup/vzdump-lxc-101-2026_11_18-12_20_00.tar.zst"}]"""

        // the first task still runs and has no end and no status yet
        const val TASKS = """[{"id":"102","node":"pve1","pid":1406409,"pstart":7515208,"starttime":1794993902,"type":"vzstart","upid":"$UPID_START","user":"root@pam"},""" +
            """{"endtime":1794960533,"id":"","node":"pve1","pid":784700,"pstart":4177743,"starttime":1794960528,"status":"OK","type":"aptupdate",""" +
            """"upid":"UPID:pve1:000BF93C:003FBF4F:6AFCEC90:aptupdate::root@pam:","user":"root@pam"}]"""

        const val TASK_STOPPED_OK = """{"exitstatus":"OK","id":"102","node":"pve1","pid":1406409,"pstart":7515208,"starttime":1794993902,"status":"stopped",""" +
            """"type":"vzstart","upid":"$UPID_START","user":"root@pam"}"""
        const val TASK_WARNINGS = """{"exitstatus":"WARNINGS: 1","id":"300","node":"pve1","pid":4729,"pstart":18419,"starttime":1794918934,"status":"stopped",""" +
            """"type":"qmstart","upid":"$UPID_WARNED","user":"root@pam"}"""

        // a task that failed, as the API describes one; the lab gave none
        const val TASK_STOPPED_FAILED = """{"exitstatus":"startup for container '102' failed","id":"102","node":"pve1","pid":1406409,"pstart":7515208,""" +
            """"starttime":1794993902,"status":"stopped","type":"vzstart","upid":"$UPID_START","user":"root@pam"}"""
        const val TASK_FAILED_LOG = """[{"n":1,"t":"run_buffer: 571 Script exited with status 1"},{"n":2,"t":"TASK ERROR: startup for container '102' failed"}]"""

        const val CONFIG_CT = """{"arch":"amd64","cores":2,"description":"lab host\n","digest":"0123456789abcdef0123456789abcdef01234567","features":"nesting=1",""" +
            """"hostname":"ct-debian","memory":512,"net0":"name=eth0,bridge=vmbr0,hwaddr=BC:24:11:00:00:01,ip=dhcp,type=veth","onboot":0,"ostype":"debian",""" +
            """"rootfs":"data-thin:vm-101-disk-0,size=4G","swap":512,"tags":"lab","unprivileged":1}"""

        // the lists of snapshots as the API gives them after the marks of the script; the lab guests had none beyond current
        const val SNAPSHOTS = "--nh-guest-- pve1 lxc 101\n" +
            """[{"description":"before the upgrade","name":"before-upgrade","snaptime":1794993000},{"description":"You are here!",""" +
            """"digest":"0123456789abcdef0123456789abcdef01234567","name":"current","parent":"before-upgrade","running":1}]""" + "\n" +
            "--nh-guest-- pve1 qemu 300\n" +
            """[{"description":"","name":"clean","snaptime":1794900000,"vmstate":1},{"description":"You are here!","digest":"89abcdef0123456789abcdef0123456789abcdef",""" +
            """"name":"current","parent":"clean","running":1}]""" + "\n"

        const val CLUSTER_LOG = """[{"id":"68:pve1","msg":"end task $UPID_START OK","node":"pve1","pid":1406383,"pri":6,"tag":"pct","time":1794993906,"uid":"68",""" +
            """"user":"root@pam"},{"id":"60:pve1","msg":"successful auth for user 'root@pam'","node":"pve1","pid":813125,"pri":6,"tag":"pvedaemon","time":1794991156,""" +
            """"uid":"60","user":"root@pam"},{"id":"59:pve1","msg":"successful auth for user 'root@pam'","node":"pve1","pid":777127,"pri":6,"tag":"pvedaemon",""" +
            """"time":1794987551,"uid":"59","user":"root@pam"}]"""
    }
}

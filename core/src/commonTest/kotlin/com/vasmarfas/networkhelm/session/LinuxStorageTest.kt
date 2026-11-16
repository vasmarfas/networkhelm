package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinuxStorageTest {

    // answers a command, or the command sudo runs, by how it starts; of two answers that start the same the first goes once
    private class Host(vararg answers: Pair<String, ExecResult>) : SshSession {
        private val answers = answers.toMutableList()
        val rooted = mutableListOf<String>()

        override suspend fun exec(command: String): ExecResult {
            val sudo = command.startsWith("sudo -n sh -c ")
            val inner = if (sudo) unquote(command.removePrefix("sudo -n sh -c ")) else command
            if (sudo) rooted += inner
            val index = answers.indexOfFirst { inner.startsWith(it.first) }
            if (index < 0) return ExecResult("", "not found", 127)
            val answer = answers[index]
            if (answers.drop(index + 1).any { it.first == answer.first }) answers.removeAt(index)
            return answer.second
        }

        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = error("no shell")
        override suspend fun sftp(): SftpClient = error("no sftp")
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
        override fun close() = Unit

        private fun unquote(text: String) = text.removePrefix("'").removeSuffix("'").replace("'\\''", "'")
    }

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    private fun storage(host: Host, vararg tools: String) = LinuxStorage(host, Root(host, null)) { tools.toSet() }.tables

    private val sudo = arrayOf("id -u" to ok("1000\n"), "sudo -n true" to ok())

    // what printf writes into the new fstab
    private fun written(host: Host) = words(host.rooted.last { it.startsWith("cp -p /etc/fstab /etc/fstab.networkhelm &&") }).let { it[it.indexOf("printf") + 2] }

    private val findmnt = """{"filesystems": [""" +
        """{"target": "/sys", "source": "sysfs", "fstype": "sysfs", "options": "rw,nosuid,nodev,noexec,relatime", "size": 0, "used": 0, "avail": 0, "use%": "-"},""" +
        """{"target": "/run", "source": "tmpfs", "fstype": "tmpfs", "options": "rw,nosuid,nodev,noexec,relatime,size=19792476k,mode=755,inode64", "size": 20267495424, "used": 8314880, "avail": 20259180544, "use%": "0%"},""" +
        """{"target": "/", "source": "/dev/mapper/pve-root", "fstype": "ext4", "options": "rw,relatime,errors=remount-ro", "size": 39520157696, "used": 22707126272, "avail": 14772502528, "use%": "57%"},""" +
        """{"target": "/proc/sys/fs/binfmt_misc", "source": "systemd-1", "fstype": "autofs", "options": "rw,relatime,fd=37,pgrp=1,timeout=0,minproto=5,maxproto=5,direct", "size": 0, "used": 0, "avail": 0, "use%": "-"},""" +
        """{"target": "/mnt/pve/directory-main", "source": "/dev/sdf2", "fstype": "ext4", "options": "rw,relatime", "size": 984305590272, "used": 38677819392, "avail": 895552290816, "use%": "4%"},""" +
        """{"target": "/proc/sys/fs/binfmt_misc", "source": "binfmt_misc", "fstype": "binfmt_misc", "options": "rw,nosuid,nodev,noexec,relatime", "size": 0, "used": 0, "avail": 0, "use%": "-"},""" +
        """{"target": "/var/lib/lxcfs", "source": "lxcfs", "fstype": "fuse.lxcfs", "options": "rw,nosuid,nodev,relatime,user_id=0,group_id=0,allow_other", "size": 0, "used": 0, "avail": 0, "use%": "-"},""" +
        """{"target": "/etc/pve", "source": "/dev/fuse", "fstype": "fuse", "options": "rw,nosuid,nodev,relatime,user_id=0,group_id=0,default_permissions,allow_other", "size": 134217728, "used": 45056, "avail": 134172672, "use%": "0%"}""" +
        "]}"

    private val fstab = "# /etc/fstab: static file system information.\n#\n# <file system> <mount point>   <type>  <options>       <dump>  <pass>\n" +
        "/dev/pve/root / ext4 errors=remount-ro 0 1\n/dev/pve/swap none swap sw 0 0\nproc /proc proc defaults 0 0\n" +
        "#/dev/proxvg/proxvz /media/vz ext4 defaults,errors=remount-ro 0 2\nUUID=1111-2222 /mnt/rd\\040backup vfat noauto,nofail 0 0\n"

    private val hdd = """{"smartctl":{"version":[7,5],"exit_status":0},"device":{"name":"/dev/sdf","info_name":"/dev/sdf [SAT]","type":"sat","protocol":"ATA"},""" +
        """"model_family":"Western Digital Gold","model_name":"WDC WD6002FRYZ-01WD5B0","serial_number":"WD-RDTEST0001","firmware_version":"01.01M02",""" +
        """"user_capacity":{"blocks":11721045168,"bytes":6001175126016},"rotation_rate":7200,"smart_support":{"available":true,"enabled":true},"smart_status":{"passed":true},""" +
        """"ata_smart_attributes":{"revision":16,"table":[""" +
        """{"id":5,"name":"Reallocated_Sector_Ct","value":100,"worst":100,"thresh":5,"when_failed":"","flags":{"value":51,"string":"PO--CK ","prefailure":true},"raw":{"value":0,"string":"0"}},""" +
        """{"id":9,"name":"Power_On_Hours","value":93,"worst":93,"thresh":0,"when_failed":"","flags":{"value":18,"string":"-O--C- ","prefailure":false},"raw":{"value":49688,"string":"49688"}},""" +
        """{"id":194,"name":"Temperature_Celsius","value":200,"worst":200,"thresh":0,"when_failed":"","flags":{"value":2,"string":"-O---- ","prefailure":false},"raw":{"value":236223856670,"string":"30 (Min/Max 10/55)"}},""" +
        """{"id":197,"name":"Current_Pending_Sector","value":100,"worst":100,"thresh":0,"when_failed":"","flags":{"value":34,"string":"-O---K ","prefailure":false},"raw":{"value":0,"string":"0"}},""" +
        """{"id":198,"name":"Offline_Uncorrectable","value":100,"worst":100,"thresh":0,"when_failed":"","flags":{"value":8,"string":"---R-- ","prefailure":false},"raw":{"value":0,"string":"0"}}]},""" +
        """"power_on_time":{"hours":49688},"power_cycle_count":572,"temperature":{"current":30}}"""

    private val ssd = """{"smartctl":{"version":[7,5],"exit_status":0},"device":{"name":"/dev/sde","info_name":"/dev/sde [SAT]","type":"sat","protocol":"ATA"},""" +
        """"model_family":"Apacer AS340/350 SSDs","model_name":"Apacer AS350 1TB","serial_number":"RDTEST0002","firmware_version":"AP615PE0",""" +
        """"user_capacity":{"blocks":2000409264,"bytes":1024209543168},"rotation_rate":0,"smart_support":{"available":true,"enabled":true},"smart_status":{"passed":true},""" +
        """"ata_smart_attributes":{"revision":16,"table":[""" +
        """{"id":9,"name":"Power_On_Hours","value":100,"worst":100,"thresh":0,"when_failed":"","flags":{"value":50,"string":"-O--CK ","prefailure":false},"raw":{"value":18863,"string":"18863"}},""" +
        """{"id":194,"name":"Temperature_Celsius","value":67,"worst":67,"thresh":58,"when_failed":"","flags":{"value":34,"string":"-O---K ","prefailure":false},"raw":{"value":141736083489,"string":"33 (Min/Max 33/33)"}},""" +
        """{"id":231,"name":"Lifetime_Left","value":100,"worst":100,"thresh":0,"when_failed":"","flags":{"value":18,"string":"-O--C- ","prefailure":false},"raw":{"value":98,"string":"98"}}]},""" +
        """"power_on_time":{"hours":18863},"power_cycle_count":302,"temperature":{"current":33}}"""

    private val nvme = """{"json_format_version":[1,0],"smartctl":{"version":[7,4],"exit_status":0},"device":{"name":"/dev/nvme0","info_name":"/dev/nvme0","type":"nvme","protocol":"NVMe"},""" +
        """"model_name":"Samsung SSD 980 PRO 1TB","serial_number":"S5RDTEST000003","firmware_version":"5B2QGXA7","nvme_total_capacity":1000204886016,"nvme_unallocated_capacity":0,""" +
        """"user_capacity":{"blocks":1953525168,"bytes":1000204886016},"smart_status":{"passed":true,"nvme":{"value":0}},""" +
        """"nvme_smart_health_information_log":{"critical_warning":0,"temperature":41,"available_spare":100,"available_spare_threshold":10,"percentage_used":3,""" +
        """"data_units_read":21412455,"data_units_written":34018744,"power_cycles":1468,"power_on_hours":6015,"unsafe_shutdowns":97,"media_errors":0,"num_err_log_entries":0},""" +
        """"temperature":{"current":41},"power_cycle_count":1468,"power_on_time":{"hours":6015}}"""

    @Test
    fun findmntGivesTheMountsOfTheHostAndNotThoseOfTheKernel() {
        val mounts = parseFindmnt(findmnt)
        assertEquals(listOf("/run", "/", "/mnt/pve/directory-main", "/etc/pve"), mounts.map { it.id })
        assertEquals(
            listOf("/dev/mapper/pve-root", "ext4", "rw,relatime,errors=remount-ro", "39520157696", "22707126272", "14772502528", "57%", "false"),
            listOf("device", "type", "options", "size", "used", "free", "use", "read-only").map { mounts[1][it] },
        )
    }

    @Test
    fun busyboxGivesTheMountsByProcMountsAndTheirSizesByDf() {
        val mounts = parseMounts(
            "/dev/mapper/thinpool--main-vm--39005--disk--0 / ext4 rw,relatime,stripe=16 0 0\n" +
                "none /dev tmpfs rw,nosuid,noexec,relatime,size=10240k,mode=755,uid=100000,gid=100000,inode64 0 0\nproc /proc proc rw,nosuid,nodev,noexec,relatime 0 0\n" +
                "sysfs /sys sysfs ro,nosuid,nodev,noexec,relatime 0 0\nrun /run tmpfs rw,nodev,relatime,mode=755,uid=100000,gid=100000,inode64 0 0\n" +
                "lxcfs /proc/cpuinfo fuse.lxcfs rw,nosuid,nodev,relatime,user_id=0,group_id=0,allow_other 0 0\n" +
                "udev /dev/null devtmpfs rw,nosuid,relatime,size=98928536k,nr_inodes=24732134,mode=755,inode64 0 0\n" +
                "devpts /dev/pts devpts rw,nosuid,noexec,relatime,gid=100005,mode=620,ptmxmode=666,max=1026 0 0\n/dev/sdb1 /media/rd\\040usb vfat ro,relatime,fmask=0022,dmask=0022 0 0\n",
            "Filesystem           1024-blocks    Used Available Capacity Mounted on\n" +
                "/dev/mapper/thinpool--main-vm--39005--disk--0                            4046560     35624   3784840   1% /\n" +
                "none                     10240         4     10236   0% /dev\nrun                   98962364        80  98962284   0% /run\n" +
                "udev                  98928536         0  98928536   0% /dev/null\n/dev/sdb1               7812864   1048576   6764288  13% /media/rd usb\n",
        )
        assertEquals(listOf("/", "/dev", "/run", "/media/rd usb"), mounts.map { it.id })
        assertEquals(listOf("4143677440", "36478976", "3875676160", "1%", "false"), listOf("size", "used", "free", "use", "read-only").map { mounts[0][it] })
        assertEquals(listOf("/dev/sdb1", "vfat", "8000372736", "13%", "true"), listOf("device", "type", "size", "use", "read-only").map { mounts[3][it] })
    }

    @Test
    fun lsblkGivesEachDeviceOnceWithAllItsParents() {
        // lsblk 2.41 of a Proxmox host: the thin pool and its volumes come under its metadata and again under its data
        fun device(name: String, type: String, size: Long, fs: String = "null", uuid: String = "null", mount: String = "null", children: String = ""): String {
            val path = if (type == "lvm") "/dev/mapper/$name" else "/dev/$name"
            val nested = if (children.isEmpty()) "" else ",\"children\":[$children]"
            return """{"name":"$name","path":"$path","type":"$type","size":$size,"model":null,"serial":null,"tran":null,"rota":false,"rm":false,"ro":false,""" +
                """"fstype":$fs,"label":null,"uuid":$uuid,"mountpoint":$mount$nested}"""
        }
        val pool = device("pve-thinpool--ssd-tpool", "lvm", 966367641600, children = device("pve-vm--100--disk--0", "lvm", 34359738368))
        val volumes = listOf(
            device("pve-swap", "lvm", 8589934592, fs = "\"swap\"", mount = "\"[SWAP]\""),
            device("pve-root", "lvm", 40475033600, fs = "\"ext4\"", mount = "\"/\""),
            device("pve-thinpool--ssd_tmeta", "lvm", 121634816, children = pool),
            device("pve-thinpool--ssd_tdata", "lvm", 966367641600, children = pool),
        )
        val partitions = listOf(
            device("sde1", "part", 1031168),
            device("sde2", "part", 1073741824, fs = "\"vfat\"", uuid = "\"1234-ABCD\""),
            device("sde3", "part", 1023134400512, fs = "\"LVM2_member\"", children = volumes.joinToString(",")),
        )
        val disks = parseLsblk(
            """{"blockdevices":[{"name":"sde","path":"/dev/sde","type":"disk","size":1024209543168,"model":"Apacer AS350 1TB","serial":"RDTEST0002","tran":"sata",""" +
                """"rota":false,"rm":false,"ro":false,"fstype":null,"label":null,"uuid":null,"mountpoint":null,"children":[${partitions.joinToString(",")}]}]}""",
        )
        assertEquals(
            listOf("sde", "sde1", "sde2", "sde3", "pve-swap", "pve-root", "pve-thinpool--ssd_tmeta", "pve-thinpool--ssd-tpool", "pve-vm--100--disk--0", "pve-thinpool--ssd_tdata"),
            disks.map { it["name"] },
        )
        assertEquals(listOf("/dev/sde", "disk", "1024209543168", "Apacer AS350 1TB", "sata", "false", ""), listOf("path", "type", "size", "model", "transport", "rotational", "parent").map { disks[0][it] })
        assertEquals(listOf("vfat", "1234-ABCD", "sde"), listOf("file-system", "uuid", "parent").map { disks[2][it] })
        assertEquals(listOf("/", "sde3"), listOf("mount-point", "parent").map { disks[5][it] })
        assertEquals("pve-thinpool--ssd_tmeta, pve-thinpool--ssd_tdata", disks[7]["parent"])
        assertEquals("pve-thinpool--ssd-tpool", disks[8]["parent"])
    }

    @Test
    fun withoutLsblkTheDisksComeFromProcPartitionsAndSysBlock() {
        val disks = parsePartitions(
            "major minor  #blocks  name\n\n   1        0       4096 ram0\n   8       64 1000204632 sde\n   8       65       1007 sde1\n   8       80 5860522584 sdf\n" +
                "   8       82  977709056 sdf2\n 252        1   39526400 dm-1\n",
            "dm-1||0|0|0\nloop0||1|0|0\nsde|Apacer AS350 1TB|0|0|0\nsdf|WDC WD6002FRYZ-0|1|0|0\n",
            "/dev/sdf2 /mnt/pve/directory-main ext4 rw,relatime 0 0\n",
        )
        assertEquals(listOf("/dev/sde", "/dev/sde1", "/dev/sdf", "/dev/sdf2", "/dev/dm-1"), disks.map { it.id })
        assertEquals(listOf("disk", "1024209543168", "Apacer AS350 1TB", "false", "false", ""), listOf("type", "size", "model", "rotational", "removable", "parent").map { disks[0][it] })
        assertEquals(listOf("part", "sdf", "/mnt/pve/directory-main"), listOf("type", "parent", "mount-point").map { disks[3][it] })
        assertEquals("true", disks[2]["rotational"])
        assertEquals("dm", disks[4]["type"])
    }

    @Test
    fun fstabKeepsItsCommentsAndItsOtherLinesWhenALineIsAddedChangedOrRemoved() = runTest {
        val lines = parseFstab(fstab, setOf("/", "/proc"))
        assertEquals(listOf("/", "none", "/proc", "/mnt/rd backup"), lines.map { it.id })
        assertEquals(listOf("UUID=1111-2222", "vfat", "noauto,nofail", "0", "0", "false"), listOf("device", "type", "options", "dump", "pass", "mounted").map { lines[3][it] })
        assertEquals("true", lines[0]["mounted"])
        assertEquals(null, lines[1]["mounted"])

        val host = Host(*sudo, "cat /etc/fstab" to ok(fstab), "findmnt --verify" to ok("/\n   [ ] target exists\n"), "cp -p" to ok())
        val table = storage(host, "findmnt").getValue("system/storage/fstab")
        val head = fstab.substringBefore("UUID=1111-2222")

        assertEquals("/srv/rd data", assertNotNull(table.add)(mapOf("device" to "UUID=3333-4444", "mount-point" to "/srv/rd data", "type" to "ext4", "options" to "noatime,nofail", "pass" to "2")))
        assertEquals(fstab + "UUID=3333-4444\t/srv/rd\\040data\text4\tnoatime,nofail\t0\t2\n", written(host))
        assertEquals(
            listOf(
                "findmnt --verify --tab-file /etc/fstab",
                "cp -p /etc/fstab /etc/fstab.networkhelm && cp -p /etc/fstab /etc/fstab.networkhelm-new && printf '%s' '$fstab" +
                    "UUID=3333-4444\t/srv/rd\\040data\text4\tnoatime,nofail\t0\t2\n' > /etc/fstab.networkhelm-new && mv -f /etc/fstab.networkhelm-new /etc/fstab",
                "findmnt --verify --tab-file /etc/fstab",
            ),
            host.rooted,
        )

        assertNotNull(table.set)("/mnt/rd backup", mapOf("options" to "nofail"))
        assertEquals(head + "UUID=1111-2222\t/mnt/rd\\040backup\tvfat\tnofail\t0\t0\n", written(host))

        assertNotNull(table.remove)(listOf("/proc", "none"))
        assertEquals(
            "# /etc/fstab: static file system information.\n#\n# <file system> <mount point>   <type>  <options>       <dump>  <pass>\n/dev/pve/root / ext4 errors=remount-ro 0 1\n" +
                "#/dev/proxvg/proxvz /media/vz ext4 defaults,errors=remount-ro 0 2\nUUID=1111-2222 /mnt/rd\\040backup vfat noauto,nofail 0 0\n",
            written(host),
        )

        val before = host.rooted.size
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { assertNotNull(table.add)(mapOf("device" to "/dev/sdc1", "mount-point" to "/")) }.category)
        assertFailsWith<DeviceException> { assertNotNull(table.add)(mapOf("device" to "/dev/sdc1", "mount-point" to "/srv/c", "options" to "noatime, ro")) }
        assertEquals(before, host.rooted.size)
    }

    @Test
    fun theOldFstabComesBackWhenFindmntFindsAnErrorItDidNotHave() = runTest {
        val missing = "/mnt/rd backup\n   [E] unreachable on boot required source: UUID=1111-2222\n"
        val host = Host(
            *sudo, "cat /etc/fstab" to ok(fstab), "cp -p" to ok(),
            "findmnt --verify" to ok(missing), "findmnt --verify" to ok(missing),
            "findmnt --verify" to ok(missing), "findmnt --verify" to ok("$missing/srv/data\n   [E] unreachable on boot required target: No such file or directory\n"),
        )
        val table = storage(host, "findmnt").getValue("system/storage/fstab")
        assertNotNull(table.set)("/mnt/rd backup", mapOf("options" to "nofail"))
        assertTrue(host.rooted.none { it == "cp -p /etc/fstab.networkhelm /etc/fstab" }, "${host.rooted}")

        val refused = assertFailsWith<DeviceException> { assertNotNull(table.add)(mapOf("device" to "/dev/sdc1", "mount-point" to "/srv/data")) }
        assertTrue("/srv/data: unreachable on boot required target" in refused.message.orEmpty() && "UUID=1111-2222" !in refused.message.orEmpty(), refused.message)
        assertEquals("cp -p /etc/fstab.networkhelm /etc/fstab", host.rooted.last())
    }

    @Test
    fun mountingAndUnmountingGoAsRootAndTheRootAndTheHomeStayMounted() = runTest {
        val host = Host(
            *sudo, "mkdir -p" to ok(), "mount" to ok(), "umount" to ok(), "cat /etc/fstab" to ok(fstab), "cp -p" to ok(),
            "df -P -k \"\$HOME\"" to ok("Filesystem           1024-blocks    Used Available Capacity Mounted on\n/dev/sdc1               7812864   1048576   6764288  13% /home\n"),
        )
        val table = storage(host).getValue("system/storage")

        assertEquals("/srv/data", assertNotNull(table.add)(mapOf("device" to "UUID=3333-4444", "mount-point" to "/srv/data", "type" to "ext4", "options" to "noatime", "fstab" to "yes")))
        assertEquals("mkdir -p '/srv/data' && mount -t 'ext4' -o 'noatime' 'UUID=3333-4444' '/srv/data'", host.rooted[0])
        assertEquals(fstab + "UUID=3333-4444\t/srv/data\text4\tnoatime\t0\t0\n", written(host))

        assertNotNull(table.add)(mapOf("device" to "server:/export", "mount-point" to "/mnt/nfs", "create" to "false"))
        assertEquals("mount 'server:/export' '/mnt/nfs'", host.rooted.last())

        assertNotNull(table.set)("/srv/data", mapOf("options" to "ro,noatime"))
        assertEquals("mount -o 'remount,ro,noatime' '/srv/data'", host.rooted.last())

        assertNotNull(table.remove)(listOf("/srv/data", "/srv/data/cache"))
        assertEquals("umount '/srv/data/cache' && umount '/srv/data'", host.rooted.last())

        val before = host.rooted.size
        for (point in listOf("/", "/home")) {
            assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { assertNotNull(table.remove)(listOf("/srv/data", point)) }.category)
        }
        assertFailsWith<DeviceException> { assertNotNull(table.add)(mapOf("device" to "/dev/sdc2", "mount-point" to "srv/data")) }
        assertEquals(before, host.rooted.size)
    }

    @Test
    fun smartctlGivesTheStateOfAHardDiskAnSsdAndAnNvmeDrive() = runTest {
        val fields = listOf(
            "model", "serial", "firmware", "capacity", "smart-enabled", "health", "temperature", "power-on-hours", "power-cycles", "reallocated", "pending",
            "uncorrectable", "wear", "life-left",
        )
        val disk = parseSmart("/dev/sdf", "sat", hdd)
        assertEquals("/dev/sdf -d sat", disk.id)
        assertEquals(
            listOf("WDC WD6002FRYZ-01WD5B0", "WD-RDTEST0001", "01.01M02", "6001175126016", "true", "PASSED", "30", "49688", "572", "0", "0", "0", "", ""),
            fields.map { disk[it] },
        )
        assertEquals("FAILED", parseSmart("/dev/sdf", "sat", hdd.replace(""""passed":true""", """"passed":false"""))["health"])
        assertEquals(listOf("Apacer AS350 1TB", "33", "100", ""), listOf("model", "temperature", "life-left", "reallocated").map { parseSmart("/dev/sde", "sat", ssd)[it] })
        assertEquals(
            listOf("Samsung SSD 980 PRO 1TB", "S5RDTEST000003", "5B2QGXA7", "1000204886016", "", "PASSED", "41", "6015", "1468", "", "", "0", "3", ""),
            fields.map { parseSmart("/dev/nvme0", "nvme", nvme)[it] },
        )

        val host = Host(
            *sudo,
            "smartctl --scan-open -j" to ok(
                """{"json_format_version":[1,0],"smartctl":{"version":[7,5],"exit_status":0},"devices":[""" +
                    """{"name":"/dev/sdf","info_name":"/dev/sdf [SAT]","type":"sat","protocol":"ATA"},{"name":"/dev/sde","info_name":"/dev/sde [SAT]","type":"sat","protocol":"ATA"}]}""",
            ),
            "smartctl -j -n standby" to ok("$hdd\n--nh-section--\n$ssd\n"),
        )
        val drives = storage(host, "smartctl").getValue("system/storage/smart").read()
        assertEquals(listOf("/dev/sdf -d sat", "/dev/sde -d sat"), drives.map { it.id })
        assertEquals(listOf("PASSED", "PASSED"), drives.map { it["health"] })
        assertEquals("smartctl -j -n standby -i -H -A -d 'sat' '/dev/sdf'; echo --nh-section--; smartctl -j -n standby -i -H -A -d 'sat' '/dev/sde'", host.rooted.last())
    }

    @Test
    fun smartctlBefore7IsReadByItsText() {
        assertEquals(listOf("/dev/sde" to "sat", "/dev/nvme0" to "nvme"), parseSmartScan("/dev/sde -d sat # /dev/sde [SAT], ATA device\n# /dev/sdg -d usbcypress # /dev/sdg [USB Cypress], open failed\n/dev/nvme0 -d nvme # /dev/nvme0, NVMe device\n"))
        val disk = parseSmart(
            "/dev/sdf", "sat",
            "smartctl 6.6 2017-11-05 r4594 [x86_64-linux-4.19.0-27-amd64] (local build)\nCopyright (C) 2002-17, Bruce Allen, Christian Franke, www.smartmontools.org\n\n" +
                "=== START OF INFORMATION SECTION ===\nModel Family:     Western Digital Gold\nDevice Model:     WDC WD6002FRYZ-01WD5B0\nSerial Number:    WD-RDTEST0001\n" +
                "Firmware Version: 01.01M02\nUser Capacity:    6,001,175,126,016 bytes [6.00 TB]\nLocal Time is:    Tue Nov 17 12:08:08 2026 MSK\n" +
                "SMART support is: Available - device has SMART capability.\nSMART support is: Enabled\nPower mode is:    ACTIVE or IDLE\n\n" +
                "=== START OF READ SMART DATA SECTION ===\nSMART overall-health self-assessment test result: PASSED\n\n" +
                "ID# ATTRIBUTE_NAME          FLAG     VALUE WORST THRESH TYPE      UPDATED  WHEN_FAILED RAW_VALUE\n" +
                "  5 Reallocated_Sector_Ct   0x0033   100   100   005    Pre-fail  Always       -       0\n" +
                "  9 Power_On_Hours          0x0012   093   093   000    Old_age   Always       -       49688\n" +
                "194 Temperature_Celsius     0x0002   200   200   000    Old_age   Always       -       30 (Min/Max 10/55)\n",
        )
        assertEquals(listOf("WDC WD6002FRYZ-01WD5B0", "WD-RDTEST0001", "01.01M02", "PASSED", "30"), listOf("model", "serial", "firmware", "health", "temperature").map { disk[it] })
        val nvme = parseSmart(
            "/dev/nvme0", "nvme",
            "=== START OF INFORMATION SECTION ===\nModel Number:                       Samsung SSD 970 EVO Plus 1TB\n" +
                "=== START OF SMART DATA SECTION ===\nSMART overall-health self-assessment test result: PASSED\n\nSMART/Health Information (NVMe Log 0x02)\n" +
                "Critical Warning:                   0x00\nTemperature:                        36 Celsius\nAvailable Spare:                    100%\n",
        )
        assertEquals(listOf("Samsung SSD 970 EVO Plus 1TB", "PASSED", "36"), listOf("model", "health", "temperature").map { nvme[it] })
    }

    @Test
    fun aShortTestGoesToTheDriveWithItsType() = runTest {
        val host = Host(
            *sudo,
            "smartctl -t short -d 'sat' '/dev/sde'" to ok(
                "smartctl 7.5 2025-04-30 r5714 [x86_64-linux-6.8.4-2-pve] (local build)\nCopyright (C) 2002-25, Bruce Allen, Christian Franke, www.smartmontools.org\n\n" +
                    "=== START OF OFFLINE IMMEDIATE AND SELF-TEST SECTION ===\nTesting has begun.\nPlease wait 2 minutes for test to complete.\n",
            ),
            "smartctl -t short" to ExecResult("smartctl 7.5\n\nSmartctl open device: /dev/sdz failed: No such device\n", "", 2),
        )
        val run = assertNotNull(storage(host, "smartctl").getValue("system/storage/smart").run)
        assertEquals(
            "=== START OF OFFLINE IMMEDIATE AND SELF-TEST SECTION ===\nTesting has begun.\nPlease wait 2 minutes for test to complete.",
            run("short-test", mapOf("number" to "/dev/sde -d sat")).single()["output"],
        )
        assertEquals("Smartctl open device: /dev/sdz failed: No such device", assertFailsWith<DeviceException> { run("short-test", mapOf("number" to "/dev/sdz -d sat")) }.message)
        assertFailsWith<DeviceException> { run("short-test", mapOf("number" to "sde")) }
    }
}

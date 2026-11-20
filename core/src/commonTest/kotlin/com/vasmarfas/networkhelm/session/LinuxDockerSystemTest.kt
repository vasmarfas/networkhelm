package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.ssh.ExecResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinuxDockerSystemTest {

    private fun ok(output: String = "") = ExecResult(output, "", 0)

    // root is the login, and the guard keeps its folder and answers after the change
    private fun host(vararg answers: Pair<String, ByteArray>, commands: List<Pair<String, ExecResult>> = emptyList(), tool: String = "docker") = DockerHost(
        listOf("$tool system dial-stdio </dev/null" to ok(), "date +%z" to ok("+0300\n"), "id -u" to ok("0\n"), "B=" to ok("4242 /tmp/networkhelm-docker.k3Jd9a\n"), "true" to ok()) +
            commands,
    ) { DockerEngine(listOf("GET /version " to engineAnswer(VERSION)) + answers) }

    private fun system(host: DockerHost, tool: String = "docker") =
        DockerSystem(host, DockerApi(host, Root(host, null)) { setOf(tool) }, Root(host, null), LinuxGuard(host, Root(host, null), null)).table { true }

    private fun docker(host: DockerHost, tools: Set<String>) = Root(host, null).let { root ->
        LinuxDocker(host, root, { tools }, LinuxGuard(host, root, null), LinuxPackages(host, root, { tools }) {})
    }

    @Test
    fun aLoginInTheGroupDockerIsToldAndRootIsNot() = runTest {
        suspend fun told(id: String) = assertNotNull(system(host(commands = listOf("id -nG" to ok(id)))).run)("group", emptyMap()).single()["docker"]
        assertEquals(listOf("true", "false", "false"), listOf(told("nhlab wheel docker\n1000\n"), told("root docker\n0\n"), told("nhlab wheel\n1000\n")))
    }

    @Test
    fun theEnginesComeWithThePackagesTheirDistributionNamesThem() = runTest {
        suspend fun engines(tools: Set<String>, said: String): Map<String, Item> =
            docker(host(commands = listOf("[ ! -f /etc/os-release ]" to ok(said))), tools).tables.getValue("container-engine").read().associateBy { it.id }
        val fedora = engines(setOf("podman"), "--nh-os-- fedora \n--nh-podman-- podman version 5.8.9\n--nh-compose-- podman-compose version 1.6.0\n")
        assertEquals(listOf("false", "moby-engine"), listOf("installed", "packages").map { fedora.getValue("docker")[it] })
        assertEquals(listOf("true", "podman version 5.8.9"), listOf("installed", "version").map { fedora.getValue("podman")[it] })
        assertEquals(listOf("true", "podman-compose"), listOf("installed", "packages").map { fedora.getValue("compose")[it] })
        assertEquals("docker-ce", engines(emptySet(), "--nh-os-- rocky rhel centos fedora\n").getValue("docker")["packages"])
        // Fedora 44 of the lab with podman-docker: docker answers as podman, and podman compose names Podman before podman-compose
        val wrapped = engines(
            setOf("docker", "podman"),
            "--nh-os-- fedora\n--nh-docker-- podman version 5.8.9\n--nh-podman-- podman version 5.8.9\n--nh-compose-- podman version 5.8.9\n--nh-compose-- podman-compose version 1.6.0\n",
        )
        assertEquals(listOf("Docker (podman-docker)", "false", ""), listOf("name", "installed", "packages").map { wrapped.getValue("docker")[it] })
        assertEquals(listOf("podman-compose version 1.6.0", "podman-compose"), listOf("version", "packages").map { wrapped.getValue("compose")[it] })
        val ubuntu = engines(emptySet(), "--nh-os-- ubuntu debian\n")
        assertEquals(listOf("docker.io", "docker-compose-v2", "false"), listOf(ubuntu.getValue("docker")["packages"], ubuntu.getValue("compose")["packages"], ubuntu.getValue("compose")["installed"]))
        assertEquals(listOf("docker", "podman", "compose"), ubuntu.keys.toList())
        // Ubuntu 22.04 with docker.io and docker-compose 1.29.2: Compose v2 is offered, and v1 keeps a row of its own with nothing to install
        val v1 = engines(setOf("docker"), UBUNTU_COMPOSE_V1)
        assertEquals(listOf("false", "", "docker-compose-v2"), listOf("installed", "version", "packages").map { v1.getValue("compose")[it] })
        assertEquals(
            listOf("Compose v1", "true", "docker-compose version 1.29.2, build unknown", ""),
            listOf("name", "installed", "version", "packages").map { v1.getValue("compose-v1")[it] },
        )
    }

    @Test
    fun installPutsInWhatTheRowsChosenNameAndStartsADockerThatWasNotThere() = runTest {
        // a host without an engine: Docker and Compose v2 in one run of apt, then Docker started and enabled
        val fresh = host(
            commands = listOf("[ ! -f /etc/os-release ]" to ok("--nh-os-- ubuntu debian\n"), "DEBIAN_FRONTEND=" to ok("apt said\n"), "if command -v systemctl " to ok("systemctl said\n")),
        )
        val install = assertNotNull(docker(fresh, setOf("apt-get", "systemctl")).tables.getValue("container-engine").run)
        assertEquals("apt said\nsystemctl said", install("install", mapOf("numbers" to "docker,compose")).single()["output"])
        assertEquals(
            listOf("DEBIAN_FRONTEND=noninteractive apt-get -y -o DPkg::Lock::Timeout=60 install 'docker.io' 'docker-compose-v2' 2>&1"),
            fresh.ran.filter { it.startsWith("DEBIAN_FRONTEND=") },
        )
        assertTrue(fresh.ran.last().startsWith("if command -v systemctl >/dev/null 2>&1; then systemctl enable --now docker; else rc-update add docker default"))

        // Docker that is there already is left as it runs; the rows of Compose v1 and of podman-docker have nothing to install
        val vps = host(commands = listOf("[ ! -f /etc/os-release ]" to ok(UBUNTU_COMPOSE_V1), "DEBIAN_FRONTEND=" to ok("apt said\n")))
        val again = assertNotNull(docker(vps, setOf("apt-get", "docker")).tables.getValue("container-engine").run)
        assertEquals("apt said", again("install", mapOf("numbers" to "docker,compose")).single()["output"])
        assertTrue(vps.ran.single { it.startsWith("DEBIAN_FRONTEND=") }.endsWith(" install 'docker.io' 'docker-compose-v2' 2>&1"))
        assertTrue(vps.ran.none { it.startsWith("if command -v systemctl") })
        val refused = assertFailsWith<DeviceException> { again("install", mapOf("numbers" to "compose-v1")) }
        assertEquals(ErrorCategory.ArgumentValue to "the rows chosen have nothing to install", refused.category to refused.message)
        val wrapped = host(commands = listOf("[ ! -f /etc/os-release ]" to ok("--nh-os-- fedora\n--nh-docker-- podman version 5.8.9\n--nh-podman-- podman version 5.8.9\n")))
        assertFailsWith<DeviceException> { assertNotNull(docker(wrapped, setOf("dnf", "docker", "podman")).tables.getValue("container-engine").run)("install", mapOf("numbers" to "docker")) }
        assertTrue(wrapped.ran.none { it.startsWith("dnf ") })
    }

    @Test
    fun installOfTheOverviewIsACommandOfItsTable() {
        val commands = docker(host(), emptySet()).tables.getValue("container-engine").commands
        val actions = bundledPacks().getValue("ssh-docker-engines.yaml").paths.getValue("container-engine").actions.map { it.command }
        assertEquals(listOf("install"), actions)
        assertEquals(emptyList(), actions.filterNot { it in commands })
    }

    @Test
    fun theEngineIsWhatDockerVersionAndDockerInfoTell() = runTest {
        fun values(version: String, info: String) =
            engineRows(Json.parseToJsonElement(version).jsonObject, Json.parseToJsonElement(info).jsonObject).associate { it.id to it["value"] }
        val keys = listOf("version", "api-version", "os", "storage-driver", "cpus", "memory", "running", "images", "root-dir", "swarm", "rootless", "host")
        assertEquals(
            listOf("29.9.3", "1.56", "Arch Linux", "overlayfs", "2", "1073741824", "2", "2", "/var/lib/docker", "inactive", "false", "nh-arch"),
            keys.map { values(ENGINE_VERSION_29, ENGINE_INFO_29)[it] },
        )
        assertEquals(
            listOf("5.8.9", "1.44", "fedora", "overlay", "2", "1073741824", "3", "1", "/var/lib/containers/storage", "inactive", "false", "nh-fedora44"),
            keys.map { values(ENGINE_VERSION_PODMAN_5, ENGINE_INFO_PODMAN_5)[it] },
        )
        val rows = system(host("GET /v1.56/version " to engineAnswer(ENGINE_VERSION_29), "GET /v1.56/info " to engineAnswer(ENGINE_INFO_29))).read()
        assertEquals("29.9.3", rows.first { it.id == "version" }["value"])
    }

    @Test
    fun theDiskIsCountedAsDockerSystemDfCountsIt() = runTest {
        val rows = assertNotNull(system(host("GET /v1.56/system/df " to engineChunked(DF_29))).run)("disk", emptyMap())
        assertEquals(
            listOf(
                listOf("images", "2", "1", "13076875", "25874"),
                listOf("containers", "2", "2", "12288", "0"),
                listOf("volumes", "1", "1", "0", "0"),
                listOf("build-cache", "0", "0", "0", "0"),
            ),
            rows.map { row -> listOf("kind", "count", "active", "size", "reclaimable").map { row[it] } },
        )
    }

    @Test
    fun theEventsOfThePastComeBeforeAnEmptyRowAndTheLiveOnesAfterIt() = runTest {
        val clock = "date +%s" to ok("1794936140\n")
        // Docker ends the past at the time of the host, and the live read starts there
        val docker = host(
            "GET /v1.56/events?since=1794932540&until=1794936140 " to engineStream(EVENTS_29.take(1), "\n"),
            "GET /v1.56/events?since=1794936140 " to engineStream(EVENTS_29.drop(1), "\n"),
            commands = listOf(clock),
        )
        val events = assertNotNull(system(docker).streams["events"])(mapOf("period" to "3600")).toList()
        assertEquals(listOf("kill", "", "kill", "disconnect", "stop", "die", "connect", "start", "restart"), events.map { it["action"].orEmpty() })
        assertTrue(events[1].values.isEmpty())
        assertEquals(listOf("container", "kill", "nh-sample-tty", "2026-11-17 20:22:19"), listOf("type", "action", "name", "time").map { events.first()[it] })
        // without a period the empty row comes first
        val live = assertNotNull(system(host("GET /v1.56/events?since=1794936140 " to engineStream(EVENTS_29, "\n"), commands = listOf(clock))).streams["events"])(emptyMap()).toList()
        assertEquals(listOf(true) + List(EVENTS_29.size) { false }, live.map { it.values.isEmpty() })

        // Podman reads the past to its end without following, past the start of the live read: what both give comes once
        val podman = host(
            "GET /v1.56/events?since=1794849740&stream=false " to engineStream(EVENTS_29.take(5), "\n"),
            "GET /v1.56/events?since=1794936140 " to engineStream(EVENTS_29.drop(3), "\n"),
            commands = listOf(clock),
            tool = "podman",
        )
        val read = assertNotNull(system(podman, "podman").streams["events"])(mapOf("period" to "86400")).toList()
        assertEquals(listOf("kill", "kill", "disconnect", "stop", "die", "", "connect", "start", "restart"), read.map { it["action"].orEmpty() })
    }

    @Test
    fun daemonJsonIsCheckedByDockerdAndDockerStartsWithItUnderTheGuard() = runTest {
        val text = """{"log-driver":"local"}"""
        val good = host(
            "GET /v1.56/info " to engineAnswer(ENGINE_INFO_29),
            commands = listOf(
                "cat /etc/docker/daemon.json 2>/dev/null" to ok("{}\n"), "mkdir -p /etc/docker && cat > " to ok(), "dockerd --validate " to ok("configuration OK\n"),
                "cat /etc/docker/daemon.json.networkhelm > " to ok(),
            ),
        )
        val run = assertNotNull(system(good).run)
        assertEquals("{}\n", run("daemon", emptyMap()).single()["text"])
        run("save-daemon", mapOf("text" to text))
        assertEquals(listOf("mkdir -p /etc/docker && cat > /etc/docker/daemon.json.networkhelm" to text), good.given)
        // the old file is kept first, and the guard puts it back and starts Docker again by itself unless the session answers
        val armed = good.ran.single { it.startsWith("B=") }
        assertTrue("[ ! -f /etc/docker/daemon.json ] || cp -p /etc/docker/daemon.json \"\$B/daemon.json\"" in armed)
        assertTrue("cp -p \"\$1/daemon.json\" /etc/docker/daemon.json" in armed)
        val changed = good.ran.single { it.startsWith("cat /etc/docker/daemon.json.networkhelm > ") }
        assertTrue(changed.startsWith("cat /etc/docker/daemon.json.networkhelm > /etc/docker/daemon.json && rm -f /etc/docker/daemon.json.networkhelm && if command -v systemctl"))
        assertTrue(changed.endsWith("sleep 1; done; }"))
        assertTrue(good.ran.any { it.startsWith("kill 4242") })

        val wrong = "unable to configure the Docker daemon with file /etc/docker/daemon.json.networkhelm: the following directives don't match any configuration option: log-drivr"
        val bad = host("GET /v1.56/info " to engineAnswer(ENGINE_INFO_29), commands = listOf("mkdir -p /etc/docker && cat > " to ok(), "dockerd --validate " to ExecResult("", "$wrong\n", 1)))
        val refused = assertFailsWith<DeviceException> { assertNotNull(system(bad).run)("save-daemon", mapOf("text" to """{"log-drivr":"local"}""")) }
        assertEquals(ErrorCategory.ArgumentValue to wrong, refused.category to refused.message)
        assertEquals("rm -f /etc/docker/daemon.json.networkhelm", bad.ran.last())
        assertTrue(bad.ran.none { it.startsWith("B=") })

        val podman = host(tool = "podman")
        assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { assertNotNull(system(podman, "podman").run)("daemon", emptyMap()) }.category)
    }

    @Test
    fun rootlessDockerKeepsItsOwnDaemonJsonAndTheOneOfTheSystemIsLeftAlone() = runTest {
        val info = ENGINE_INFO_29.replace(""""SecurityOptions":["name=seccomp,profile=builtin",""", """"SecurityOptions":["name=seccomp,profile=builtin","name=rootless",""")
        val host = host("GET /v1.56/info " to engineAnswer(info))
        val run = assertNotNull(system(host).run)
        for (command in listOf("daemon", "save-daemon")) {
            val refused = assertFailsWith<DeviceException> { run(command, mapOf("text" to """{"log-driver":"local"}""")) }
            assertEquals(ErrorCategory.Unsupported, refused.category)
            assertTrue(refused.message.orEmpty().startsWith("rootless Docker reads ~/.config/docker/daemon.json of the login"))
        }
        assertTrue(host.given.isEmpty())
        assertTrue(host.ran.none { "/etc/docker" in it || it.startsWith("B=") })
    }

    private companion object {
        const val VERSION = """{"ApiVersion":"1.56","MinAPIVersion":"1.40"}"""

        // what the login reads on Ubuntu 22.04 with docker.io 26.1.3 and the docker-compose 1.29.2 of the distribution
        const val UBUNTU_COMPOSE_V1 = "--nh-os-- ubuntu debian\n--nh-docker-- Docker version 26.1.3, build 26.1.3-0ubuntu1~22.04.1\n" +
            "--nh-compose-- docker-compose version 1.29.2, build unknown\n--nh-compose-- docker-py version: 5.0.3\n--nh-compose-- CPython version: 3.10.12\n" +
            "--nh-compose-- OpenSSL version: OpenSSL 3.0.2 15 Mar 2022\n"

        // recorded on Docker 29.9.3 (API 1.56) on Arch and on Podman 5.8.9 (compatible API 1.44) on Fedora 44
        const val ENGINE_VERSION_29 = """{"Platform":{"Name":""},"Version":"29.9.3","ApiVersion":"1.56","MinAPIVersion":"1.40","Os":"linux","Arch":"amd64","Components":[{"Name":"Engine",""" +
            """"Version":"29.9.3","Details":{"ApiVersion":"1.56","Arch":"amd64","BuildTime":"2026-11-04T13:44:59.000000000+00:00","Experimental":"false","GitCommit":"70efc0e31d",""" +
            """"GoVersion":"go1.27.3-X:nodwarf5","KernelVersion":"6.8.4-2-pve","MinAPIVersion":"1.40","Module":"github.com/moby/moby/v2","ModuleVersion":"v2.0.0+unknown",""" +
            """"Os":"linux"}},{"Name":"containerd","Version":"v2.4.2","Details":{"GitCommit":"b7a25261e029d68978bbf290df0a62fc819b3c51.m"}},{"Name":"runc",""" +
            """"Version":"1.5.3","Details":{"GitCommit":""}},{"Name":"docker-init","Version":"0.19.0","Details":{"GitCommit":"de40ad0"}}],"GitCommit":"70efc0e31d",""" +
            """"GoVersion":"go1.27.3-X:nodwarf5","KernelVersion":"6.8.4-2-pve","BuildTime":"2026-11-04T13:44:59.000000000+00:00"}"""
        const val ENGINE_INFO_29 = """{"ID":"12b9fce9-ce95-452a-941f-611150268cdf","Containers":2,"ContainersRunning":2,"ContainersPaused":0,"ContainersStopped":0,"Images":2,"Driver":"overlayfs",""" +
            """"DriverStatus":[["driver-type","io.containerd.snapshotter.v1"]],"Plugins":{"Volume":["local"],"Network":["bridge","host","ipvlan","macvlan",""" +
            """"null","overlay"],"Authorization":null,"Log":["awslogs","fluentd","gcplogs","gelf","journald","json-file","local","splunk","syslog"]},"MemoryLimit":true,""" +
            """"SwapLimit":true,"CpuCfsPeriod":true,"CpuCfsQuota":true,"CPUShares":true,"CPUSet":true,"PidsLimit":true,"IPv4Forwarding":true,"Debug":false,""" +
            """"NFd":46,"OomKillDisable":false,"NGoroutines":76,"SystemTime":"2026-11-17T17:21:13.056174085Z","LoggingDriver":"json-file","CgroupDriver":"systemd",""" +
            """"CgroupVersion":"2","NEventsListener":0,"KernelVersion":"6.8.4-2-pve","OperatingSystem":"Arch Linux","OSVersion":"","OSType":"linux","Architecture":"x86_64",""" +
            """"IndexServerAddress":"https://index.docker.io/v1/","RegistryConfig":{"InsecureRegistryCIDRs":["127.0.0.0/8","::1/128"],"IndexConfigs":{"docker.io":{"Name":"docker.io",""" +
            """"Mirrors":[],"Secure":true,"Official":true}},"Mirrors":[]},"NCPU":2,"MemTotal":1073741824,"GenericResources":null,"DockerRootDir":"/var/lib/docker",""" +
            """"HttpProxy":"","HttpsProxy":"","NoProxy":"","Name":"nh-arch","Labels":[],"ExperimentalBuild":false,"ServerVersion":"29.9.3","Runtimes":{"io.containerd.runc.v2":{"path":"runc",""" +
            """"status":{"org.opencontainers.runtime-spec.features":"{\"ociVersionMin\":\"1.0.0\",\"ociVersionMax\":\"1.3.0\",\"hooks\":[\"prestart\",\"createRuntime\",""" +
            """\"createContainer\",\"startContainer\",\"poststart\",\"poststop\"],\"mountOptions\":[\"async\",\"atime\",\"bind\",\"defaults\",\"dev\",\"diratime\",""" +
            """\"dirsync\",\"exec\",\"iversion\",\"lazytime\",\"loud\",\"mand\",\"noatime\",\"nodev\",\"nodiratime\",\"noexec\",\"noiversion\",\"nolazytime\",""" +
            """\"nomand\",\"norelatime\",\"nostrictatime\",\"nosuid\",\"nosymfollow\",\"private\",\"ratime\",\"rbind\",\"rdev\",\"rdiratime\",\"relatime\",""" +
            """\"remount\",\"rexec\",\"rnoatime\",\"rnodev\",\"rnodiratime\",\"rnoexec\",\"rnorelatime\",\"rnostrictatime\",\"rnosuid\",\"rnosymfollow\",\"ro\",""" +
            """\"rprivate\",\"rrelatime\",\"rro\",\"rrw\",\"rshared\",\"rslave\",\"rstrictatime\",\"rsuid\",\"rsymfollow\",\"runbindable\",\"rw\",\"shared\",""" +
            """\"silent\",\"slave\",\"strictatime\",\"suid\",\"symfollow\",\"sync\",\"tmpcopyup\",\"unbindable\"],\"linux\":{\"namespaces\":[\"cgroup\",\"ipc\",""" +
            """\"mount\",\"network\",\"pid\",\"time\",\"user\",\"uts\"],\"capabilities\":[\"CAP_CHOWN\",\"CAP_DAC_OVERRIDE\",\"CAP_DAC_READ_SEARCH\",\"CAP_FOWNER\",""" +
            """\"CAP_FSETID\",\"CAP_KILL\",\"CAP_SETGID\",\"CAP_SETUID\",\"CAP_SETPCAP\",\"CAP_LINUX_IMMUTABLE\",\"CAP_NET_BIND_SERVICE\",\"CAP_NET_BROADCAST\",""" +
            """\"CAP_NET_ADMIN\",\"CAP_NET_RAW\",\"CAP_IPC_LOCK\",\"CAP_IPC_OWNER\",\"CAP_SYS_MODULE\",\"CAP_SYS_RAWIO\",\"CAP_SYS_CHROOT\",\"CAP_SYS_PTRACE\",""" +
            """\"CAP_SYS_PACCT\",\"CAP_SYS_ADMIN\",\"CAP_SYS_BOOT\",\"CAP_SYS_NICE\",\"CAP_SYS_RESOURCE\",\"CAP_SYS_TIME\",\"CAP_SYS_TTY_CONFIG\",\"CAP_MKNOD\",""" +
            """\"CAP_LEASE\",\"CAP_AUDIT_WRITE\",\"CAP_AUDIT_CONTROL\",\"CAP_SETFCAP\",\"CAP_MAC_OVERRIDE\",\"CAP_MAC_ADMIN\",\"CAP_SYSLOG\",\"CAP_WAKE_ALARM\",""" +
            """\"CAP_BLOCK_SUSPEND\",\"CAP_AUDIT_READ\",\"CAP_PERFMON\",\"CAP_BPF\",\"CAP_CHECKPOINT_RESTORE\"],\"cgroup\":{\"v1\":true,\"v2\":true,\"systemd\":true,""" +
            """\"systemdUser\":true,\"rdma\":true},\"seccomp\":{\"enabled\":true,\"actions\":[\"SCMP_ACT_ALLOW\",\"SCMP_ACT_ERRNO\",\"SCMP_ACT_KILL\",\"SCMP_ACT_KILL_PROCESS\",""" +
            """\"SCMP_ACT_KILL_THREAD\",\"SCMP_ACT_LOG\",\"SCMP_ACT_NOTIFY\",\"SCMP_ACT_TRACE\",\"SCMP_ACT_TRAP\"],\"operators\":[\"SCMP_CMP_EQ\",\"SCMP_CMP_GE\",""" +
            """\"SCMP_CMP_GT\",\"SCMP_CMP_LE\",\"SCMP_CMP_LT\",\"SCMP_CMP_MASKED_EQ\",\"SCMP_CMP_NE\"],\"archs\":[\"SCMP_ARCH_AARCH64\",\"SCMP_ARCH_ARM\",\"SCMP_ARCH_LOONGARCH64\",""" +
            """\"SCMP_ARCH_MIPS\",\"SCMP_ARCH_MIPS64\",\"SCMP_ARCH_MIPS64N32\",\"SCMP_ARCH_MIPSEL\",\"SCMP_ARCH_MIPSEL64\",\"SCMP_ARCH_MIPSEL64N32\",\"SCMP_ARCH_PPC\",""" +
            """\"SCMP_ARCH_PPC64\",\"SCMP_ARCH_PPC64LE\",\"SCMP_ARCH_RISCV64\",\"SCMP_ARCH_S390\",\"SCMP_ARCH_S390X\",\"SCMP_ARCH_X32\",\"SCMP_ARCH_X86\",\"SCMP_ARCH_X86_64\"],""" +
            """\"knownFlags\":[\"SECCOMP_FILTER_FLAG_TSYNC\",\"SECCOMP_FILTER_FLAG_SPEC_ALLOW\",\"SECCOMP_FILTER_FLAG_LOG\",\"SECCOMP_FILTER_FLAG_WAIT_KILLABLE_RECV\"],""" +
            """\"supportedFlags\":[\"SECCOMP_FILTER_FLAG_TSYNC\",\"SECCOMP_FILTER_FLAG_SPEC_ALLOW\",\"SECCOMP_FILTER_FLAG_LOG\",\"SECCOMP_FILTER_FLAG_WAIT_KILLABLE_RECV\"]},""" +
            """\"apparmor\":{\"enabled\":true},\"selinux\":{\"enabled\":true},\"intelRdt\":{\"enabled\":true,\"schemata\":true,\"monitoring\":true},\"memoryPolicy\":{\"modes\":[\"MPOL_BIND\",""" +
            """\"MPOL_DEFAULT\",\"MPOL_INTERLEAVE\",\"MPOL_LOCAL\",\"MPOL_PREFERRED\",\"MPOL_PREFERRED_MANY\",\"MPOL_WEIGHTED_INTERLEAVE\"],\"flags\":[\"MPOL_F_NUMA_BALANCING\",""" +
            """\"MPOL_F_RELATIVE_NODES\",\"MPOL_F_STATIC_NODES\"]},\"mountExtensions\":{\"idmap\":{\"enabled\":true}},\"netDevices\":{\"enabled\":true}},\"annotations\":{\"com.cyphar.pathrs.libpathrs.version\":\"0.2.6\",""" +
            """\"io.github.seccomp.libseccomp.version\":\"2.6.0\",\"org.opencontainers.runc.checkpoint.enabled\":\"true\",\"org.opencontainers.runc.commit\":\"\",""" +
            """\"org.opencontainers.runc.version\":\"1.5.3\"},\"potentiallyUnsafeConfigAnnotations\":[\"bundle\",\"org.systemd.property.\",\"org.criu.config\"]}"}},""" +
            """"runc":{"path":"runc","status":{"org.opencontainers.runtime-spec.features":"{\"ociVersionMin\":\"1.0.0\",\"ociVersionMax\":\"1.3.0\",\"hooks\":[\"prestart\",""" +
            """\"createRuntime\",\"createContainer\",\"startContainer\",\"poststart\",\"poststop\"],\"mountOptions\":[\"async\",\"atime\",\"bind\",\"defaults\",""" +
            """\"dev\",\"diratime\",\"dirsync\",\"exec\",\"iversion\",\"lazytime\",\"loud\",\"mand\",\"noatime\",\"nodev\",\"nodiratime\",\"noexec\",\"noiversion\",""" +
            """\"nolazytime\",\"nomand\",\"norelatime\",\"nostrictatime\",\"nosuid\",\"nosymfollow\",\"private\",\"ratime\",\"rbind\",\"rdev\",\"rdiratime\",""" +
            """\"relatime\",\"remount\",\"rexec\",\"rnoatime\",\"rnodev\",\"rnodiratime\",\"rnoexec\",\"rnorelatime\",\"rnostrictatime\",\"rnosuid\",\"rnosymfollow\",""" +
            """\"ro\",\"rprivate\",\"rrelatime\",\"rro\",\"rrw\",\"rshared\",\"rslave\",\"rstrictatime\",\"rsuid\",\"rsymfollow\",\"runbindable\",\"rw\",\"shared\",""" +
            """\"silent\",\"slave\",\"strictatime\",\"suid\",\"symfollow\",\"sync\",\"tmpcopyup\",\"unbindable\"],\"linux\":{\"namespaces\":[\"cgroup\",\"ipc\",""" +
            """\"mount\",\"network\",\"pid\",\"time\",\"user\",\"uts\"],\"capabilities\":[\"CAP_CHOWN\",\"CAP_DAC_OVERRIDE\",\"CAP_DAC_READ_SEARCH\",\"CAP_FOWNER\",""" +
            """\"CAP_FSETID\",\"CAP_KILL\",\"CAP_SETGID\",\"CAP_SETUID\",\"CAP_SETPCAP\",\"CAP_LINUX_IMMUTABLE\",\"CAP_NET_BIND_SERVICE\",\"CAP_NET_BROADCAST\",""" +
            """\"CAP_NET_ADMIN\",\"CAP_NET_RAW\",\"CAP_IPC_LOCK\",\"CAP_IPC_OWNER\",\"CAP_SYS_MODULE\",\"CAP_SYS_RAWIO\",\"CAP_SYS_CHROOT\",\"CAP_SYS_PTRACE\",""" +
            """\"CAP_SYS_PACCT\",\"CAP_SYS_ADMIN\",\"CAP_SYS_BOOT\",\"CAP_SYS_NICE\",\"CAP_SYS_RESOURCE\",\"CAP_SYS_TIME\",\"CAP_SYS_TTY_CONFIG\",\"CAP_MKNOD\",""" +
            """\"CAP_LEASE\",\"CAP_AUDIT_WRITE\",\"CAP_AUDIT_CONTROL\",\"CAP_SETFCAP\",\"CAP_MAC_OVERRIDE\",\"CAP_MAC_ADMIN\",\"CAP_SYSLOG\",\"CAP_WAKE_ALARM\",""" +
            """\"CAP_BLOCK_SUSPEND\",\"CAP_AUDIT_READ\",\"CAP_PERFMON\",\"CAP_BPF\",\"CAP_CHECKPOINT_RESTORE\"],\"cgroup\":{\"v1\":true,\"v2\":true,\"systemd\":true,""" +
            """\"systemdUser\":true,\"rdma\":true},\"seccomp\":{\"enabled\":true,\"actions\":[\"SCMP_ACT_ALLOW\",\"SCMP_ACT_ERRNO\",\"SCMP_ACT_KILL\",\"SCMP_ACT_KILL_PROCESS\",""" +
            """\"SCMP_ACT_KILL_THREAD\",\"SCMP_ACT_LOG\",\"SCMP_ACT_NOTIFY\",\"SCMP_ACT_TRACE\",\"SCMP_ACT_TRAP\"],\"operators\":[\"SCMP_CMP_EQ\",\"SCMP_CMP_GE\",""" +
            """\"SCMP_CMP_GT\",\"SCMP_CMP_LE\",\"SCMP_CMP_LT\",\"SCMP_CMP_MASKED_EQ\",\"SCMP_CMP_NE\"],\"archs\":[\"SCMP_ARCH_AARCH64\",\"SCMP_ARCH_ARM\",\"SCMP_ARCH_LOONGARCH64\",""" +
            """\"SCMP_ARCH_MIPS\",\"SCMP_ARCH_MIPS64\",\"SCMP_ARCH_MIPS64N32\",\"SCMP_ARCH_MIPSEL\",\"SCMP_ARCH_MIPSEL64\",\"SCMP_ARCH_MIPSEL64N32\",\"SCMP_ARCH_PPC\",""" +
            """\"SCMP_ARCH_PPC64\",\"SCMP_ARCH_PPC64LE\",\"SCMP_ARCH_RISCV64\",\"SCMP_ARCH_S390\",\"SCMP_ARCH_S390X\",\"SCMP_ARCH_X32\",\"SCMP_ARCH_X86\",\"SCMP_ARCH_X86_64\"],""" +
            """\"knownFlags\":[\"SECCOMP_FILTER_FLAG_TSYNC\",\"SECCOMP_FILTER_FLAG_SPEC_ALLOW\",\"SECCOMP_FILTER_FLAG_LOG\",\"SECCOMP_FILTER_FLAG_WAIT_KILLABLE_RECV\"],""" +
            """\"supportedFlags\":[\"SECCOMP_FILTER_FLAG_TSYNC\",\"SECCOMP_FILTER_FLAG_SPEC_ALLOW\",\"SECCOMP_FILTER_FLAG_LOG\",\"SECCOMP_FILTER_FLAG_WAIT_KILLABLE_RECV\"]},""" +
            """\"apparmor\":{\"enabled\":true},\"selinux\":{\"enabled\":true},\"intelRdt\":{\"enabled\":true,\"schemata\":true,\"monitoring\":true},\"memoryPolicy\":{\"modes\":[\"MPOL_BIND\",""" +
            """\"MPOL_DEFAULT\",\"MPOL_INTERLEAVE\",\"MPOL_LOCAL\",\"MPOL_PREFERRED\",\"MPOL_PREFERRED_MANY\",\"MPOL_WEIGHTED_INTERLEAVE\"],\"flags\":[\"MPOL_F_NUMA_BALANCING\",""" +
            """\"MPOL_F_RELATIVE_NODES\",\"MPOL_F_STATIC_NODES\"]},\"mountExtensions\":{\"idmap\":{\"enabled\":true}},\"netDevices\":{\"enabled\":true}},\"annotations\":{\"com.cyphar.pathrs.libpathrs.version\":\"0.2.6\",""" +
            """\"io.github.seccomp.libseccomp.version\":\"2.6.0\",\"org.opencontainers.runc.checkpoint.enabled\":\"true\",\"org.opencontainers.runc.commit\":\"\",""" +
            """\"org.opencontainers.runc.version\":\"1.5.3\"},\"potentiallyUnsafeConfigAnnotations\":[\"bundle\",\"org.systemd.property.\",\"org.criu.config\"]}"}}},""" +
            """"DefaultRuntime":"runc","Swarm":{"NodeID":"","NodeAddr":"","LocalNodeState":"inactive","ControlAvailable":false,"Error":"","RemoteManagers":null},""" +
            """"LiveRestoreEnabled":false,"Isolation":"","InitBinary":"docker-init","ContainerdCommit":{"ID":"b7a25261e029d68978bbf290df0a62fc819b3c51.m"},""" +
            """"RuncCommit":{"ID":""},"InitCommit":{"ID":"de40ad0"},"SecurityOptions":["name=seccomp,profile=builtin","name=cgroupns"],"FirewallBackend":{"Driver":"iptables",""" +
            """"Info":[["EnableUserlandProxy","true"],["UserlandProxyPath","/usr/bin/docker-proxy"]]},"CDISpecDirs":["/etc/cdi","/var/run/cdi"],"Containerd":{"Address":"/run/containerd/containerd.sock",""" +
            """"Namespaces":{"Containers":"moby","Plugins":"plugins.moby"}},"Warnings":null}"""
        const val ENGINE_VERSION_PODMAN_5 = """{"Platform":{"Name":"linux/amd64/fedora-44"},"Components":[{"Name":"Podman Engine","Version":"5.8.9","Details":{"APIVersion":"5.8.9","Arch":"amd64",""" +
            """"BuildTime":"2026-10-12T00:00:00Z","Experimental":"false","GitCommit":"40030f4073bd4e1bf4f2fd9df2f6952b4d9d350a","GoVersion":"go1.26.9-X:nodwarf5",""" +
            """"KernelVersion":"6.8.4-2-pve","MinAPIVersion":"4.0.0","Os":"linux"}},{"Name":"Conmon","Version":"conmon version 2.2.1, commit: ","Details":{"Package":"conmon-2.2.1-2.fc44.x86_64"}},""" +
            """{"Name":"OCI Runtime (crun)","Version":"crun version 1.30\ncommit: a6e70f254e509ceac317027c858082cc6da34cf2\nrundir: /run/crun\nspec: 1.0.0\n+SYSTEMD +SELINUX +APPARMOR +CAP +SECCOMP +EBPF +CRIU +LIBKRUN +WASM:wasmedge +JSON_C",""" +
            """"Details":{"Package":"crun-1.30-1.fc44.x86_64"}}],"Version":"5.8.9","ApiVersion":"1.44","MinAPIVersion":"1.24","GitCommit":"40030f4073bd4e1bf4f2fd9df2f6952b4d9d350a",""" +
            """"GoVersion":"go1.26.9-X:nodwarf5","Os":"linux","Arch":"amd64","KernelVersion":"6.8.4-2-pve","BuildTime":"2026-10-12T00:00:00Z"}"""
        const val ENGINE_INFO_PODMAN_5 = """{"ID":"9f41fd68-069f-4e3a-a567-ff03df82ede3","Containers":3,"ContainersRunning":3,"ContainersPaused":0,"ContainersStopped":0,"Images":1,"Driver":"overlay",""" +
            """"DriverStatus":[["Native Overlay Diff","false"],["Using metacopy","false"],["Supports shifting","false"],["Supports volatile","true"],["Backing Filesystem",""" +
            """"extfs"],["Supports d_type","true"]],"Plugins":{"Volume":["local"],"Network":["bridge","macvlan","ipvlan"],"Authorization":null,"Log":["k8s-file",""" +
            """"none","passthrough","journald"]},"MemoryLimit":true,"SwapLimit":false,"CpuCfsPeriod":false,"CpuCfsQuota":false,"CPUShares":false,"CPUSet":true,""" +
            """"PidsLimit":true,"IPv4Forwarding":true,"Debug":false,"NFd":11,"OomKillDisable":false,"NGoroutines":12,"SystemTime":"2026-11-17T17:31:13.355030938Z",""" +
            """"LoggingDriver":"","CgroupDriver":"systemd","NEventsListener":0,"KernelVersion":"6.8.4-2-pve","OperatingSystem":"fedora","OSVersion":"44","OSType":"linux",""" +
            """"Architecture":"amd64","IndexServerAddress":"","RegistryConfig":{"IndexConfigs":{},"InsecureRegistryCIDRs":[],"Mirrors":[]},"NCPU":2,"MemTotal":1073741824,""" +
            """"GenericResources":null,"DockerRootDir":"/var/lib/containers/storage","HttpProxy":"","HttpsProxy":"","NoProxy":"","Name":"nh-fedora44","Labels":null,""" +
            """"ExperimentalBuild":true,"ServerVersion":"5.8.9","Runtimes":{"crun":{"path":"/usr/bin/crun"},"crun-vm":{"path":"/usr/bin/crun-vm"},"crun-wasm":{"path":"/usr/bin/crun-wasm"},""" +
            """"kata":{"path":"/usr/bin/kata-runtime"},"krun":{"path":"/usr/bin/krun"},"ocijail":{"path":"/usr/local/bin/ocijail"},"runc":{"path":"/usr/bin/runc"},""" +
            """"runj":{"path":"/usr/local/bin/runj"},"runsc":{"path":"/usr/bin/runsc"},"youki":{"path":"/usr/local/bin/youki"}},"DefaultRuntime":"crun","Swarm":{"NodeID":"",""" +
            """"NodeAddr":"","LocalNodeState":"inactive","ControlAvailable":false,"Error":"","RemoteManagers":null},"LiveRestoreEnabled":false,"Isolation":"",""" +
            """"InitBinary":"","ContainerdCommit":{"ID":""},"RuncCommit":{"ID":""},"InitCommit":{"ID":""},"SecurityOptions":["name=seccomp,profile=default"],""" +
            """"ProductLicense":"Apache-2.0","DefaultAddressPools":[{"Base":"10.89.0.0/16","Size":24},{"Base":"10.90.0.0/15","Size":24},{"Base":"10.92.0.0/14",""" +
            """"Size":24},{"Base":"10.96.0.0/11","Size":24},{"Base":"10.128.0.0/9","Size":24}],"CDISpecDirs":null,"Warnings":[],"BuildahVersion":"1.43.5","CPURealtimePeriod":false,""" +
            """"CPURealtimeRuntime":false,"CgroupVersion":"2","Rootless":false,"SwapFree":536870912,"SwapTotal":536870912,"Uptime":"0h 7m 7.00s"}"""
        const val DF_29 = """{"LayersSize":13076875,"Images":[{"Containers":2,"Created":1793901628,"Id":"sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf",""" +
            """"Labels":{},"ParentId":"","Descriptor":{"mediaType":"application/vnd.oci.image.index.v1+json","digest":"sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf",""" +
            """"size":9218},"RepoDigests":["alpine@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf"],"RepoTags":["alpine:latest"],"SharedSize":0,""" +
            """"Size":13030721},{"Containers":0,"Created":1774301639,"Id":"sha256:1e4c05b897a751332ce85c7e9449da2d93514ea2dea6dbb0e2753c9e949b2bfa","Labels":{},""" +
            """"ParentId":"","Descriptor":{"mediaType":"application/vnd.oci.image.index.v1+json","digest":"sha256:1e4c05b897a751332ce85c7e9449da2d93514ea2dea6dbb0e2753c9e949b2bfa",""" +
            """"size":11062},"RepoDigests":["hello-world@sha256:1e4c05b897a751332ce85c7e9449da2d93514ea2dea6dbb0e2753c9e949b2bfa"],"RepoTags":["hello-world:latest"],""" +
            """"SharedSize":0,"Size":25874}],"Containers":[{"Id":"5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a","Names":["/nh-sample-tty"],""" +
            """"Image":"alpine:latest","ImageID":"sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf","Command":"sh -c 'while true; do echo tty $(date +%s); sleep 1; done'",""" +
            """"Created":1794936065,"Ports":[],"SizeRw":4096,"SizeRootFs":9113600,"Labels":{},"State":"running","Status":"Up 18 seconds","HostConfig":{"NetworkMode":"bridge"},""" +
            """"Health":{"Status":"none","FailingStreak":0},"NetworkSettings":{"Networks":{"bridge":{"IPAMConfig":null,"Links":null,"Aliases":null,"DriverOpts":null,""" +
            """"GwPriority":0,"NetworkID":"64d3a304e07732e9934cf31e994e28051b8311bd827019c939a65f58cf9f6196","EndpointID":"c2808a11833bce93e4ff3f8a9d93905886dc66120775458a1395a88382a77f75",""" +
            """"Gateway":"172.17.0.1","IPAddress":"172.17.0.2","MacAddress":"26:c7:5f:64:ac:f5","IPPrefixLen":16,"IPv6Gateway":"","GlobalIPv6Address":"","GlobalIPv6PrefixLen":0,""" +
            """"DNSNames":null}}},"Mounts":[]},{"Id":"87deced11966e779b7967cbd3cb91973f526454a3aeeb5914eb59e069bd6c59d","Names":["/nh-sample-web"],"Image":"alpine:latest",""" +
            """"ImageID":"sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf","Command":"sh -c 'while true; do echo out $(date +%s); echo err >&2; sleep 1; done'",""" +
            """"Created":1794936064,"Ports":[{"IP":"0.0.0.0","PrivatePort":80,"PublicPort":18080,"Type":"tcp"},{"IP":"::","PrivatePort":80,"PublicPort":18080,""" +
            """"Type":"tcp"}],"SizeRw":8192,"SizeRootFs":9117696,"Labels":{"nh.sample":"1"},"State":"running","Status":"Up 19 seconds","HostConfig":{"NetworkMode":"nh-sample-net"},""" +
            """"Health":{"Status":"none","FailingStreak":0},"NetworkSettings":{"Networks":{"nh-sample-net":{"IPAMConfig":null,"Links":null,"Aliases":null,"DriverOpts":null,""" +
            """"GwPriority":0,"NetworkID":"2ad1893d6f4bcfd3e5ad97f97591af44ddc5c6fa845e7f3e9fcba8879d1fe978","EndpointID":"044878b019dc1abd9be1bd312d8223807bfd136558d3e2cb685c66c2385a1ace",""" +
            """"Gateway":"172.30.9.1","IPAddress":"172.30.9.2","MacAddress":"1e:19:12:be:79:c6","IPPrefixLen":24,"IPv6Gateway":"","GlobalIPv6Address":"","GlobalIPv6PrefixLen":0,""" +
            """"DNSNames":null}}},"Mounts":[{"Type":"volume","Name":"nh-sample-vol","Source":"/var/lib/docker/volumes/nh-sample-vol/_data","Destination":"/data",""" +
            """"Driver":"local","Mode":"z","RW":true,"Propagation":""}]}],"Volumes":[{"CreatedAt":"2026-11-17T17:20:59Z","Driver":"local","Labels":null,"Mountpoint":"/var/lib/docker/volumes/nh-sample-vol/_data",""" +
            """"Name":"nh-sample-vol","Options":null,"Scope":"local","UsageData":{"RefCount":1,"Size":0}}],"BuildCache":[],"ImageUsage":{"ActiveCount":1,"Items":[{"Containers":2,""" +
            """"Created":1793901628,"Id":"sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf","Labels":{},"ParentId":"","Descriptor":{"mediaType":"application/vnd.oci.image.index.v1+json",""" +
            """"digest":"sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf","size":9218},"RepoDigests":["alpine@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf"],""" +
            """"RepoTags":["alpine:latest"],"SharedSize":0,"Size":13030721},{"Containers":0,"Created":1774301639,"Id":"sha256:1e4c05b897a751332ce85c7e9449da2d93514ea2dea6dbb0e2753c9e949b2bfa",""" +
            """"Labels":{},"ParentId":"","Descriptor":{"mediaType":"application/vnd.oci.image.index.v1+json","digest":"sha256:1e4c05b897a751332ce85c7e9449da2d93514ea2dea6dbb0e2753c9e949b2bfa",""" +
            """"size":11062},"RepoDigests":["hello-world@sha256:1e4c05b897a751332ce85c7e9449da2d93514ea2dea6dbb0e2753c9e949b2bfa"],"RepoTags":["hello-world:latest"],""" +
            """"SharedSize":0,"Size":25874}],"Reclaimable":25874,"TotalCount":2,"TotalSize":13076875},"ContainerUsage":{"ActiveCount":2,"Items":[{"Id":"5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a",""" +
            """"Names":["/nh-sample-tty"],"Image":"alpine:latest","ImageID":"sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf","Command":"sh -c 'while true; do echo tty $(date +%s); sleep 1; done'",""" +
            """"Created":1794936065,"Ports":[],"SizeRw":4096,"SizeRootFs":9113600,"Labels":{},"State":"running","Status":"Up 18 seconds","HostConfig":{"NetworkMode":"bridge"},""" +
            """"Health":{"Status":"none","FailingStreak":0},"NetworkSettings":{"Networks":{"bridge":{"IPAMConfig":null,"Links":null,"Aliases":null,"DriverOpts":null,""" +
            """"GwPriority":0,"NetworkID":"64d3a304e07732e9934cf31e994e28051b8311bd827019c939a65f58cf9f6196","EndpointID":"c2808a11833bce93e4ff3f8a9d93905886dc66120775458a1395a88382a77f75",""" +
            """"Gateway":"172.17.0.1","IPAddress":"172.17.0.2","MacAddress":"26:c7:5f:64:ac:f5","IPPrefixLen":16,"IPv6Gateway":"","GlobalIPv6Address":"","GlobalIPv6PrefixLen":0,""" +
            """"DNSNames":null}}},"Mounts":[]},{"Id":"87deced11966e779b7967cbd3cb91973f526454a3aeeb5914eb59e069bd6c59d","Names":["/nh-sample-web"],"Image":"alpine:latest",""" +
            """"ImageID":"sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf","Command":"sh -c 'while true; do echo out $(date +%s); echo err >&2; sleep 1; done'",""" +
            """"Created":1794936064,"Ports":[{"IP":"0.0.0.0","PrivatePort":80,"PublicPort":18080,"Type":"tcp"},{"IP":"::","PrivatePort":80,"PublicPort":18080,""" +
            """"Type":"tcp"}],"SizeRw":8192,"SizeRootFs":9117696,"Labels":{"nh.sample":"1"},"State":"running","Status":"Up 19 seconds","HostConfig":{"NetworkMode":"nh-sample-net"},""" +
            """"Health":{"Status":"none","FailingStreak":0},"NetworkSettings":{"Networks":{"nh-sample-net":{"IPAMConfig":null,"Links":null,"Aliases":null,"DriverOpts":null,""" +
            """"GwPriority":0,"NetworkID":"2ad1893d6f4bcfd3e5ad97f97591af44ddc5c6fa845e7f3e9fcba8879d1fe978","EndpointID":"044878b019dc1abd9be1bd312d8223807bfd136558d3e2cb685c66c2385a1ace",""" +
            """"Gateway":"172.30.9.1","IPAddress":"172.30.9.2","MacAddress":"1e:19:12:be:79:c6","IPPrefixLen":24,"IPv6Gateway":"","GlobalIPv6Address":"","GlobalIPv6PrefixLen":0,""" +
            """"DNSNames":null}}},"Mounts":[{"Type":"volume","Name":"nh-sample-vol","Source":"/var/lib/docker/volumes/nh-sample-vol/_data","Destination":"/data",""" +
            """"Driver":"local","Mode":"z","RW":true,"Propagation":""}]}],"TotalCount":2,"TotalSize":12288},"VolumeUsage":{"ActiveCount":1,"Items":[{"CreatedAt":"2026-11-17T17:20:59Z",""" +
            """"Driver":"local","Labels":null,"Mountpoint":"/var/lib/docker/volumes/nh-sample-vol/_data","Name":"nh-sample-vol","Options":null,"Scope":"local",""" +
            """"UsageData":{"RefCount":1,"Size":0}}],"TotalCount":1},"BuildCacheUsage":{}}"""
        val EVENTS_29 = listOf(
            """{"Type":"container","Action":"kill","Actor":{"ID":"5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a","Attributes":{"image":"alpine:latest",""" +
                """"name":"nh-sample-tty","signal":"15"}},"scope":"local","time":1794936139,"timeNano":1794936139230222874}""",
            """{"Type":"container","Action":"kill","Actor":{"ID":"5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a","Attributes":{"image":"alpine:latest",""" +
                """"name":"nh-sample-tty","signal":"9"}},"scope":"local","time":1794936140,"timeNano":1794936140251185885}""",
            """{"Type":"network","Action":"disconnect","Actor":{"ID":"64d3a304e07732e9934cf31e994e28051b8311bd827019c939a65f58cf9f6196","Attributes":{"container":"5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a",""" +
                """"name":"bridge","type":"bridge"}},"scope":"local","time":1794936140,"timeNano":1794936140435724233}""",
            """{"Type":"container","Action":"stop","Actor":{"ID":"5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a","Attributes":{"image":"alpine:latest",""" +
                """"name":"nh-sample-tty"}},"scope":"local","time":1794936140,"timeNano":1794936140436018165}""",
            """{"Type":"container","Action":"die","Actor":{"ID":"5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a","Attributes":{"execDuration":"42",""" +
                """"exitCode":"137","image":"alpine:latest","name":"nh-sample-tty"}},"scope":"local","time":1794936140,"timeNano":1794936140477929097}""",
            """{"Type":"network","Action":"connect","Actor":{"ID":"64d3a304e07732e9934cf31e994e28051b8311bd827019c939a65f58cf9f6196","Attributes":{"container":"5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a",""" +
                """"name":"bridge","type":"bridge"}},"scope":"local","time":1794936140,"timeNano":1794936140734350141}""",
            """{"Type":"container","Action":"start","Actor":{"ID":"5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a","Attributes":{"image":"alpine:latest",""" +
                """"name":"nh-sample-tty"}},"scope":"local","time":1794936140,"timeNano":1794936140760100779}""",
            """{"Type":"container","Action":"restart","Actor":{"ID":"5b5a4e47a53a3185878759cdc8b0bf67d39839dc78afbb9ee53a87e5c5a11f8a","Attributes":{"image":"alpine:latest",""" +
                """"name":"nh-sample-tty"}},"scope":"local","time":1794936140,"timeNano":1794936140760120475}""",
        )
    }
}

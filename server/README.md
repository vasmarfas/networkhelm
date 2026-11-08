# NetworkHelm in a browser: the gateway

A browser cannot open TCP or SSH connections, so NetworkHelm in a browser reaches routers through a gateway: a small server that keeps the saved routers and their passwords and connects to the routers on behalf of the page. The gateway serves the web app itself, over HTTPS on port 8443, and the page talks to it over a WebSocket of the same address.

**Русский:** [README.ru.md](README.ru.md)

There are five ways to run it: as an archive with its own Java for Windows and Linux, as a jar, in Docker, in a container on a router, and inside the desktop app. An OpenWrt router can also serve the web app itself, without a gateway, and a static host such as GitHub Pages serves a page that has none either, see [app/webApp/README.md](../app/webApp/README.md).

## What it reaches

The gateway connects from its own computer, so the browser gets what the desktop app has there: RouterOS over API, API-SSL, REST, SSH and port 8291 of WinBox, a router by its MAC address (MAC-Telnet), OpenWrt, OPNsense, pfSense, VyOS, Keenetic, and hosts over SSH, SNMP and UPnP; the list of neighbors that MNDP announces, the request that makes the routers announce themselves, and the sweep of addresses for the ports of the protocols. MAC-Telnet and MNDP are broadcasts of the network layer 2, so the computer of the gateway has to stand in the same segment as the routers. In its first frame the gateway tells the page what it offers, and the page shows only that.

## Login

The account is `admin`. Its password is the one in `NETWORKHELM_GATEWAY_PASSWORD` at the first start; without it the gateway makes one up and prints it once. Start it from a terminal for that: a double click on the jar in Windows has no console, and the password is lost. The gateway keeps the password nowhere, so a lost one cannot be read back, but an admin of the computer sets another from the console (below).

The code of an authenticator app (Google Authenticator, Aegis, 2FAS and the like) is asked when the gateway starts with `--code`, and always when it is open to any address (`--public`). Until a login takes a code, the gateway prints its `otpauth://` link at every start, and the login page shows the QR code of it after the right password, so the app is set up from the page itself.

Five failed logins from one address in ten minutes close the login from that address for ten minutes. Every try goes to `logins.log`. A session lasts twelve hours.

Without `--public` the gateway answers only the local networks: 10/8, 172.16/12, 192.168/16, 100.64/10 (CGNAT, Tailscale), IPv6 ULA and link-local, loopback.

## The password from the console

Whoever reaches the console of the computer is its admin, so the account is changed there, by options of the same program, which do it and end without starting the gateway:

| Option | What it does |
| --- | --- |
| `--set-password` | a new password, typed twice without an echo, or the one in `NETWORKHELM_GATEWAY_PASSWORD` where there is no terminal; at least 8 characters. The saved passwords of the routers pass to it when the current password is typed, or is in `NETWORKHELM_GATEWAY_CURRENT_PASSWORD`; Enter at that question, as for a lost one, puts them aside |
| `--reset-password` | a password made up and shown once, for a lost one; the saved passwords of the routers are put aside as `secrets.json.old`, because they are sealed with the old password and cannot be opened without it |
| `--new-code` | a new secret of the authenticator app, as when its phone is lost; the `otpauth://` link is printed, and the login page shows its QR code after the password |

On a computer that has none of the account yet, `--set-password` makes it, so the password can be set before the first start. A gateway that runs takes the change at its next request: the old password stops at once and the sessions of it end, though a page that is open keeps its socket until it is closed.

```bash
java -jar networkhelm-gateway.jar --reset-password
networkhelm-gateway.bat --set-password
sudo env NETWORKHELM_GATEWAY_HOME=/var/lib/networkhelm-gateway /opt/networkhelm-gateway/networkhelm-gateway.sh --reset-password
docker run --rm -it -v networkhelm-gateway:/data networkhelm-gateway --reset-password
```

The first line is for the jar, the second for the archive on Windows, the third for the service of systemd, the fourth for the container; the folder is the one the gateway uses, `NETWORKHELM_GATEWAY_HOME` where it is not the default.

## Certificate

The first start makes a self-signed certificate for ten years, for `localhost` and the name and addresses of the machine. The browser warns about it once; the gateway prints its SHA-256 at every start, and the browser shows the same value in the details of the certificate. Compare them before you trust it.

## Options

| Option | Meaning |
| --- | --- |
| `--host`, `--port` | the address and port to listen on, `0.0.0.0` and `8443` by default; `--host 127.0.0.1` behind a reverse proxy |
| `--public` | answer any address, not only the local networks; the code is then always asked |
| `--code` | ask for the code of an authenticator app |
| `NETWORKHELM_GATEWAY_HOME` | the folder of the gateway, `~/.networkhelm-gateway` by default |
| `NETWORKHELM_GATEWAY_PASSWORD` | the password of the first start |
| `NETWORKHELM_GATEWAY_WEB` | a folder with a build of `:app:webApp` to serve instead of the one inside the jar |

The folder of the gateway holds `gateway.json` (the account), `gateway.jks` (the key of the certificate), `known-routers` (the fingerprints of the routers), `schema/` (what the routers told of their menus), `documents/` and `secrets.json` (what the gateway keeps for the browser; the secrets are sealed with a key that only the password of `admin` gives) and `logins.log`.

## As an archive with its own Java

```bash
./gradlew :server:gatewayArchive
```

makes `server/build/gateway/NetworkHelm-gateway-<version>-<system>.zip` on Windows and `.tar.gz` on Linux and macOS: the jar, a Java runtime that jlink cuts to the modules the jar needs, and the launchers. Nothing else is to be installed; the archive is about 65 MB. The runtime is the one of the JDK that builds it, so each system builds its own archive.

On Windows unpack it and run `networkhelm-gateway.bat`: the window shows the address and the password of the first start once, and closing it stops the gateway. On Linux run `./networkhelm-gateway.sh`, or as a service of systemd:

```bash
sudo mkdir -p /opt/networkhelm-gateway
sudo tar -xzf NetworkHelm-gateway-<version>-linux-x64.tar.gz -C /opt/networkhelm-gateway --strip-components=1
sudo cp /opt/networkhelm-gateway/networkhelm-gateway.service /etc/systemd/system/
sudo systemctl enable --now networkhelm-gateway
sudo journalctl -u networkhelm-gateway
```

The journal holds the password of the first start; the account, the certificate and the saved routers live in `/var/lib/networkhelm-gateway`.

## As a jar

```bash
./gradlew :server:buildFatJar
java -jar server/build/libs/networkhelm-gateway.jar
```

The jar, about 31 MB, needs Java 17 or newer and carries the web app inside.

## In Docker

Every release comes with a ready image for amd64 and arm64 (the `latest` tag follows stable versions only):

```bash
docker run -d --name networkhelm-gateway -p 8443:8443 -v networkhelm-gateway:/data -e NETWORKHELM_GATEWAY_PASSWORD=<password> ghcr.io/vasmarfas/networkhelm-gateway:latest
docker logs networkhelm-gateway
```

To build it yourself:

```bash
./gradlew :server:buildFatJar
docker build -t networkhelm-gateway server
docker run -d --name networkhelm-gateway -p 8443:8443 -v networkhelm-gateway:/data -e NETWORKHELM_GATEWAY_PASSWORD=<password> networkhelm-gateway
docker logs networkhelm-gateway
```

Options go after the name of the image: `docker run ... networkhelm-gateway --code`. The log shows the SHA-256 of the certificate and, with the code, the `otpauth://` link. MNDP and MAC-Telnet need the layer 2 of the network the routers are in, which a container does not see behind its bridge: on Linux run it with `--network host` instead of `-p`, otherwise the page does not offer them.

The image is built on `eclipse-temurin:21-jre` and runs as an unprivileged user; the folder of the gateway is the volume `/data`. It is about 140 MB to download and 280 MB unpacked; the gateway took 130 MB of memory in a container limited to 256 MB. With Docker on Linux the gateway sees the real address of a client from another machine. Docker Desktop on Windows and macOS hands every connection over from the address of its virtual machine, which is a local one, so there the filter of local networks lets in whoever reaches the published port.

For other processors, for example for a router:

```bash
docker buildx build --platform linux/arm64 --output type=docker,dest=networkhelm-gateway-arm64.tar server
```

`linux/arm64`, `linux/arm/v7` and `linux/amd64` are the platforms the base image exists for.

## In a container on a RouterOS router

It takes RouterOS 7 with the `container` package, an arm, arm64 or x86 router with an attached disk for the 280 MB of the unpacked image, and about 150 MB of free memory. arm32v5 routers (hEX Refresh), MIPS and PowerPC do not run it. Before 7.21.2 a container cannot write to its mounts as an ordinary user: there add `user=0` to the container.

1. Allow containers: `/system/device-mode/update container=yes`, then confirm with the reset button or a cold reboot, as the router asks.
2. A network for containers:

   ```
   /interface/veth/add name=veth-networkhelm address=172.17.0.2/24 gateway=172.17.0.1
   /interface/bridge/add name=containers
   /ip/address/add address=172.17.0.1/24 interface=containers
   /interface/bridge/port/add bridge=containers interface=veth-networkhelm
   /ip/firewall/nat/add chain=srcnat action=masquerade src-address=172.17.0.0/24
   ```

3. The password of the first start and the folder of the gateway on the disk:

   ```
   /container/envs/add list=networkhelm key=NETWORKHELM_GATEWAY_PASSWORD value="<password>"
   /container/mounts/add list=networkhelm src=disk1/networkhelm/data dst=/data
   ```

4. Build the image for the processor of the router as in the Docker section, upload the tar to the disk of the router and add the container:

   ```
   /container/add file=disk1/networkhelm-gateway-arm64.tar interface=veth-networkhelm root-dir=disk1/networkhelm/root envlists=networkhelm mountlists=networkhelm logging=yes start-on-boot=yes
   ```

   For the code of an authenticator app add `cmd="--code"`.
5. When `/container/print` shows it stopped (the image is unpacked), start it: `/container/start [find interface=veth-networkhelm]`.
6. Forward the port from the address of the router in the local network, here `192.168.88.1`:

   ```
   /ip/firewall/nat/add chain=dstnat action=dst-nat protocol=tcp dst-address=192.168.88.1 dst-port=8443 to-addresses=172.17.0.2 to-ports=8443
   ```

The log of the router (`/log/print where topics~"container"`) shows what the gateway prints. Then open `https://192.168.88.1:8443`. The gateway reaches the router it runs on at `172.17.0.1`.

## Inside the desktop app

Settings → Access from a browser. The desktop app runs the same gateway inside itself, with the web app in its package: set the password, choose the port, whether to answer any address and whether to ask for a code, and open the access. The window lists the addresses of the computer with a QR code of the first one for a phone, and the SHA-256 of the certificate; while the access is open, the title bar of the window shows its port, and the next start of the app opens it again.

The browser works with the saved routers, the passwords and the known fingerprints of the desktop app. Its own settings, workspaces and table layouts stay apart, in `gateway/` of the folder of the app, along with the account, the certificate and `logins.log`.

## Directly on an OpenWrt router

An OpenWrt router with LuCI (uhttpd with its ubus module, and rpcd) can serve NetworkHelm itself, without a gateway. The page then reaches that router alone, through `/ubus` of the same address, with the login of the router.

```bash
./gradlew :app:webApp:openWrtPackage
scp -O app/webApp/build/openwrt/networkhelm-openwrt-<version>.tar.gz root@192.168.1.1:/tmp/
ssh root@192.168.1.1 'rm -rf /www/networkhelm && tar -xzf /tmp/networkhelm-openwrt-<version>.tar.gz -C / && rm /tmp/networkhelm-openwrt-<version>.tar.gz && /etc/init.d/rpcd reload'
```

Then open `http://192.168.1.1/networkhelm/` or `https://192.168.1.1/networkhelm/`. The package takes 19 MB of the flash, 6 MB to copy: see `df -h /overlay` first; a router with 16 MB of flash in all has no room for it. It also lays `/usr/share/rpcd/acl.d/networkhelm.json`, which gives root over `/ubus` what root has over SSH: every ubus object with its subscriptions, UCI and files; without it the login gets only the ACL of LuCI. A firmware upgrade keeps both when `/www/networkhelm` and the ACL file are listed in `/etc/sysupgrade.conf`.

Such a page has no terminal and no files, which need SSH that a browser cannot open, and no windows of LuCI applications, which need a JavaScript engine of their own. The saved routers and the settings stay in the browser; a password lasts until the page is closed.

# NetworkHelm in a browser: the page without a gateway

The web app comes in two forms. Served by [the gateway](../../server/README.md), it reaches routers through a server that opens TCP, SSH and UDP connections for it. Served by a static host such as GitHub Pages, there is no server behind it: every request goes from the browser itself. This is the second form, built by `./gradlew :app:webApp:pagesBundle` into `app/webApp/build/pages`.

**Русский:** [README.ru.md](README.ru.md)

The page knows which form it is by `<meta name="networkhelm-mode" content="standalone">` in its `index.html`, which only the Pages bundle has. It saves the routers and the settings in the `localStorage` of the browser; a password is kept until the tab is closed. The bundle carries a web app manifest and a service worker, so a browser can install it as an app and open it without a network (the files come from the network while it is there and are kept).

## Demo routers

On the first visit the list of the connection window holds six routers, one for each platform, and a Linux host:

| Host | Platform | Protocol |
| --- | --- | --- |
| `routeros.demo` | MikroTik RouterOS 7.24 | REST |
| `openwrt.demo` | OpenWrt 24.10 | ubus over HTTPS |
| `opnsense.demo` | OPNsense 25.1 | API |
| `pfsense.demo` | pfSense 2.8 | API |
| `vyos.demo` | VyOS 1.4 | HTTP API |
| `keenetic.demo` | Keenetic 5.1 | RCI |
| `linux.demo` | Debian 12 with Proxmox VE 8.2 | SSH |

They exist in the memory of the page, any password opens them, and a new connection starts from the data given in `core/.../demo/`: what one connection changed, the next does not find. Lists take add, set, remove and move; Safe Mode of the RouterOS demo takes the changes back; ping, traceroute and the traffic of an interface answer with rows. The addresses are those of documentation (192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24) and the MAC addresses 00:00:5E:00:53:xx, the range RFC 7042 sets aside for it. The rows of each platform carry the columns of its packs, so the windows look as they do on a real device; menus the demo has no data for are not shown.

The RouterOS demo has rows or settings for every entry of the menu of RouterOS 7.24 on a hAP ax^3 with all optional packages (container, dude, iot, cmr, openflow, netinstall), except the legacy Wireless and CAPsMAN menus, and for every tab of their windows. The buttons that run a command on the router (Reset Counters, Release, Make Static) are shown and do nothing here.

The Linux host opens over SSH although a page cannot speak it: the demo lives in the page itself. It has every window of a Linux host: the dashboard, interfaces, addresses, routes, sockets, services, processes, storage, packages, ufw and nftables, the logs, the VPN servers and clients with the panel of connections, whose sessions count their bytes up, the proxies, Docker and Podman with containers, images, volumes, networks, stacks and pods, and Proxmox VE with its guests, snapshots, backups and tasks. The wizards answer with the files a real host would give; what needs a live SSH channel (the terminal of a container, the console of a guest, file transfers) is not there.

## Real devices

The page sends the HTTP of the drivers through `fetch`, so the REST of RouterOS, `/ubus` of OpenWrt and the APIs of OPNsense, pfSense, VyOS and Keenetic go the way they go from the desktop app. What the browser lets through is another matter, and it does not tell which of the limits it applied:

- **CORS.** A page reads the answer of another origin only if the answer says so. The REST of RouterOS answers without `Access-Control-Allow-Origin`, and the preflight request of the browser (the `Authorization` and `Content-Type: application/json` headers make one) gets `503`. Measured on RouterOS 7.26 on the lab router. `/ubus` of OpenWrt 25.12 answers without the header as well; users report that `option ubus_cors '1'` in the `main` section of `/etc/config/uhttpd` turns it on, which was not tried on the lab. OPNsense, pfSense, VyOS and Keenetic were not measured, there is no such device in the lab. The `allow-origin` setting of the VyOS API is for its GraphQL endpoint, not for the REST calls, as a bug report of VyOS 1.4.0 says. A reverse proxy that answers `OPTIONS` with `204` and puts `Access-Control-Allow-Origin`, `-Headers` and `-Methods` on the answers makes any of these APIs readable.
- **Mixed content.** Pages is served over HTTPS, so a page cannot fetch `http://` addresses; `http://localhost` and `http://127.0.0.1` are the exception.
- **Certificates.** The browser checks the certificate of the device and offers no way to pin it. A self-signed one fails until the same browser has opened the address once and accepted it.
- **Local network.** Recent Chrome asks the user before a public page reaches an address of a private network.

A device the browser does not let through fails with one message that names these causes. The error cannot be told apart further: for `fetch` all of them are the same `TypeError`.

## What a page cannot do at all

These are not HTTP, and a browser sends nothing else, however the page is served:

| Protocol | Needed for |
| --- | --- |
| RouterOS API, API-SSL (8728, 8729) | the binary API of RouterOS |
| TCP 8291 | the protocol of WinBox |
| SSH, SFTP (22) | terminal, files, OpenWrt over SSH, Safe Mode of RouterOS before 7.18 |
| MAC-Telnet, MNDP (UDP) | routers by their MAC address, the list of neighbors |
| SNMP (UDP 161) | devices read over SNMP |
| SSDP (UDP 1900) | finding UPnP devices |

The connection window greys these protocols out on the page. Direct Sockets, the browser API that opens TCP and UDP, exists only for installed Isolated Web Apps of Chrome, not for a page. All of them work through the gateway, which opens them from its own computer; the page of a gateway offers what the gateway says it can.

## Publishing

`.github/workflows/build_web.yml` builds the bundle. The release workflow publishes it on GitHub Pages, except for prereleases, and attaches `NetworkHelm-web-<version>.zip` to the release. That archive is the same bundle: unpacked on any static host over HTTPS (the service worker needs it, `localhost` excepted), it makes the same page. The repository needs Pages set to deploy from GitHub Actions.

The paths of the bundle are relative, so the page works under `https://<user>.github.io/<repository>/`.

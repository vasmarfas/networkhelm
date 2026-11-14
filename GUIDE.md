# Guide

This is what you need to get started: how to install the program, how to connect to a router, what to switch on at the router itself, and what to do when it does not work. There are no descriptions of individual windows. The program reads menus and fields from the router, and the meaning of the settings is explained by the makers; for MikroTik that is the RouterOS documentation. The project as a whole is described in [README.md](README.md).

**Русский:** [GUIDE.ru.md](GUIDE.ru.md)

## Installing

### Windows

Run the installer (`windows-setup.exe`) or the portable file (`windows-portable.exe`, no install needed). If the file has no publisher signature, Windows shows "Windows protected your PC": press "More info", then "Run anyway".

On its first start the portable file unpacks itself into `%LOCALAPPDATA%\NetworkHelm\portable\<version>` (about 200 MB) and keeps its settings in the `NetworkHelm data` folder beside it. If the installer or the portable file says the program does not support this version of Windows, you have Windows 7, 8 or 8.1. There are files marked `legacy` for them.

### macOS

Open the DMG and drag NetworkHelm into Applications. If the program is not signed by Apple, macOS refuses to open it the first time. Open System Settings, Privacy & Security, find the message about NetworkHelm and press "Open Anyway". When macOS asks about access to the local network, allow it: without it the program cannot reach the routers of your network.

### Linux

DEB and RPM go in through the package manager:

```bash
sudo apt install ./NetworkHelm-<version>-linux-amd64.deb
sudo dnf install ./NetworkHelm-<version>-linux-x86_64.rpm
```

The AppImage is not installed: make the file executable (`chmod +x`) and run it. It mounts itself through FUSE and needs `fusermount` from the `fuse3` or `fuse` package; without FUSE, start it with `--appimage-extract-and-run`.

### Android

Download the APK and open it; Android asks for permission to install from that source. On the first connection allow "Nearby devices" for the program. Without it Android keeps the program out of the local network, and instead of a login you see "Android does not let NetworkHelm reach the local network".

### iOS

There is no ready app, only an Xcode project. On a Mac with Xcode run `tools/ios/build-ssh.sh` once, then open `app/iosApp` and build the app for your own device.

The neighbour list, MAC-Telnet and Wake-on-LAN go by broadcasts, which iOS lets an app send and receive only with the multicast entitlement that Apple grants on request. Until the app has it, the Neighbors tab, the login by MAC address and Wake-on-LAN are hidden on iOS. With the entitlement, MAC-Telnet also needs the MAC address of the device, and the connection window asks for it: iOS does not tell it to apps, and the router answers to the address the client names. It is the Wi-Fi Address under Settings → Wi-Fi → (i) next to the network, and with a private address it differs from network to network. The ARP table is closed to apps, so the LAN scanner lists no MAC address and vendor there. The language of the app is set in Settings → NetworkHelm → Language.

## First connection

The connection window opens on start. Its fields:

| Field | What to enter |
| --- | --- |
| Address | the name or IP address of the router; add a port after a colon if it is not the standard one |
| Platform | the type of device; the program recognizes it by itself, and then "recognized" stands beside it |
| Protocol | the way to connect; grey entries are ports that gave no answer |
| Port | change it only if the service on the router listens on a non-standard port |
| User, Password | the account on the router |
| Remember the password | keeps the password in the protected storage of the system |
| Group, Comment | for order in the list of saved routers |
| Via router | connect through the SSH of another saved router, when the one you need cannot be reached directly |

As soon as you type an address, the program checks almost at once which ports of the router answer. It only tries to open a connection and reads the greeting, without logging in; from the answers it recognizes the device and picks a protocol. You can change the choice by hand. A router that guards against password guessing may count such a check as a login attempt and block your address. Then turn the check off in "Settings": the item "Recognize the device and check which protocols answer while the address is typed".

Press "Connect". The "Save" button puts the router in the list on the "Saved" tab, so next time you only pick it there: a click fills the form, a double click connects. The "Neighbors" tab shows the MikroTik routers that announce themselves on your network. They do it every 30 to 60 seconds, and "Refresh" asks them to answer now. On a window 960 dp wide or more the form and the lists stand side by side at the full height of the window; the button at the right of the tabs moves the lists under the form and back. Under the form a bar divides the height between the two: drag it to give one more room, double-click it to give the form its own height back.

If the connection goes over a protocol without encryption (the API on port 8728, HTTP, SNMP v2c), a warning appears under the fields and the main window carries a "Not encrypted" mark. The password and all data then cross the network in the clear, so work like that only on a network you trust.

To open a configuration without a router, press "Open config file" or drop the file onto the connection window: an `/export` of RouterOS, an OpenWrt backup or a UCI file will do.

## Preparing the router

The program reaches a router through services it already has. If the service it needs is off or closed by the firewall, the connection fails and the program says which port gave no answer.

### MikroTik RouterOS

The services are visible in WinBox and in the console: `/ip service print`. Any of these will do:

| Way | Port | Notes |
| --- | --- | --- |
| TCP 8291 (WinBox port) | 8291 | the whole interface works over this one port, so no API, REST or SSH is needed; the program picks it first when the port answers; the login on this port does not work on RouterOS 6.42, pick the API there |
| API-SSL | 8729 | encrypted; needs a certificate: without one RouterOS answers with anonymous TLS, which the program does not use |
| API | 8728 | not encrypted |
| REST over HTTPS and HTTP | 443 and 80 | RouterOS 7.1 and later only, the `www-ssl` and `www` services |
| MAC-Telnet | UDP 20561 | for a router without an IP address; needs its MAC address and a shared layer 2 network, on iOS also the MAC address of the device |

A new MikroTik usually has the login `admin`, with an empty password or one printed on the sticker. If the user group lacks the rights for a menu section, the program marks the section and says which right is missing.

When the password of the user has expired (RouterOS asks for a new one after `/user/expire-password`), the program offers to set it right after the login. "Skip" goes on with the old one, and the router asks again at the next login. The question comes through the console of the router, so it is seen over TCP 8291, SSH and MAC-Telnet, not over the API.

To move to encryption, first connect another way. If the first attempt gives an error about TLS (API-SSL is on and has no certificate), pick "API (8728)" in the "Protocol" list. When the connection opens, click the "Not encrypted" mark in the main window: the "Prepare router" window opens. It has four steps, each of which you can tick or untick, and the commands run when you press Apply:

- issue a self-signed certificate and turn on API-SSL (8729);
- allow the API through an SSH tunnel (`forwarding-enabled=local`);
- add the user group `networkhelm` with the rights the program needs; a separate user for the program can go in it instead of `admin`;
- turn off the plain API (8728) and Telnet; while this connection goes over API 8728 the step is unavailable, since it would cut the connection.

After that connect over API-SSL; the program asks about the fingerprint of the certificate once.

### OpenWrt

Pick the OpenWrt platform. There are two ways:

- SSH, port 22. It needs the root password or a key. On a freshly flashed router you set the password in LuCI, under System, Administration.
- ubus over HTTP (port 80) or HTTPS (port 443). It needs LuCI with the ubus module of the web server and rpcd; you log in as root with its password. Such a login gets what the ACL of LuCI allows. The windows of DHCP leases, ARP and wireless clients answer "Permission denied", the forms show the options without the types and hints that LuCI gives them, and the parts whose config no installed application of LuCI names are left out of the menu. The ACL file of the [OpenWrt package](server/README.md#directly-on-an-openwrt-router) lifts all of that, and so does a login over SSH.

At the top of the main window you can switch the menu view: "OpenWrt view", "Unified view" (the menus the program has for any router) and "LuCI view".

### OPNsense

Create an API key on the router: System, Access, Users, the user you need, API keys. A file with the key and the secret downloads. In the program enter the key in "User" and the secret in "Password". The port is 443.

### pfSense

The package pfSense-pkg-RESTAPI version 2 has to be installed: without it pfSense does not answer the API. There are two ways to log in: leave "User" empty and enter a key from System, REST API, Keys as the password, or give the login and password of a local user.

### VyOS 1.4 and later

Turn on the HTTP API and create a key: `set service https api keys id <name> key <key>`, then `commit` and `save`. In the program the key goes into "Password"; VyOS has no user field. Every edit is applied and saved at once.

### Keenetic and Netcraze

Read only. Enter the login and password of the web interface, or leave the login empty and put an access token of KeeneticOS 5.2 (the section "Users and access") in the password. These drivers are written from the documentation and have not been tried on a real device.

### Other devices

Pick "Other device". SSH is enough for Linux. UPnP and TR-064 find the device on port 1900 (a FRITZ!Box serves its description on port 49000) and are read only.

SNMP v2c reads interfaces, addresses, routes, ARP, neighbours and storage into windows of their own; the community goes in the field "Community", which shows what you type and starts as `public`; there is no user field. SNMP v3 needs a user name and passwords.

After the main menu appears, the program walks the agent the way `snmpwalk` does: the trees mib-2 (1.3.6.1.2.1), enterprises (1.3.6.1.4.1) and the SNMP modules (1.3.6.1.6.3). The walk takes at most 12 seconds and 20 000 objects, reads 20 rows of each column and 500 objects of each subtree it does not know; the windows read the rest when they open. While it runs, the windows of the main menu already work; when it ends, a "MIB" group appears in the menu with one window per MIB module (IF-MIB, Q-BRIDGE-MIB, POWER-ETHERNET-MIB and so on): a tab for each table and a form for each group of single values. What the main menu already shows (interfaces, addresses, routes, ARP, storage, the system name) does not repeat there. Names, types and value lists come from a built-in dictionary of 30 standard modules and the net-snmp modules; values are shown as a person reads them: states by name, times as durations, MAC and IP addresses as such, yes and no. A window of the "MIB" group reads at most 1000 rows of a table and refreshes every 10 seconds.

A subtree the dictionary does not know, a maker's own objects under enterprises for example, shows as a window named after the maker (MikroTik, Cisco, Huawei, ...) with one row per object: OID under the subtree, type and value, at most 2000 rows.

Writing needs a community or a user with write rights. Any column the dictionary marks read-write or read-create can be changed; all the changes of one row go in one request, which the agent applies whole or not at all. Rows are added and removed in tables with a RowStatus column (VLANs of Q-BRIDGE-MIB, SNMP targets, communities, VACM views): the new row needs its index first (for a table numbered by one integer the window proposes the next free number), and the program sends createAndGo, or createAndWait, the values and active to an agent that wants the row built in steps; a row the agent does not accept is removed again. Tables without RowStatus remove rows only where the standard gives a value for it, such as `invalid` of dot1dStaticStatus. A value of an unknown OID can be changed in the type the agent reported; counters are never written. SNMP tables have no order, so rows cannot be moved. There is no rollback like Safe Mode: a wrong change of a VACM, USM or community table can lock the program out of the agent.

## Safety

If a router changes its certificate or its SSH key, the program tells you: it remembers the fingerprints of the certificates and keys you accepted. Protocols without encryption are marked in the connection window and in the main window. "Settings" has "Hide passwords and keys" (they stand as dots in tables, dialogs and the journal, and the eye in a field shows the value) and "Ask before removing from the router".

### Safe Mode

Safe Mode protects against an edit after which the router stops answering. How it works depends on the router.

On RouterOS press Safe Mode in the top bar. While it is on, the router takes the settings back by itself if the connection is lost. The button turns into a menu with a count of edits: "Keep changes" keeps them, "Roll back changes" undoes them. If you close the connection without choosing, the program asks whether to keep the edits.

On VyOS the button turns on commit-confirm for every edit. If the program is left without a connection after an edit, VyOS reboots in about a minute with the configuration saved before it.

On OpenWrt, OPNsense and pfSense the top bar has an Apply button instead of Safe Mode: it puts the edits you made into effect. On OpenWrt the apply goes through `uci apply` with a rollback, and if the connection is lost after it, the router returns to its previous configuration. On OPNsense 24.7 the rules of the firewall are rolled back, and the API of 26.7 has no rollback. pfSense has none at all. Where there is no way back, the program says so.

## In a browser

A browser cannot open connections to routers on its own, so a gateway stands between the two. It keeps the saved routers and their passwords and connects to the routers in place of the page.

**From the desktop app.** "Settings", "Access from a browser…". Set a password (you log in as `admin`), choose a port and press "Open the access". The window shows the addresses of the computer and the fingerprint of the certificate; there is a QR code for a phone. Open the address in the browser of a phone or of another computer on the same network. The browser warns once about the self-signed certificate: compare its SHA-256 fingerprint with the one in the window and go on. While the access is open, its port shows in the title of the window, and the next start of the app opens it again. The box "Any address, not only the local networks" is for access from outside the home network (through a forwarded port or a VPN); the gateway then asks for a code from an authenticator app every time (Google Authenticator, Aegis, 2FAS and the like).

**As a separate gateway.** The archive of the gateway (`NetworkHelm-gateway-<version>-windows-x64.zip` or `-linux-x64.tar.gz`) unpacks and runs without any Java installed: `networkhelm-gateway.bat` on Windows, `./networkhelm-gateway.sh` on Linux. The password of the first start shows once, in the console window or in the log; if you lose it, set a new one with the `--reset-password` option. The gateway also runs as a jar (Java 17 or newer is needed): `java -jar NetworkHelm-gateway-<version>.jar`; the address of the Docker image, if one was published, is in the description of the release. Then open `https://<address of the computer>:8443`. By default the gateway answers local networks only; the `--public` option opens it to any address and makes the code compulsory, and `--code` asks for the code on the local network as well.

**Straight on an OpenWrt router.** A router with LuCI can serve the page itself and reach only itself through `/ubus`; a page served that way has no terminal and no files.

More about the gateway (all options, Docker, a container on a RouterOS router, a systemd service) is in [server/README.md](server/README.md).

**The demo page.** https://vasmarfas.github.io/networkhelm/ shows the interface on made-up routers. It has no gateway, and a page in a browser can only send HTTP, so it cannot reach most real routers. RouterOS does not help: its REST API sends no CORS headers and answers the preflight request with 503 (the REST API page of MikroTik says so), so a page cannot call it even over HTTPS with a trusted certificate. Chrome's Direct Sockets, which would give a page real TCP, are exposed only to isolated contexts such as installed Isolated Web Apps, not to ordinary pages. How that page is built and what it can do is in [app/webApp/README.md](app/webApp/README.md).

## Network tools

The "Network tools" button in the connection window and the wrench in the top bar of a router open fourteen tools that run on this computer or phone, not on the router.

| Tool | What it does | Where it works |
|---|---|---|
| Ping | Echoes through the system `ping` (on Android `/system/bin/ping`, no root needed; on iOS an ICMP socket that needs no privileges) or, as a second method, the time to open a TCP connection to a port (443 by default). A count or "until stopped", the interval, the timeout, the packet size, the TTL, the don't-fragment flag, and IPv4 or IPv6 to force; the address the name resolved to is shown. A chart of the last 100 round-trip times (a lost reply is a red bar), the last 20 replies, and sent, received, lost in per cent, minimum, average, maximum and jitter (the deviation of the times from their average). "Find path MTU" sends echoes of different sizes with the flag set and narrows in on the largest one that passes (IPv4 only, from 576 to 1500 bytes) | Desktop, Android, iOS |
| Traceroute | The same `ping` with TTL from 1 to 30; each hop with its address, host name and time. The name is a reverse lookup through the system resolver that runs after the hop is shown, waits at most 2.5 s and can be turned off. It stops at the target or after eight silent hops in a row | Desktop, Android, iOS |
| Port scanner | TCP connections to a list or a range of ports of one host (`22,80,443`, `1-1024`), 64 at once by default, 800 ms each; a port that does not connect is shown as closed or filtered | Desktop, Android, iOS |
| LAN scanner | Scans a range: `192.168.1.0/24`, `192.168.1.10-100`, `192.168.1.10-192.168.1.99` or an address with a mask, at most 4096 addresses (the network and the broadcast address of a subnet are left out). It starts with the /24 of this device and, if there are several networks, offers a list. Every address gets an echo (48 addresses at once) and TCP connections to ports 22, 53, 80, 139, 443, 445, 5000, 8080, 8291 and 62078; a host that answers either is listed with its name, MAC address, vendor, round-trip time and open ports. MAC addresses are read from the ARP table of this device, and a host that is in the table but ignored every probe is listed too; iOS and Android 10 and newer close the table to apps, and there the list has no MAC address and vendor columns | Desktop, Android, iOS |
| DNS lookup | A, AAAA, MX, NS, TXT, CNAME, PTR, SOA, SRV and CAA over UDP from Cloudflare, Google, Quad9, Yandex or any server you type; a truncated answer is asked again over TCP. An IP address is looked up as PTR | Desktop, Android, iOS |
| Whois | Two tabs. RDAP asks the registry over HTTPS about a domain, an IP address, a network in CIDR form or an AS number and lists status, dates, name servers, registrar and the contacts the registry makes public as rows; the raw JSON is one tick away. The server comes from the IANA files at data.iana.org, rdap.org stands in when they name none. Port 43 asks whois.iana.org, follows its referral to the registry and, when the registry names one, to the registrar; a server that fails leaves on the screen what it had sent and is named under the text | RDAP: Desktop, Android; port 43: Desktop, Android, iOS |
| IP info | An address, a host name or nothing (your public address). Shows the addresses a name resolves to, the reverse name, the AS, prefix, country and registry from the DNS service of Team Cymru, the network record from RDAP with the abuse contact, and the location and provider from ipwho.is (ipapi.co if that fails); for your own address also the address of the other family from ipify. Every block names the host it asked and fails on its own | Desktop, Android |
| Speed test | Latency (median and jitter of five requests), download and upload over four parallel connections to speed.cloudflare.com or to another server with the same /meta, /__down and /__up; the first half second of a phase is not counted, the next 8 s are, and the figure on the screen is live. Stop cuts the connections | Desktop, Android |
| Wake-on-LAN | Sends the magic packet to a MAC address by UDP broadcast (255.255.255.255, port 9 by default) | Desktop, Android, iOS with the multicast entitlement |
| Subnet calculator | Network, mask, wildcard, broadcast, first and last host, number of hosts, class and the binary form for IPv4; network, first and last address and their number for IPv6. A range (`192.168.1.10-192.168.5.110`, or `192.168.1.10-100` where the number is the last octet) gives its first and last address, their number, the fewest CIDR blocks that cover it exactly and the smallest network around it | Everywhere |
| Subnet splitter | Three modes. Equal cuts a network, or a range that is exactly one network, into parts by their number (rounded up to a power of two) or by a prefix; the table shows up to 1024 parts. VLSM gives every host count its own block, the biggest first, inside a network or a range; a block is never smaller than a /30. Summarize merges a list of networks and ranges, one per line or separated by commas, into the fewest CIDR blocks | Everywhere |
| Transfer time | Gives the third of size, speed and time when two are typed: sizes from B to TiB (decimal and binary), speeds from bit/s to GB/s, time in hours, minutes and seconds. Overhead in per cent is the share of the speed that carries no data. For a known size the time at 10 Mbit/s, 100 Mbit/s and 1 Gbit/s is listed under the result | Everywhere |
| Port reference | 109 well-known TCP and UDP ports with the protocol, the service and a short description in the language of the interface. The filter takes the start of a number, a part of the service, the protocol or a word of the description. The port scanner names open ports from the same list | Everywhere |
| MAC vendor lookup | Finds the vendor in the IEEE registry that comes with the program (58 623 blocks of 2026-09-25); the longest prefix wins | Everywhere |
| IP ↔ HEX | Turns IPv4 addresses into the hex value of a DHCP option as RouterOS takes it (`10.103.0.250` → `0x0A6700FA`); with a sub-option code it adds the code and the length, as option 43 wants them (`1` gives `0x01040A6700FA`). A hex value is read back as addresses, as sub-options in code-length-value form and as text | Everywhere |

A browser can open no sockets, so the page shows the calculator, the splitter, the port reference, the transfer time, the MAC lookup and IP ↔ HEX. On iOS the echoes of the ping, the traceroute and the LAN scanner go through an ICMP socket that needs no privileges.

RDAP, IP info and the speed test send requests to third-party hosts, which the line under their form lists. They need HTTPS with the certificate check of the system, which the desktop and Android have; on iOS only the port 43 whois remains of the three, in a browser none.

The scan sends many packets, so run it only on networks you manage. On Android 10 and later the ARP table is closed to apps, so the scan shows no MAC addresses there and misses the hosts that answer nothing but ARP. A MAC address with the "locally administered" bit set, which phones use for privacy, has no vendor and is marked "random or local".

## Appearance

Settings → Theme offers three themes. Modern is the default: flat controls in the layout of WinBox 4. Legacy repeats WinBox 3, with raised buttons and framed tabs, and stays light until "Look" is set to Dark. Material follows Material Design 3: pill-shaped buttons, rounded windows with a thin frame, tonal selection of the current item, and the title of the active window in a tint of the accent. Its sizes are made for a mouse: table rows 26 dp high against 24 in Modern, fields and window titles 32 dp; on a phone every theme grows to 40 dp for a finger. "Table rows" in the same settings adds up to 8 dp to every row of a table or takes 2 away. "Comments in tables" puts the comment of a row above it, in a column or nowhere, in every table. The comments button of a table window gives that table a mode of its own and stays highlighted while it has one; "As in the settings" at the top of its menu returns the table to the setting, and a change of the setting returns every table to it. "Colour" picks one of eight accents, and Material builds both its light and its dark palette from it. On Android 12 and later "System colours" takes the palette Android builds from the wallpaper instead. "Look" chooses between light, dark and the setting of the system; Modern and Material follow the system until it is changed.

A settings window opens its tables with buttons, as in WinBox: Static, Cache, Adlist and Forwarders of IP › DNS open one window "DNS" with a tab for each, and a second button switches that window to its tab. "Open the tables of a settings window as its tabs" in Settings puts them on tabs of the settings window instead, and the window grows to show the whole form; the same holds for SNMP, NTP, UPnP, NAT-PMP, RoMON and Accounting of RouterOS 6. Windows that were open before the change keep their layout.

## When something does not work

| What you see | What it means | What to do |
| --- | --- | --- |
| "… does not accept connections on port N: the service is off or the port is closed" | the service on the router is off, or the firewall closes the port | turn the service on (on RouterOS, for example, `/ip service enable api`) or pick another protocol; grey entries in the list are the ports that gave no answer |
| "… does not answer" | a wrong address, no route, or a firewall that silently drops packets | check the address, ping the router, make sure you are on the same network; if the router sits behind another router, use the "Via router" field |
| "… cannot be found" | the name does not resolve to an address | enter the IP address |
| "The router refused the login: …" | a wrong login or password, or the user has no rights for that service | check them and, on RouterOS, the user group |
| "… and NetworkHelm could not agree on TLS …" | API-SSL is on in RouterOS, but the service has no certificate | pick "API (8728)", connect, and issue a certificate in the "Prepare router" window |
| "No SNMP answer from …" | the community is wrong or is not allowed to read (a switch counts such a request as "illegal operation for community name supplied"), the agent is off, or UDP 161 is closed | on the agent check the community: it must exist with read rights and let your address in; SNMP v2c must be on |
| "the SNMP agent answered notWritable for …" (or noAccess, readOnly, authorizationError) | the object is read-only on this agent or the community or user may not write it | use a community or user with write rights and a view that includes the object |
| "the SNMP agent answered inconsistentValue for …" (or wrongValue, wrongLength, noCreation) | the agent does not take this value or this row: a required column of a new row is missing, the index is taken, the value is out of range | fill the other columns of the row, take another index, check the allowed values in the documentation of the device |
| "Android does not let NetworkHelm reach the local network" | the permission is not granted | in the Android settings of the app allow "Nearby devices" |
| "The connection was lost after sending the command …" | the link dropped at the moment of the edit | check the router first: the command may have run, and a repeat would run it twice |
| "The connection to the router is lost, reconnecting…" | the link dropped or the router rebooted; on a phone the system also closes the connections of an app in the background | wait: the session comes back by itself, open forms keep what you typed; after a return to the app on a phone this takes a few seconds |
| "Loading… 2187 of 34998" at the bottom of a list | over TCP 8291, SSH and REST a table of more than 2 000 rows comes in portions, so that the router does not have to hold it in memory whole; on a local network 30 000 rows take about 10 seconds over REST and 40 over TCP 8291 | wait: the list is ready when the count turns into the number of items |
| "Not loaded" and a "Retry" button under a list | the rows did not arrive; the reason stands beside the button | press "Retry" or F5 |
| The router stopped answering right after you typed the address | its protection against password guessing took the port check for a login attempt | wait for the block to end and turn the check off in "Settings" |
| "The browser blocked the request …" | a page in a browser can only send HTTP, and RouterOS sends no CORS headers | open the app through a gateway, or connect with the app |
| The program does not start | on a computer it writes the cause to the file `NetworkHelm-startup-error.log` in the temporary folder | take the file (`%TEMP%` on Windows, `/tmp` on Linux) and attach it to your report with the version of the system |
| A window closes after a message about an error | the error is written to `NetworkHelm-error.log` in the same temporary folder, and the message gives its full path | attach that file to your report |

Everything else can go to the [issue tracker](https://github.com/vasmarfas/networkhelm/issues). Leave addresses, passwords and keys out of your reports.

## Where the data lives

The program keeps saved routers, settings and table layouts in its own data folder. Passwords and keys you asked it to remember are in the protected storage of the system: Windows Data Protection on Windows, the Keychain on macOS and iOS, the Secret Service on Linux, the Android Keystore on Android. Where there is no system storage, the program encrypts them itself, and that protection is weaker. All of it is described in [privacy-policy.md](privacy-policy.md).

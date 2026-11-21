# Privacy Policy

Last updated: 18 November 2026

NetworkHelm is a client for managing network routers: MikroTik RouterOS, OpenWrt and its derivatives, OPNsense, pfSense, VyOS, Keenetic and Netcraze, plain Linux hosts over SSH, and devices that answer SNMP, UPnP or TR-064. This policy covers the desktop apps for Windows, macOS and Linux, the Android and iOS apps, and the browser gateway that the desktop app or the separate gateway server provides.

## What the app does not do

The app has no account with the developer, no analytics, no crash reporting, no advertising and no tracking. It does not send anything to the developer or to any server of the developer. The developer has no way to see which routers you manage or what you do with them.

## Connections the app makes

Apart from the check for a new version, the list of changes of a RouterOS version and synchronisation, all described below, the app connects only to the addresses you enter or pick, and only by the protocol you choose for each of them: the RouterOS API, API-SSL and REST, SSH and SFTP, the ubus interface of OpenWrt over SSH or HTTP, the REST APIs of OPNsense, pfSense and VyOS, RCI of Keenetic, SNMP v2c, UPnP and TR-064. When you route a connection through another router ("Via"), the app opens an SSH tunnel through that router. Your login, the commands you run and the configuration you read or change travel between your device and your routers only.

Some protocols the app offers are not encrypted (the RouterOS API on port 8728, REST and ubus over HTTP, RCI over HTTP, SNMP v2c, UPnP). The app marks them in the connection window and in the main window. Over such a protocol the password and every value can be read by anyone on the network path.

Discovery of routers on the local network listens for MikroTik Neighbor Discovery (MNDP) broadcasts. The subnet scan, which you start yourself, opens a TCP connection to the ports of the supported protocols on each address of the subnet you choose and closes it at once, without a login.

## Check for a new version

The desktop and Android apps ask GitHub (`api.github.com`) for the list of releases of the project when they start. The request carries no data about you or your routers; GitHub sees your IP address and the version of the app in the `User-Agent` header, as any web server would, and handles them under its own privacy policy. If a newer release exists, the toolbar shows a button that opens its page in your browser; with no answer, nothing is shown. The app downloads and installs nothing by itself. The iOS app, whose updates come through the store, and the browser version make no such request. The check is switched off in Settings under "Check for new versions of NetworkHelm"; with the setting off the app makes no request at all at start.

The notice about a newer RouterOS comes from the router: the app reads the update state that the router keeps (`/system/package/update`) over the connection you opened. The notice does not ask MikroTik's servers.

## List of changes of a RouterOS version

The window "Check For Updates" shows the list of changes of the version the router offers. Over the WinBox protocol (port 8291), which the desktop and Android apps open beside the API when the port answers, the list comes from the router itself and nothing leaves the local connection. When the app has no such channel to the router, the desktop and Android apps ask the manufacturer's server for the text: one HTTPS GET to `https://upgrade.mikrotik.com/routeros/<version>/CHANGELOG`, where `<version>` is the latest version the router names for its update channel (for example `7.26beta5`). The request goes out only while the window is open; the answer is kept in memory only, so a version that was answered is asked for once during one run of the app. It carries the version of the app in the `User-Agent` header (`NetworkHelm/<version>`) and the address the request comes from, as any request to the network does; nothing about the router goes with it, neither its address, name, serial number nor login. A redirect to another host is not followed, an answer larger than 256 KiB is dropped, and when the server does not answer the box stays empty and nothing is reported. The iOS app and the browser version make no such request.

## Network tools

The tools of the "Network tools" window send requests only when you press Start, and only for what you typed. Ping, traceroute, the port scanner, the LAN scanner and Wake-on-LAN talk to the addresses you enter or to the addresses of your own network. The DNS lookup asks the resolver you pick (Cloudflare, Google, Quad9, Yandex or one you type). Whois over port 43 asks `whois.iana.org` and the server it names; RDAP asks `data.iana.org` for the list of servers and then the RDAP server of the registry (`rdap.org` when the list names none). IP info asks the DNS service of Team Cymru (`asn.cymru.com`), the RDAP server of the registry, `ipwho.is` (`ipapi.co` when that fails) and, for your own address, `api.ipify.org`. The speed test talks to `speed.cloudflare.com` or to the server you type and moves data for about eight seconds in each direction, which on a fast line is hundreds of megabytes. These hosts see your IP address and what you typed, and where HTTP is used the `User-Agent` header with the name and version of the app; they handle them under their own policies. Nothing goes to the developer. The iOS app and the browser version open fewer of these connections, because they can open fewer kinds.

## Synchronisation between your devices

Synchronisation is off until you set it up in Settings, and until then the app sends nothing for it anywhere. When you set it up, the app sends your saved routers, settings, themes, snippets, Docker templates, named workspaces, tunnels and trusted fingerprints, and the passwords and keys of your routers only if you tick that separate box, to the places you choose, one or several: a folder that a sync client of your own keeps alike, a WebDAV, S3 or Git service, Dropbox, Google Drive, Yandex Disk, iCloud, or your own NetworkHelm gateway. The developer runs no such service and receives nothing.

With Dropbox, Google Drive, Yandex Disk and iCloud you sign in on the page of that service, in the browser or in a window of the system. The app asks only for its own place there, Apps/NetworkHelm in Dropbox, the hidden folder of the app on Google Drive, Applications/NetworkHelm on Yandex Disk or the container of the app in iCloud, and sees nothing else of the account. The tokens of the sign-in stay in the protected storage of the device, go only to that service and are not part of the code for a new device: each device signs in itself. Turning synchronisation off ends the sign-in on Dropbox and Google Drive as well; Yandex Disk and iCloud have no such call, there you take the access away in the settings of the account.

Everything leaves the device encrypted with XChaCha20-Poly1305 under a key made from your password with Argon2id; the service never gets the password or the key. The service sees the names of the files (`networkhelm-sync/vault`, `devices/<random id>`, `state/<random id>`), which tell how many devices write and when, and the size of the files, rounded up to 4 KiB; it handles your account and the encrypted files under its own privacy policy. The code that lets a new device join holds the key to the data and the login of the service, which the new device then shares; the app shows it only after you type the password again, for 60 seconds, as a QR code and as text that cannot be copied.

Your own gateway, chosen as the service, keeps the encrypted files in the folder `sync` of its home and, for each device, a hash of the token the device got at its login; a new gateway password ends all such tokens. Removing the app leaves the vault on the service; "Turn off…" with "Also remove the vault from the service" removes it.

## Data stored on your device

- **Saved routers:** address, user name, protocol, group, comment and the router to connect through. They are stored in the app's own data folder.
- **Passwords and SSH keys** you choose to remember use the operating system's secure storage when available: Windows Data Protection (DPAPI), the macOS or iOS Keychain, the Secret Service on Linux, or the Android Keystore. On desktop systems where the system storage is unavailable, the storage library falls back to software encryption in the app's data folder; this does not provide the same protection as system-backed storage.
- **Fingerprints** of router certificates and SSH host keys you accepted, so that a changed certificate or key is reported.
- **Synchronisation:** the identifiers of the vault and of the device, the name of the device, the service with its address and user name, and a memory of the last round with your own last state, encrypted as it went to the service, in the app's data folder. The keys of synchronisation and the password, key or token of the service are in the same secure storage as remembered passwords.
- **Logins to container registries** you save in the Docker windows of a Linux host, in the same secure storage as remembered passwords.
- **Settings and layouts:** window positions, table columns and sorting, the theme, terminal snippets and the like.
- **Cached menu descriptions** of RouterOS versions the app has seen, to open windows faster.
- **The journal of edits** of the current session, kept in memory until the app closes.

An exported address book is a file you save yourself. If you protect it with a password, its contents are encrypted with AES-256-GCM under a key derived from that password with PBKDF2.

On Android the system may include the app's data folder in your device backup, if you have backups turned on; the setup and the memory of synchronisation stay out of it. Remembered passwords and keys, and the keys of synchronisation, are not usable from such a backup: their encryption key stays in the Android Keystore of the device.

Removing the app removes this data, except files you exported yourself and, on some systems, entries the operating system keeps in its secure storage until you delete them there.

## The app in a browser

When you turn on access from a browser in the desktop app, or run the gateway server, the gateway stores on the computer it runs on: its own account (a user name, a value derived from the password to check it, and the secret of an authenticator app if you set one up), a log of logins with time and address, its TLS certificate, and the routers, passwords and fingerprints you save while working in the browser, the passwords encrypted with a key derived from the gateway password. The browser keeps only its session cookie. The gateway reaches your routers the same way the desktop app does and sends nothing elsewhere.

When an OpenWrt router serves the web app itself, the app talks only to that router. The saved routers and the settings stay in the local storage of that browser; passwords are kept only until the page is closed.

The page without a gateway, published on GitHub Pages or another static host, works the same way: the browser reaches your routers by itself and the page sends nothing to the developer. The host sees the requests for the files of the page, as any host does, under its own privacy policy. Remembered passwords and the keys of synchronisation stay in the local storage of that browser, encrypted with a WebCrypto key that the page cannot export and that the browser keeps in IndexedDB: a copy of the browser's files does not reveal them, but a script running on the same address can use that key, and while the page is open they are in its memory. The six demo routers in the list of the page are generated inside it and exist nowhere else.

## Permissions

- **Android:** internet and local network access, to connect to routers; Wi-Fi multicast, to receive MNDP announcements; the camera, only when you choose to scan the QR code another of your devices shows to join synchronisation. The frames are read on the device and are neither stored nor sent.
- **iOS and macOS:** local network access, to connect to routers and receive their announcements; on iOS the camera, in the same way, to scan that code.
- **Browser:** the page reads that code from a photo you take with the camera of your phone; the photo stays in the browser.
- **Files:** the app reads and writes only the files you pick: uploads and downloads of router files, exported configurations and address books, and for synchronisation the folder you pick, where it works only in the subfolder `networkhelm-sync`.

## Children

The app is a tool for administering network equipment and is not directed at children.

## Changes

A change to this policy is published in this file in the repository, with a new date at the top.

**Developer:** vasmarfas
**Contact:** https://github.com/vasmarfas/networkhelm/issues

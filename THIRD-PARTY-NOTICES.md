# Third-party notices

Components used by this project, with the license each one is covered by. This file supplements [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md): the terms below apply to those components, not to this project's own code. Versions are the ones pinned in [gradle/libs.versions.toml](gradle/libs.versions.toml).

## Apache License 2.0

The text of the license is in [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt).

| Component | Copyright |
|---|---|
| Kotlin standard library, `kotlin-reflect`, `kotlin-parcelize-runtime`, `kotlin-test` | JetBrains s.r.o. and Kotlin Programming Language contributors |
| kotlinx.coroutines | JetBrains s.r.o. |
| kotlinx.serialization (core and JSON) | JetBrains s.r.o. |
| kotlinx-datetime | JetBrains s.r.o. |
| kotlinx.atomicfu | JetBrains s.r.o. |
| kotlinx-io (core and bytestring) | JetBrains s.r.o., Square, Inc. |
| KSafe (`eu.anifantakis:ksafe`), the password store | Ioannis Anifantakis |
| Material Color Utilities, the Kotlin port inside MaterialKolor (`com.materialkolor:material-color-utilities`), the colours of the Material theme | Copyright 2021-2025 Google LLC |
| Poko annotations (`dev.drewhamilton.poko:poko-annotations`), pulled in by MaterialKolor | Drew Hamilton |
| AndroidX DataStore, pulled in by KSafe | The Android Open Source Project |
| Okio, pulled in by DataStore | Square, Inc. |
| Compose Multiplatform — animation, runtime, foundation, material, material3, ui, ui-backhandler, components-resources, ui-tooling-preview, collection and annotation, including their AndroidX counterparts | The Android Open Source Project, JetBrains s.r.o. |
| AndroidX lifecycle, navigationevent and savedstate, including the JetBrains multiplatform builds | The Android Open Source Project, JetBrains s.r.o. |
| AndroidX activity (including activity-compose), startup and profileinstaller | The Android Open Source Project |
| AndroidX annotation, arch.core, collection, concurrent, core (including core-ktx), resourceinspection, tracing and versionedparcelable | The Android Open Source Project |
| AndroidX appcompat, autofill, cardview, constraintlayout, coordinatorlayout, cursoradapter, customview, drawerlayout, fragment, loader, recyclerview, viewpager, viewpager2 and window | The Android Open Source Project |
| AndroidX documentfile, dynamicanimation, emoji2, exifinterface, graphics, interpolator, transition and vectordrawable | The Android Open Source Project |
| JetBrains annotations (`org.jetbrains:annotations`) | JetBrains s.r.o. |
| JSpecify (`org.jspecify:jspecify`) | Copyright 2022 The JSpecify Authors |
| Error Prone annotations (`com.google.errorprone:error_prone_annotations`) | Copyright 2017 The Error Prone Authors |
| Guava ListenableFuture (`com.google.guava:listenablefuture`) | Copyright (C) 2007 The Guava Authors |
| Ktor (server core, Netty engine, WebSockets, compression and the generator of self-signed certificates for the gateway; network sockets on iOS) | JetBrains s.r.o. |
| Netty, pulled in by the Ktor Netty engine (server) | The Netty Project |
| Typesafe Config (`com.typesafe:config`), pulled in by Ktor | Typesafe Inc. |
| Jetty ALPN API (`org.eclipse.jetty.alpn:alpn-api`, Apache-2.0 chosen of Apache-2.0 or EPL-1.0), pulled in by the Ktor Netty engine | Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd. |
| Skiko (`org.jetbrains.skiko:skiko-awt` and its platform runtime), the desktop graphics bindings | JetBrains s.r.o. |
| JNA and jna-platform, pulled in by FileKit for the file dialogs of the desktop and by Jewel (Apache-2.0 chosen of Apache-2.0 or LGPL-2.1) | Java Native Access contributors |
| Jewel (`org.jetbrains.jewel:jewel-decorated-window` with `jewel-int-ui-standalone`, `jewel-ui` and `jewel-foundation`), the title bar of the desktop windows | JetBrains s.r.o. |
| IntelliJ Platform icons (`com.jetbrains.intellij.platform:icons-api`, `icons-impl`, `icons-api-rendering`), pulled in by Jewel | JetBrains s.r.o. |
| JetBrains Runtime API (`org.jetbrains.runtime:jbr-api`), pulled in by Jewel for the custom title bar | JetBrains s.r.o. |
| Meslo LG S font with the Nerd Fonts glyphs (MesloLGS NF), bundled inside BossTerm (the patcher of Nerd Fonts is MIT) | Copyright 2009 Apple Inc., Copyright 2006 Tavmjong Bah; Nerd Fonts — Copyright (c) 2014 Ryan L McIntyre |
| SnakeYAML Engine (`org.snakeyaml:snakeyaml-engine`), reads interface packs written in YAML during the build; it is not part of the application | SnakeYAML Engine developers |
| quickjs-kt (`io.github.dokar3:quickjs-kt`), runs the forms of LuCI on the desktop and Android | Copyright (c) dokar3 |
| ConnectBot Terminal (`org.connectbot:termlib`), the terminal of the Android application | Copyright 2025 Kenny Root |
| tik4net (https://github.com/danikf/tik4net, v5.0.0-alpha2): the names, types, enumeration values and read-only marks of the fields of RouterOS menus in `core/data/tik4net-fields.json` are taken from its entity classes; no code of it is used | Copyright (C) Daniel Frantik |
| Margin Research (https://github.com/MarginResearch/EC-SRP, https://github.com/MarginResearch/mikrotik_authentication and the article "MikroTik Authentication Revealed"): the description of the EC-SRP5 login in `miknative/`; no code of it is used | Margin Research |
| tik4net wiki and `Docs/findings-romon.md` (https://github.com/danikf/tik4net): the behaviour of RouterOS over the channel on port 8291 and the relay through a RoMON agent described there guided `miknative/`; no code of it is used | Copyright (C) Daniel Frantik |
| tik4net, `tik4net/Winbox/WinboxM2Protocol.cs` (https://github.com/danikf/tik4net): the numbers of the keys and commands of the object model (getall, the read of a singleton, the cursor, the flags of a read, the error codes) that `miknative/NativeObjectModel.kt` uses; no code of it is used | Copyright (C) Daniel Frantik |
| tik4net, `Docs/winbox-native-m2-protocol.md` (https://github.com/danikf/tik4net): the table of the types of the message fields (the arrays of 64-bit numbers, addresses and raw values), the second code for "not implemented" and the way an address range is written, in `miknative/`; no code of it is used | Copyright (C) Daniel Frantik |
| Material Components for Android, AndroidX appcompat and core-ktx, pulled in by ConnectBot Terminal | Google LLC, The Android Open Source Project |
| OpenSSL 3.5 (libcrypto and libssl), built by `tools/ios/build-ssh.sh` and linked into the iOS application under libssh2 | Copyright (c) 1998-2025 The OpenSSL Project Authors |

## MIT License (iOS)

| Component | Copyright |
|---|---|
| SwiftTerm 1.99.1, the terminal of the iOS application, through Swift Package Manager | Copyright (c) 2019-2026 Miguel de Icaza; Copyright (c) 2017-2019 The xterm.js authors; Copyright (c) 2014-2016 SourceLair Private Company; Copyright (c) 2012-2013 Christopher Jeffrey |

Swift Package Manager also fetches swift-png, h and swift-argument-parser (Apache-2.0) for SwiftTerm; they serve its build tools and its graphics on Linux, Windows and Android and are not built into the iOS application.

## BSD 3-Clause License

| Component | Copyright |
|---|---|
| JSch, the mwiede fork (`com.github.mwiede:jsch`, desktop and Android builds) | Copyright (c) 2002-2015 Atsuhiko Yamanaka, JCraft, Inc. |
| JZlib, bundled inside JSch | Copyright (c) 2000-2011 ymnk, JCraft, Inc. |
| Protocol Buffers runtime, shaded inside AndroidX DataStore (pulled in by KSafe) | Copyright 2008 Google Inc. |
| libssh2 1.11, SSH and SFTP of the iOS application, built by `tools/ios/build-ssh.sh` | Copyright (C) 2004-2007 Sara Golemon, (C) 2009-2024 Daniel Stenberg and the other authors listed in its COPYING |

> Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
> following conditions are met:
>
> 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following
>    disclaimer.
> 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
>    disclaimer in the documentation and/or other materials provided with the distribution.
> 3. The names of the authors may not be used to endorse or promote products derived from this software without
>    specific prior written permission.
>
> THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
> WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL JCRAFT, INC. OR ANY
> CONTRIBUTORS TO THIS SOFTWARE BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
> (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
> INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
> NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
> DAMAGE.

## BSD 3-Clause License (js-joda)

| Component | Copyright |
|---|---|
| js-joda (`@js-joda/core` 3.2.0), bundled in the browser build by kotlinx-datetime | Copyright (c) 2016, Philipp Thürwächter & Pattrick Hüper; Copyright (c) 2007-present, Stephen Colebourne & Michael Nascimento Santos |

> Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
> following conditions are met:
>
> * Redistributions of source code must retain the above copyright notice, this list of conditions and the following
>   disclaimer.
> * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
>   disclaimer in the documentation and/or other materials provided with the distribution.
> * Neither the name of js-joda nor the names of its contributors may be used to endorse or promote products derived from
>   this software without specific prior written permission.
>
> THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
> INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
> DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
> SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
> LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
> CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
> SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## ISC License

**jBCrypt**, bundled inside JSch — Copyright (c) 2006 Damien Miller <djm@mindrot.org>

**Lucide icons** (`com.composables:icons-lucide-cmp`, application UI) — Copyright (c) 2026 Lucide Icons and Contributors. The icons Lucide took from Feather are under the MIT License, listed below.

> Permission to use, copy, modify, and distribute this software for any purpose with or without fee is hereby granted,
> provided that the above copyright notice and this permission notice appear in all copies.
>
> THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL
> IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
> INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
> ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF
> THIS SOFTWARE.

## GNU Lesser General Public License 3.0

| Component | Copyright |
|---|---|
| BossTerm (`com.risaboss:bossterm-core`, `bossterm-compose`), the terminal of the desktop application | Shivang and BossTerm contributors |

BossTerm is used unmodified, as separate jar files of the desktop distribution, and works with any build of the same version put in their place. Its source is at https://github.com/kshivang/BossTerm. The texts of the license are in [licenses/LGPL-3.0.txt](licenses/LGPL-3.0.txt) and, as the LGPL builds on it, [licenses/GPL-3.0.txt](licenses/GPL-3.0.txt).

## Eclipse Public License 1.0

| Component | Copyright |
|---|---|
| pty4j (`org.jetbrains.pty4j:pty4j`), pulled in by BossTerm | JetBrains s.r.o. and pty4j contributors |

Used unmodified. Its source is at https://github.com/JetBrains/pty4j; the text of the license is in [licenses/EPL-1.0.txt](licenses/EPL-1.0.txt). The contributors of pty4j give it without any warranty and are not liable for damages, as sections 5 and 6 of the license say.

## Unicode License v3

| Component | Copyright |
|---|---|
| ICU4J (`com.ibm.icu:icu4j`), pulled in by BossTerm | Unicode, Inc. and others; International Business Machines Corporation and others |

> UNICODE LICENSE V3
>
> COPYRIGHT AND PERMISSION NOTICE
>
> Copyright © 2016-2023 Unicode, Inc.
>
> NOTICE TO USER: Carefully read the following legal agreement. BY
> DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR
> SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE
> TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT
> DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.
>
> Permission is hereby granted, free of charge, to any person obtaining a
> copy of data files and any associated documentation (the "Data Files") or
> software and any associated documentation (the "Software") to deal in the
> Data Files or Software without restriction, including without limitation
> the rights to use, copy, modify, merge, publish, distribute, and/or sell
> copies of the Data Files or Software, and to permit persons to whom the
> Data Files or Software are furnished to do so, provided that either (a)
> this copyright and permission notice appear with all copies of the Data
> Files or Software, or (b) this copyright and permission notice appear in
> associated Documentation.
>
> THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
> KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
> MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
> THIRD PARTY RIGHTS.
>
> IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
> BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
> OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
> WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
> ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
> FILES OR SOFTWARE.
>
> Except as contained in this notice, the name of a copyright holder shall
> not be used in advertising or otherwise to promote the sale, use or other
> dealings in these Data Files or Software without prior written
> authorization of the copyright holder.

## Eclipse Public License 1.0 or GNU LGPL 2.1, at the recipient's choice

| Component | Copyright |
|---|---|
| Logback (server) | QOS.ch Sarl |

## MIT License

| Component | Copyright |
|---|---|
| SLF4J API, pulled in by Logback (server) and by KSafe | QOS.ch Sarl |
| Compose Unstyled (`com.composables:composeunstyled`, `composeunstyled-colored-indication`, application UI) | Copyright (c) 2026 Composable Horizons |
| Ripple indication (`com.composables:ripple-indication`), pulled in by Compose Unstyled on Android | Composable Horizons |
| Compose Icons, the Kotlin packaging of Lucide (`com.composables:icons-lucide-cmp`) | Copyright (c) 2025 Composable Horizons |
| MaterialKolor (`com.materialkolor:material-color-utilities`), the colours of the Material theme | Copyright (c) 2026 Jordon de Hoog |
| Feather icons inside Lucide | Copyright (c) 2013-present Cole Bemis |
| tikoci/centrs (https://github.com/tikoci/centrs): the description of the MAC-Telnet frames and the packet header in `miknative/`; no code of it is used | Copyright (c) 2026 TIKOCI GitHub Organization |
| subixonfire/winbox-terminal-protocol (https://github.com/subixonfire/winbox-terminal-protocol): the description of the frames, the message format and the console messages of the protocol on port 8291 in `miknative/`; the order of the hashes and the encodings of the EC-SRP5 login were read from its client; no code of it is used | Copyright (c) 2026 subixonfire |
| subixonfire/winbox-file-client (https://github.com/subixonfire/winbox-file-client): the description of the file store and of the object model of the protocol on port 8291 in `miknative/`; the types of the message fields were read from its client; no code of it is used | Copyright (c) 2026 subixonfire |
| FileKit (`io.github.vinceglb:filekit-core`, `filekit-dialogs`), file dialogs | Copyright (c) 2024 Vincent Guillebaud |
| dbus-java (`com.github.hypfvieh`), pulled in by FileKit for the file dialogs on Linux | dbus-java contributors |
| QuickJS, the JavaScript engine inside quickjs-kt | Copyright (c) 2017-2024 Fabrice Bellard, Copyright (c) 2017-2024 Charlie Gordon |
| libvterm, the terminal emulation inside ConnectBot Terminal | Copyright (c) Paul Evans |
| Bouncy Castle (`org.bouncycastle:bcprov-jdk18on`), ed25519, curve25519 and chacha20 for SSH on Android; the Bouncy Castle License is the MIT licence | Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc. |
| AppImage runtime (`type2-runtime` 20251108, https://github.com/AppImage/type2-runtime), the first part of the AppImage file of the Linux build; the libraries it links statically are covered by their own licenses, listed in its repository | Copyright (c) 2004-23 probonopd |

> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
> documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
> rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
> persons to whom the Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
> Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
> WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
> COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
> OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Inno Setup License

The Windows installer and its uninstaller are built with Inno Setup and carry its setup code.

| Component | Copyright |
|---|---|
| Inno Setup 6 (https://jrsoftware.org/isinfo.php) | Copyright (C) 1997-2026 Jordan Russell; portions Copyright (C) 2000-2026 Martijn Laan |

> This software is provided "as-is," without any express or implied warranty. In no event shall the author be held
> liable for any damages arising from the use of this software. Permission is granted to anyone to use this software
> for any purpose, including commercial applications, and to alter and redistribute it, provided that the following
> conditions are met: 1. All redistributions of source code files must retain all copyright notices that are currently
> in place, and this list of conditions without modification. 2. All redistributions in binary form must retain all
> occurrences of the above copyright notice and web site addresses that are currently in place (for example, in the
> About boxes). 3. The origin of this software must not be misrepresented; you must not claim that you wrote the
> original software. If you use this software to distribute a product, an acknowledgment in the product
> documentation would be appreciated but is not required. 4. Modified versions in source or binary form must be
> plainly marked as such, and must not be misrepresented as being the original software.

## GNU General Public License 2.0 with the Classpath Exception

| Component | Copyright |
|---|---|
| JetBrains Runtime 25, the Java runtime the desktop installers carry. The application runs on it as on any Java runtime; the Classpath Exception covers its class library | Oracle and/or its affiliates, JetBrains s.r.o. |
| Eclipse Temurin 21 JRE, the base of the Docker image of the gateway (`eclipse-temurin:21-jre`, on Ubuntu); the gateway runs on it as on any Java runtime | Oracle and/or its affiliates, the Eclipse Adoptium project |

## SIL Open Font License 1.1

Bundled with the application as TTF files in `app/shared/src/commonMain/composeResources/font`.

| Font | Copyright |
|---|---|
| Inter (Regular, Medium, SemiBold) | Copyright (c) 2016 The Inter Project Authors (https://github.com/rsms/inter) |
| JetBrains Mono NL (Regular, Bold) | Copyright 2020 The JetBrains Mono Project Authors (https://github.com/JetBrains/JetBrainsMono) |
| Inter and JetBrains Mono in all their styles, bundled inside Jewel (`jewel-int-ui-standalone`) | The same authors as above |

> This Font Software is licensed under the SIL Open Font License, Version 1.1.
> This license is copied below, and is also available with a FAQ at:
> http://scripts.sil.org/OFL
>
> -----------------------------------------------------------
> SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
> -----------------------------------------------------------
>
> PREAMBLE
> The goals of the Open Font License (OFL) are to stimulate worldwide
> development of collaborative font projects, to support the font creation
> efforts of academic and linguistic communities, and to provide a free and
> open framework in which fonts may be shared and improved in partnership
> with others.
>
> The OFL allows the licensed fonts to be used, studied, modified and
> redistributed freely as long as they are not sold by themselves. The
> fonts, including any derivative works, can be bundled, embedded,
> redistributed and/or sold with any software provided that any reserved
> names are not used by derivative works. The fonts and derivatives,
> however, cannot be released under any other type of license. The
> requirement for fonts to remain under this license does not apply
> to any document created using the fonts or their derivatives.
>
> DEFINITIONS
> "Font Software" refers to the set of files released by the Copyright
> Holder(s) under this license and clearly marked as such. This may
> include source files, build scripts and documentation.
>
> "Reserved Font Name" refers to any names specified as such after the
> copyright statement(s).
>
> "Original Version" refers to the collection of Font Software components as
> distributed by the Copyright Holder(s).
>
> "Modified Version" refers to any derivative made by adding to, deleting,
> or substituting -- in part or in whole -- any of the components of the
> Original Version, by changing formats or by porting the Font Software to a
> new environment.
>
> "Author" refers to any designer, engineer, programmer, technical
> writer or other person who contributed to the Font Software.
>
> PERMISSION AND CONDITIONS
> Permission is hereby granted, free of charge, to any person obtaining
> a copy of the Font Software, to use, study, copy, merge, embed, modify,
> redistribute, and sell modified and unmodified copies of the Font
> Software, subject to the following conditions:
>
> 1) Neither the Font Software nor any of its individual components,
> in Original or Modified Versions, may be sold by itself.
>
> 2) Original or Modified Versions of the Font Software may be bundled,
> redistributed and/or sold with any software, provided that each copy
> contains the above copyright notice and this license. These can be
> included either as stand-alone text files, human-readable headers or
> in the appropriate machine-readable metadata fields within text or
> binary files as long as those fields can be easily viewed by the user.
>
> 3) No Modified Version of the Font Software may use the Reserved Font
> Name(s) unless explicit written permission is granted by the corresponding
> Copyright Holder. This restriction only applies to the primary font name as
> presented to the users.
>
> 4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
> Software shall not be used to promote, endorse or advertise any
> Modified Version, except to acknowledge the contribution(s) of the
> Copyright Holder(s) and the Author(s) or with their explicit written
> permission.
>
> 5) The Font Software, modified or unmodified, in part or in whole,
> must be distributed entirely under this license, and must not be
> distributed under any other license. The requirement for fonts to
> remain under this license does not apply to any document created
> using the Font Software.
>
> TERMINATION
> This license becomes null and void if any of the above conditions are
> not met.
>
> DISCLAIMER
> THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
> EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
> MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
> OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
> COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
> INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
> DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
> FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
> OTHER DEALINGS IN THE FONT SOFTWARE.

## The Unlicense

| Component | Author |
|---|---|
| restraml command snapshots of RouterOS 7, merged into `core/schema/routeros-commands.json` (names and versions only, without descriptions) | tikoci |

## zlib License

| Component | Copyright |
|---|---|
| puff.c of zlib (contrib/puff): the inflater of the backups of OpenWrt in `core/src/commonMain/kotlin/com/vasmarfas/networkhelm/offline/Archive.kt` reads deflate its way, codes counted by length and read bit by bit; it is written anew in Kotlin, no code is copied | Copyright (C) 2002-2013 Mark Adler |

## Other sources

| Component | Source |
|---|---|
| The registry of MAC address blocks in `app/shared/src/commonMain/composeResources/files/mac-vendors.tsv`: prefix, organisation and country of every MA-L, MA-M, MA-S and IAB block of the public listings of the IEEE Registration Authority (https://standards-oui.ieee.org) of 25.09.2026, 58 623 blocks | IEEE Registration Authority |
| The logic of the network tools in `core/src/commonMain/kotlin/com/vasmarfas/networkhelm/nettools/` (addresses and subnets, the DNS message, whois referrals, the port list, the MAC registry, the parsing of `ping` output) and the registry file above come from Mobitool, another application of the author of NetworkHelm | vasmarfas |
| The dictionary of SNMP objects in `core/schema/snmp-mibs.tsv` (names, types, enumerations, access and indexes of 40 MIB modules, no descriptions), built by `tools/snmp-mibs/build.py` from the MIB modules in the texts of the RFCs of the IETF (the code components of an RFC, Revised BSD licence of the IETF Trust) and from the UCD-SNMP, NET-SNMP and LM-SENSORS modules of the Net-SNMP project (BSD-style licences of the project) | The IETF Trust, the Net-SNMP project |

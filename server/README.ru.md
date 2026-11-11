# NetworkHelm в браузере: шлюз

Браузер не умеет открывать соединения TCP и SSH, поэтому NetworkHelm в браузере ходит к роутерам через шлюз — небольшой сервер, который хранит сохранённые роутеры и их пароли и подключается к роутерам вместо страницы. Шлюз сам отдаёт веб-приложение по HTTPS на порту 8443, а страница говорит с ним по WebSocket с того же адреса.

**English:** [README.md](README.md)

Запустить его можно пятью способами: архивом со своей Java для Windows и Linux, как jar, в Docker, в контейнере на роутере и внутри десктопного приложения. Роутер OpenWrt может и сам отдавать веб-приложение, без шлюза, а статический хост вроде GitHub Pages — страницу, у которой шлюза тоже нет, см. [app/webApp/README.ru.md](../app/webApp/README.ru.md).

## До чего он дотягивается

Шлюз подключается со своего компьютера, поэтому браузер получает то, что есть у десктопного приложения на этом компьютере: RouterOS по API, API-SSL, REST, SSH и порту 8291 (WinBox), роутер по MAC-адресу (MAC-Telnet), OpenWrt, OPNsense, pfSense, VyOS, Keenetic и хосты по SSH, SNMP и UPnP; список соседей по MNDP, запрос, после которого роутеры объявляют себя, и обход адресов по портам протоколов. MAC-Telnet и MNDP — широковещательные пакеты второго уровня, поэтому компьютер со шлюзом должен стоять в одном сегменте с роутерами. Первым кадром шлюз сообщает странице, что он умеет, и страница показывает только это.

## Вход

Учётная запись — `admin`. Пароль берётся из `NETWORKHELM_GATEWAY_PASSWORD` при первом запуске; без неё шлюз придумывает пароль и печатает его один раз. Поэтому запускать надо из терминала: при двойном щелчке по jar в Windows консоли нет, и пароль пропадает. Сам пароль шлюз нигде не хранит; потерянный не восстановить, но админ компьютера задаёт другой из консоли (ниже).

Код приложения-аутентификатора (Google Authenticator, Aegis, 2FAS и подобные) шлюз спрашивает, если запущен с `--code`, и всегда, если открыт для любых адресов (`--public`). Пока ни один вход не принял код, шлюз печатает ссылку `otpauth://` при каждом запуске, а страница входа после верного пароля показывает её QR-код — приложение настраивается прямо со страницы.

Пять неудачных попыток с одного адреса за десять минут закрывают вход с этого адреса на десять минут. Каждая попытка пишется в `logins.log`; пока вход с адреса закрыт, его попытки дают там одну строку. Сеанс живёт двенадцать часов.

Без `--public` шлюз отвечает только локальным сетям: 10/8, 172.16/12, 192.168/16, 100.64/10 (CGNAT, Tailscale), IPv6 ULA и link-local, loopback.

За обратным прокси шлюз видит адрес прокси, а не клиента. Прокси стоит на том же компьютере или в локальной сети, поэтому фильтр локальных сетей пропускает всех, кого пропустил прокси, а пять неудачных попыток считаются на всех сразу: попытки одного посетителя закрывают вход остальным. Такой шлюз запускайте с `--code` или `--public`, чтобы вход требовал ещё и код приложения-аутентификатора.

## Пароль из консоли

До консоли компьютера дотягивается только его админ, поэтому учётную запись меняют там, параметрами той же программы: они делают своё дело и завершаются, не запуская шлюз.

| Параметр | Что делает |
| --- | --- |
| `--set-password` | новый пароль: набирается дважды без отображения, а без терминала берётся из `NETWORKHELM_GATEWAY_PASSWORD`; не короче 8 знаков. Сохранённые пароли роутеров переходят к нему, если ввести текущий пароль (или задать его в `NETWORKHELM_GATEWAY_CURRENT_PASSWORD`); Enter на этом вопросе, как для потерянного пароля, откладывает их в сторону |
| `--reset-password` | придуманный пароль, показанный один раз, для потерянного; сохранённые пароли роутеров откладываются в `secrets.json.old`: они запечатаны прежним паролем и без него не открываются |
| `--new-code` | новый секрет приложения-аутентификатора, как при потере телефона; печатается ссылка `otpauth://`, а страница входа после пароля показывает её QR-код |

Если учётной записи ещё нет, `--set-password` её создаёт, так что пароль можно задать до первого запуска. Работающий шлюз принимает изменение при следующем запросе: старый пароль перестаёт действовать сразу, сеансы по нему заканчиваются, но открытая страница сохраняет свой сокет, пока её не закроют.

```bash
java -jar networkhelm-gateway.jar --reset-password
networkhelm-gateway.bat --set-password
sudo env NETWORKHELM_GATEWAY_HOME=/var/lib/networkhelm-gateway /opt/networkhelm-gateway/networkhelm-gateway.sh --reset-password
docker run --rm -it -v networkhelm-gateway:/data networkhelm-gateway --reset-password
```

Первая строка — для jar, вторая — для архива на Windows, третья — для службы systemd, четвёртая — для контейнера; папка — та, которой пользуется шлюз, `NETWORKHELM_GATEWAY_HOME`, если она не стандартная.

## Сертификат

Первый запуск создаёт самоподписанный сертификат на десять лет для `localhost`, имени и адресов машины. Браузер один раз предупредит о нём; шлюз печатает его SHA-256 при каждом запуске, и тот же отпечаток браузер показывает в сведениях о сертификате. Сверьте их, прежде чем доверять.

## Параметры

| Параметр | Что значит |
| --- | --- |
| `--host`, `--port` | адрес и порт, по умолчанию `0.0.0.0` и `8443`; `--host 127.0.0.1` — за обратным прокси |
| `--public` | отвечать любым адресам, а не только локальным сетям; код тогда спрашивается всегда |
| `--code` | спрашивать код приложения-аутентификатора |
| `NETWORKHELM_GATEWAY_HOME` | папка шлюза, по умолчанию `~/.networkhelm-gateway` |
| `NETWORKHELM_GATEWAY_PASSWORD` | пароль первого запуска |
| `NETWORKHELM_GATEWAY_WEB` | папка со сборкой `:app:webApp`, которую отдавать вместо встроенной в jar |

В папке шлюза лежат `gateway.json` (учётная запись), `gateway.jks` (ключ сертификата), `known-routers` (отпечатки роутеров), `schema/` (что роутеры рассказали о своих меню), `documents/` и `secrets.json` (то, что шлюз хранит для браузера; секреты запечатаны ключом, который даёт только пароль `admin`) и `logins.log`.

## Архивом со своей Java

```bash
./gradlew :server:gatewayArchive
```

собирает `server/build/gateway/NetworkHelm-gateway-<версия>-<система>.zip` на Windows и `.tar.gz` на Linux и macOS: jar, среда Java, которую jlink урезает до модулей, нужных jar, и скрипты запуска. Ничего больше ставить не нужно, архив весит около 65 МБ. Среда — от того JDK, который собирает архив, поэтому каждая система собирает свой.

На Windows распаковать и запустить `networkhelm-gateway.bat`: в окне один раз виден адрес и пароль первого запуска, закрытие окна останавливает шлюз. На Linux — `./networkhelm-gateway.sh` или службой systemd:

```bash
sudo mkdir -p /opt/networkhelm-gateway
sudo tar -xzf NetworkHelm-gateway-<версия>-linux-x64.tar.gz -C /opt/networkhelm-gateway --strip-components=1
sudo cp /opt/networkhelm-gateway/networkhelm-gateway.service /etc/systemd/system/
sudo systemctl enable --now networkhelm-gateway
sudo journalctl -u networkhelm-gateway
```

В журнале пароль первого запуска; учётная запись, сертификат и сохранённые роутеры лежат в `/var/lib/networkhelm-gateway`.

## Как jar

```bash
./gradlew :server:buildFatJar
java -jar server/build/libs/networkhelm-gateway.jar
```

Jar весит около 31 МБ, нужна Java 17 или новее; веб-приложение уже внутри.

## В Docker

К каждому релизу есть готовый образ для amd64 и arm64 (тег `latest` указывает только на стабильные версии):

```bash
docker run -d --name networkhelm-gateway -p 8443:8443 -v networkhelm-gateway:/data -e NETWORKHELM_GATEWAY_PASSWORD=<пароль> ghcr.io/vasmarfas/networkhelm-gateway:latest
docker logs networkhelm-gateway
```

Чтобы собрать его самому:

```bash
./gradlew :server:buildFatJar
docker build -t networkhelm-gateway server
docker run -d --name networkhelm-gateway -p 8443:8443 -v networkhelm-gateway:/data -e NETWORKHELM_GATEWAY_PASSWORD=<пароль> networkhelm-gateway
docker logs networkhelm-gateway
```

Параметры пишутся после имени образа: `docker run ... networkhelm-gateway --code`. В журнале — SHA-256 сертификата и, если спрашивается код, ссылка `otpauth://`. MNDP и MAC-Telnet нужен второй уровень сети роутеров, которого контейнер за своим мостом не видит: на Linux запускать с `--network host` вместо `-p`, иначе страница их не предложит.

Образ собран на `eclipse-temurin:21-jre` и работает от непривилегированного пользователя; папка шлюза — том `/data`. Скачивается около 150 МБ, в распакованном виде — 290 МБ; в контейнере с лимитом 256 МБ шлюз занял 130 МБ памяти. В Docker на Linux шлюз видит настоящий адрес клиента с другой машины. Docker Desktop на Windows и macOS передаёт каждое соединение с адреса своей виртуальной машины, а он локальный, поэтому там фильтр локальных сетей пропускает всех, кто дотянулся до опубликованного порта.

Под другой процессор, например для роутера:

```bash
docker buildx build --platform linux/arm64 --output type=docker,dest=networkhelm-gateway-arm64.tar server
```

Базовый образ есть для `linux/arm64`, `linux/arm/v7` и `linux/amd64`.

## В контейнере на роутере с RouterOS

Нужны RouterOS 7 с пакетом `container`, роутер на arm, arm64 или x86 с подключённым диском под 290 МБ распакованного образа и около 150 МБ свободной памяти. Роутеры arm32v5 (hEX Refresh), MIPS и PowerPC его не запустят. До 7.21.2 контейнер не может писать в свои тома от обычного пользователя: там добавьте контейнеру `user=0`.

1. Разрешить контейнеры: `/system/device-mode/update container=yes`, затем подтвердить кнопкой reset или холодной перезагрузкой, как попросит роутер.
2. Сеть для контейнеров:

   ```
   /interface/veth/add name=veth-networkhelm address=172.17.0.2/24 gateway=172.17.0.1
   /interface/bridge/add name=containers
   /ip/address/add address=172.17.0.1/24 interface=containers
   /interface/bridge/port/add bridge=containers interface=veth-networkhelm
   /ip/firewall/nat/add chain=srcnat action=masquerade src-address=172.17.0.0/24
   ```

3. Пароль первого запуска и папка шлюза на диске:

   ```
   /container/envs/add list=networkhelm key=NETWORKHELM_GATEWAY_PASSWORD value="<пароль>"
   /container/mounts/add list=networkhelm src=disk1/networkhelm/data dst=/data
   ```

4. Собрать образ под процессор роутера, как в разделе про Docker, залить tar на диск роутера и добавить контейнер:

   ```
   /container/add file=disk1/networkhelm-gateway-arm64.tar interface=veth-networkhelm root-dir=disk1/networkhelm/root envlists=networkhelm mountlists=networkhelm logging=yes start-on-boot=yes
   ```

   Чтобы спрашивался код приложения-аутентификатора, добавьте `cmd="--code"`.
5. Когда `/container/print` покажет его остановленным (образ распакован), запустить: `/container/start [find interface=veth-networkhelm]`.
6. Пробросить порт с адреса роутера в локальной сети, здесь `192.168.88.1`:

   ```
   /ip/firewall/nat/add chain=dstnat action=dst-nat protocol=tcp dst-address=192.168.88.1 dst-port=8443 to-addresses=172.17.0.2 to-ports=8443
   ```

Что печатает шлюз, видно в журнале роутера (`/log/print where topics~"container"`). Дальше открыть `https://192.168.88.1:8443`. Роутер, на котором работает шлюз, доступен ему по адресу `172.17.0.1`.

## Внутри десктопного приложения

Настройки → Доступ из браузера. Десктоп запускает тот же шлюз внутри себя, веб-приложение входит в его пакет: задайте пароль, выберите порт, отвечать ли любым адресам и спрашивать ли код, и откройте доступ. Окно показывает адреса компьютера с QR-кодом первого — для телефона — и SHA-256 сертификата; пока доступ открыт, в заголовке окна виден его порт, а следующий запуск приложения открывает его снова.

Браузер работает с сохранёнными роутерами, паролями и отпечатками десктопа. Его собственные настройки, рабочие области и раскладки таблиц лежат отдельно, в `gateway/` папки приложения, вместе с учётной записью, сертификатом и `logins.log`.

## Прямо на роутере OpenWrt

Роутер OpenWrt с LuCI (uhttpd с модулем ubus и rpcd) может отдавать NetworkHelm сам, без шлюза. Страница тогда ходит только к этому роутеру — через `/ubus` того же адреса, входом самого роутера.

```bash
./gradlew :app:webApp:openWrtPackage
scp -O app/webApp/build/openwrt/networkhelm-openwrt-<версия>.tar.gz root@192.168.1.1:/tmp/
ssh root@192.168.1.1 'rm -rf /www/networkhelm && tar -xzf /tmp/networkhelm-openwrt-<версия>.tar.gz -C / && rm /tmp/networkhelm-openwrt-<версия>.tar.gz && /etc/init.d/rpcd reload'
```

Затем открыть `http://192.168.1.1/networkhelm/` или `https://192.168.1.1/networkhelm/`. Пакет занимает 19 МБ флеш-памяти, копировать 6 МБ: сначала посмотрите `df -h /overlay`; на роутер, у которого вся флеш-память 16 МБ, он не встанет. Ещё он кладёт `/usr/share/rpcd/acl.d/networkhelm.json`: этот файл даёт root через `/ubus` то же, что root может по SSH, — все объекты ubus с подписками, UCI и файлы; без него вход получает только ACL LuCI. Обновление прошивки сохранит оба, если `/www/networkhelm` и файл ACL перечислены в `/etc/sysupgrade.conf`.

На такой странице нет терминала и файлов — им нужен SSH, а браузер его не открывает, — и нет окон приложений LuCI, которым нужен свой движок JavaScript. Сохранённые роутеры и настройки остаются в браузере, пароль — до закрытия страницы.

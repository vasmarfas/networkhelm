package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory

// the clouds a device signs in to in the browser, each through a registration of the app with it: a client of OAuth, or the API token of
// the container in CloudKit. A cloud the app is not registered with yet is not offered
enum class Cloud { YandexDisk, Dropbox, GoogleDrive, OneDrive, CloudKit }

// the address of the page of the app that the consoles of the clouds list: the window in which the page signs in comes back to it, and
// it passes the answers of the sign-ins of the apps on to them
const val SIGN_IN_PAGE = "https://networkhelm.vasmarfas.com/"

// the scheme of the app the page hands the answer of a sign-in over to, on a phone
const val APP_SCHEME = "networkhelm"

// how a device signs in to a cloud: the desktop in its browser; Android and iOS in the browser of the system; the page in a window of its
// own. Each cloud takes its own ways back for each of them
enum class SignInWay { Loopback, Android, Ios, Page }

// the browser of this device for a sign-in. Open shows an address in the browser, and page is the HTML document the browser shows when it
// comes back to the loopback address of the app, which the desktop and Android listen on. Handed, where the system gives back the address the browser
// was sent to, as iOS and the page do, shows the address and waits for that one, by the scheme iOS waits for. The client of the cloud for
// the way picks one of the two
class WayBack(
    val way: SignInWay,
    val open: (String) -> Unit,
    val page: String,
    val handed: (suspend (url: String, scheme: String) -> String)? = null,
)

// the registration of the app with a cloud for a way of signing in; null where the cloud takes no such way or the app has no client for
// it yet
internal fun Cloud.client(way: SignInWay): OAuthClient? = when (this) {
    Cloud.YandexDisk -> when (way) {
        SignInWay.Loopback, SignInWay.Android -> YANDEX_DISK
        // the way back of an app on iOS Yandex gives only to its LoginSDK: the browser comes back to the page, which hands the answer over
        SignInWay.Ios -> YANDEX_DISK.copy(redirect = SIGN_IN_PAGE, relay = APP_SCHEME)
        SignInWay.Page -> YANDEX_DISK.copy(redirect = SIGN_IN_PAGE)
    }
    Cloud.Dropbox -> when (way) {
        SignInWay.Loopback -> DROPBOX
        // the address of the official SDKs, which Dropbox takes for the key of the app without a line in the console
        SignInWay.Android, SignInWay.Ios -> DROPBOX.copy(redirect = "db-${DROPBOX.id}://2/token")
        SignInWay.Page -> DROPBOX.copy(redirect = SIGN_IN_PAGE)
    }
    Cloud.GoogleDrive -> when (way) {
        // a client for Android takes neither a scheme of its own nor a loopback address, so Android signs in as the desktop does; Google
        // refuses the code of that client without its secret: client_secret is missing
        SignInWay.Loopback, SignInWay.Android -> GOOGLE_DRIVE.takeIf { it.secret.isNotEmpty() }
        SignInWay.Ios -> GOOGLE_DRIVE_IOS
        // a page signs in only with the secret of a client for the web
        SignInWay.Page -> null
    }
    Cloud.OneDrive -> ONEDRIVE.takeIf { way == SignInWay.Loopback || way == SignInWay.Android }
    Cloud.CloudKit -> null
}?.takeIf { it.id.isNotEmpty() }

// the container of CloudKit with the API token for a way of signing in: iOS comes back by the scheme of the container, the others through
// the page, as CloudKit in production takes no loopback address
internal fun cloudKit(way: SignInWay): CloudKitContainer? = (if (way == SignInWay.Ios) CLOUDKIT_APPLE else CLOUDKIT_PAGE).takeIf { it.token.isNotEmpty() }

fun Cloud.offered(way: SignInWay): Boolean = if (this == Cloud.CloudKit) cloudKit(way) != null else client(way) != null

// the service of a cloud with the tokens of this device; keep takes them again each time the cloud renews them, through the client of
// the way the device signed in
fun cloudService(cloud: Cloud, way: SignInWay, http: SyncHttp, tokens: String, keep: suspend (String) -> Unit): SyncService {
    fun login(base: OAuthClient, scheme: String = "Bearer") = OAuthLogin(http, cloud.client(way) ?: base, tokens, keep, scheme)
    return when (cloud) {
        Cloud.YandexDisk -> YandexDiskService(http, login(YANDEX_DISK, "OAuth"))
        Cloud.Dropbox -> DropboxService(http, login(DROPBOX))
        Cloud.GoogleDrive -> GoogleDriveService(login(GOOGLE_DRIVE))
        Cloud.OneDrive -> OneDriveService(http, login(ONEDRIVE))
        Cloud.CloudKit -> CloudKitService(http, cloudKit(way) ?: CLOUDKIT_PAGE, tokens, keep)
    }
}

// the sign-in of this device in the browser, as the text the service of the cloud takes its tokens from
suspend fun signIn(cloud: Cloud, http: SyncHttp, back: WayBack): String {
    val refused = DeviceException(ErrorCategory.General, "$cloud takes no sign-in from this device")
    val handed = back.handed
    if (cloud == Cloud.CloudKit) {
        val container = cloudKit(back.way) ?: throw refused
        // the desktop and Android hear the web token on the loopback address the page passes it to
        val relayed = back.way == SignInWay.Loopback || back.way == SignInWay.Android
        return if (relayed || handed == null) container.relayed(http, back.open, back.page) else container.signIn(http) { handed(it, container.redirect.substringBefore(':')) }
    }
    val client = cloud.client(back.way) ?: throw refused
    return textOf(
        if (client.loopback || handed == null) {
            client.signIn(http, back.open, back.page)
        } else {
            client.signIn(http) { handed(it, client.relay.ifEmpty { client.redirect.substringBefore(':') }) }
        },
    )
}

// the sign-in ends on the cloud as well where the cloud lets an app end it, as Dropbox and Google do. Yandex wants the secret of the app
// for that, Microsoft and Apple have no such call: there the user takes the access away in the settings of the account
suspend fun signOut(cloud: Cloud, http: SyncHttp, tokens: String) {
    when (cloud) {
        Cloud.Dropbox -> DropboxService(http, OAuthLogin(http, DROPBOX, tokens, {})).revoke()
        Cloud.GoogleDrive -> tokensOf(tokens)?.let { GOOGLE_DRIVE.revoke(http, it) }
        Cloud.YandexDisk, Cloud.OneDrive, Cloud.CloudKit -> Unit
    }
}

package com.vasmarfas.networkhelm.ui.sync

import org.jetbrains.compose.resources.StringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.sync_error_sign_in_closed
import networkhelm.app.shared.generated.resources.sync_error_sign_in_empty
import networkhelm.app.shared.generated.resources.sync_error_sign_in_late
import networkhelm.app.shared.generated.resources.sync_error_sign_in_other
import networkhelm.app.shared.generated.resources.sync_error_sign_in_popup
import networkhelm.app.shared.generated.resources.sync_error_sign_in_port
import networkhelm.app.shared.generated.resources.sync_error_sign_in_refused

// the messages of the sign-in to the clouds, each with its string
internal val syncCloudErrorTemplates: List<Pair<String, StringResource>> = listOf(
    "%1\$s refused the sign-in: %2\$s" to Res.string.sync_error_sign_in_refused,
    "the answer of the sign-in came for another request" to Res.string.sync_error_sign_in_other,
    "the browser came back without the answer of the sign-in" to Res.string.sync_error_sign_in_empty,
    "the sign-in was not finished in the browser in time" to Res.string.sync_error_sign_in_late,
    "the port %1\$s of this computer is taken by another program" to Res.string.sync_error_sign_in_port,
    "the sign-in was closed before it ended" to Res.string.sync_error_sign_in_closed,
    "the browser did not open the window of the sign-in: allow pop-up windows for this page" to Res.string.sync_error_sign_in_popup,
)

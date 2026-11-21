package com.vasmarfas.networkhelm.ui.sync

import org.jetbrains.compose.resources.StringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.sync_error_argon2
import networkhelm.app.shared.generated.resources.sync_error_changed
import networkhelm.app.shared.generated.resources.sync_error_changed_while_written
import networkhelm.app.shared.generated.resources.sync_error_earlier_key
import networkhelm.app.shared.generated.resources.sync_error_folder
import networkhelm.app.shared.generated.resources.sync_error_gateway_code
import networkhelm.app.shared.generated.resources.sync_error_given_back
import networkhelm.app.shared.generated.resources.sync_error_https
import networkhelm.app.shared.generated.resources.sync_error_keys_gone
import networkhelm.app.shared.generated.resources.sync_error_key_changed
import networkhelm.app.shared.generated.resources.sync_error_login
import networkhelm.app.shared.generated.resources.sync_error_master_key
import networkhelm.app.shared.generated.resources.sync_error_newer
import networkhelm.app.shared.generated.resources.sync_error_no_answer
import networkhelm.app.shared.generated.resources.sync_error_not_api
import networkhelm.app.shared.generated.resources.sync_error_not_vault_file
import networkhelm.app.shared.generated.resources.sync_error_other_key
import networkhelm.app.shared.generated.resources.sync_error_other_place
import networkhelm.app.shared.generated.resources.sync_error_other_vault
import networkhelm.app.shared.generated.resources.sync_error_password
import networkhelm.app.shared.generated.resources.sync_error_place_files_left
import networkhelm.app.shared.generated.resources.sync_error_place_taken
import networkhelm.app.shared.generated.resources.sync_error_rolled_back
import networkhelm.app.shared.generated.resources.sync_error_secret_needed
import networkhelm.app.shared.generated.resources.sync_error_too_large
import networkhelm.app.shared.generated.resources.sync_error_transfer_code
import networkhelm.app.shared.generated.resources.sync_error_untrusted
import networkhelm.app.shared.generated.resources.sync_error_vault_gone
import networkhelm.app.shared.generated.resources.sync_error_vault_made

// the messages of the synchronisation between devices, each with its string
internal val syncErrorTemplates: List<Pair<String, StringResource>> = listOf(
    "%1\$s was changed after it was written" to Res.string.sync_error_changed,
    "%1\$s is not a file of a NetworkHelm vault" to Res.string.sync_error_not_vault_file,
    "%1\$s was written by a newer version of the app: update it to synchronise" to Res.string.sync_error_newer,
    "the vault asks for Argon2id with %1\$s passes and %2\$s bytes of memory, which this app does not run" to Res.string.sync_error_argon2,
    "the vault needs its secret key as well as the password" to Res.string.sync_error_secret_needed,
    "the password does not open the vault" to Res.string.sync_error_password,
    "this key does not open the vault" to Res.string.sync_error_master_key,
    "%1\$s belongs to another vault" to Res.string.sync_error_other_vault,
    "%1\$s was written with an earlier key of the vault" to Res.string.sync_error_earlier_key,
    "the key of the vault was changed on another device: enter the new password" to Res.string.sync_error_key_changed,
    "%1\$s holds a file written for another place" to Res.string.sync_error_other_place,
    "%1\$s was not written with the key of this vault" to Res.string.sync_error_other_key,
    "%1\$s is older than the copy read before: the service gave back an earlier version" to Res.string.sync_error_rolled_back,
    "the code is not one of NetworkHelm synchronisation" to Res.string.sync_error_transfer_code,
    "%1\$s does not answer: %2\$s" to Res.string.sync_error_no_answer,
    "%1\$s sent more than 64 MiB" to Res.string.sync_error_too_large,
    "%1\$s did not accept the login" to Res.string.sync_error_login,
    "the folder of synchronisation is not available: %1\$s" to Res.string.sync_error_folder,
    "%1\$s was changed on the service while it was written" to Res.string.sync_error_changed_while_written,
    "%1\$s did not answer as the API of %2\$s" to Res.string.sync_error_not_api,
    "%1\$s asks to set up the code of an authenticator app on its page first" to Res.string.sync_error_gateway_code,
    "the vault is no longer on the service" to Res.string.sync_error_vault_gone,
    "another device put a vault on the service meanwhile: join it" to Res.string.sync_error_vault_made,
    "the keys of synchronisation are not in the protected store of this device: turn synchronisation off and join the vault again" to Res.string.sync_error_keys_gone,
    "the service gave back a vault with an earlier key than this device saw" to Res.string.sync_error_given_back,
    "the address of the service must start with https://" to Res.string.sync_error_https,
    "the system does not trust the certificate of %1\$s: a public service needs one it trusts" to Res.string.sync_error_untrusted,
    "the vault is kept there already" to Res.string.sync_error_place_taken,
    "the place was left, but the files of this device stay there: %1\$s" to Res.string.sync_error_place_files_left,
)

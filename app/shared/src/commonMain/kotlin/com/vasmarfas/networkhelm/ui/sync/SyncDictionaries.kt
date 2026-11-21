package com.vasmarfas.networkhelm.ui.sync

import com.vasmarfas.networkhelm.sync.Dictionaries
import networkhelm.app.shared.generated.resources.Res

// the common passwords of SecLists and the words of EFF, for the strength and the generator of the sync password; the page of the
// browser fetches them only when the wizard asks
suspend fun syncDictionaries() = Dictionaries(lines("files/sync/passwords.txt"), lines("files/sync/words.txt"))

private suspend fun lines(path: String) = Res.readBytes(path).decodeToString().lines().filter { it.isNotEmpty() }

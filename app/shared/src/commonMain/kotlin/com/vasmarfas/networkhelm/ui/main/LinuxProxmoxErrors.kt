package com.vasmarfas.networkhelm.ui.main

import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.proxmox_error_choose_guest
import networkhelm.app.shared.generated.resources.proxmox_error_choose_storage
import networkhelm.app.shared.generated.resources.proxmox_error_no_guest
import networkhelm.app.shared.generated.resources.proxmox_error_no_node
import networkhelm.app.shared.generated.resources.proxmox_error_snapshot_name
import org.jetbrains.compose.resources.StringResource

// the messages of the windows of Proxmox VE on a Linux host, each with its string
internal val proxmoxErrorTemplates: List<Pair<String, StringResource>> = listOf(
    "the cluster names no node of this host" to Res.string.proxmox_error_no_node,
    "choose the guest" to Res.string.proxmox_error_choose_guest,
    "the cluster has no guest %1\$s" to Res.string.proxmox_error_no_guest,
    "the name of a snapshot is a letter and then letters, digits, - and _, 2 to 40 of them: %1\$s" to Res.string.proxmox_error_snapshot_name,
    "choose the storage" to Res.string.proxmox_error_choose_storage,
)

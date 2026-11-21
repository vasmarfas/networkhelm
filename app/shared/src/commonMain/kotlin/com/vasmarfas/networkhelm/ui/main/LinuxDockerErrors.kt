package com.vasmarfas.networkhelm.ui.main

import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.docker_error_archive_name
import networkhelm.app.shared.generated.resources.docker_error_connected
import networkhelm.app.shared.generated.resources.docker_error_container_id
import networkhelm.app.shared.generated.resources.docker_error_cpus
import networkhelm.app.shared.generated.resources.docker_error_default_bridge
import networkhelm.app.shared.generated.resources.docker_error_device
import networkhelm.app.shared.generated.resources.docker_error_duration
import networkhelm.app.shared.generated.resources.docker_error_folder_taken
import networkhelm.app.shared.generated.resources.docker_error_frame_cut
import networkhelm.app.shared.generated.resources.docker_error_frame_stream
import networkhelm.app.shared.generated.resources.docker_error_host_file
import networkhelm.app.shared.generated.resources.docker_error_host_time
import networkhelm.app.shared.generated.resources.docker_error_image
import networkhelm.app.shared.generated.resources.docker_error_image_name
import networkhelm.app.shared.generated.resources.docker_error_image_required
import networkhelm.app.shared.generated.resources.docker_error_line_json
import networkhelm.app.shared.generated.resources.docker_error_mount
import networkhelm.app.shared.generated.resources.docker_error_mount_type
import networkhelm.app.shared.generated.resources.docker_error_needs_recreate
import networkhelm.app.shared.generated.resources.docker_error_network
import networkhelm.app.shared.generated.resources.docker_error_network_name
import networkhelm.app.shared.generated.resources.docker_error_network_taken
import networkhelm.app.shared.generated.resources.docker_error_no_compose
import networkhelm.app.shared.generated.resources.docker_error_no_compose_file
import networkhelm.app.shared.generated.resources.docker_error_no_engine
import networkhelm.app.shared.generated.resources.docker_error_no_stack
import networkhelm.app.shared.generated.resources.docker_error_nothing_to_install
import networkhelm.app.shared.generated.resources.docker_error_number
import networkhelm.app.shared.generated.resources.docker_error_pair
import networkhelm.app.shared.generated.resources.docker_error_podman_daemon
import networkhelm.app.shared.generated.resources.docker_error_port
import networkhelm.app.shared.generated.resources.docker_error_registry_login
import networkhelm.app.shared.generated.resources.docker_error_rootless_daemon
import networkhelm.app.shared.generated.resources.docker_error_size
import networkhelm.app.shared.generated.resources.docker_error_stack
import networkhelm.app.shared.generated.resources.docker_error_stack_name
import networkhelm.app.shared.generated.resources.docker_error_ulimit
import networkhelm.app.shared.generated.resources.docker_error_unreachable
import networkhelm.app.shared.generated.resources.docker_error_volume
import org.jetbrains.compose.resources.StringResource

// the messages of the container windows of a Linux host, each with its string
internal val dockerErrorTemplates: List<Pair<String, StringResource>> = listOf(
    "the app cannot reach the container engine: %1\$s" to Res.string.docker_error_unreachable,
    "the host has neither docker nor podman" to Res.string.docker_error_no_engine,
    "the engine sent a frame of the unknown stream %1\$s" to Res.string.docker_error_frame_stream,
    "the log ended inside a frame" to Res.string.docker_error_frame_cut,
    "the engine sent a line that is no JSON object" to Res.string.docker_error_line_json,
    "the image of the container is required" to Res.string.docker_error_image_required,
    "the command needs the id of a container" to Res.string.docker_error_container_id,
    "this change takes the container made anew: tick Recreate, and what it wrote outside its volumes goes with the old one" to Res.string.docker_error_needs_recreate,
    "not a port mapping: %1\$s" to Res.string.docker_error_port,
    "not a mount: %1\$s" to Res.string.docker_error_mount,
    "not a type of mount: %1\$s" to Res.string.docker_error_mount_type,
    "not a size: %1\$s" to Res.string.docker_error_size,
    "not a duration: %1\$s" to Res.string.docker_error_duration,
    "not a name=value line: %1\$s" to Res.string.docker_error_pair,
    "not a ulimit: %1\$s" to Res.string.docker_error_ulimit,
    "not a device: %1\$s" to Res.string.docker_error_device,
    "not a number of CPUs: %1\$s" to Res.string.docker_error_cpus,
    "%1\$s: %2\$s is no number" to Res.string.docker_error_number,
    "the name of a file in the archive is longer than 100 bytes" to Res.string.docker_error_archive_name,
    "the command needs an image" to Res.string.docker_error_image,
    "the command needs the new name of the image" to Res.string.docker_error_image_name,
    "the command needs a file of the host" to Res.string.docker_error_host_file,
    "the command needs a volume" to Res.string.docker_error_volume,
    "the network needs a name" to Res.string.docker_error_network_name,
    "the engine has a network %1\$s already" to Res.string.docker_error_network_taken,
    "the command needs a network" to Res.string.docker_error_network,
    "%1\$s is on %2\$s already" to Res.string.docker_error_connected,
    "the default bridge of Docker takes no aliases: they work on networks of the user" to Res.string.docker_error_default_bridge,
    "the command needs a stack" to Res.string.docker_error_stack,
    "the host has no stack %1\$s" to Res.string.docker_error_no_stack,
    "the host has no Compose: docker compose, docker-compose or podman-compose" to Res.string.docker_error_no_compose,
    "the name of a stack is small letters, digits, - and _, from a letter or a digit: %1\$s" to Res.string.docker_error_stack_name,
    "the folder %1\$s is there already" to Res.string.docker_error_folder_taken,
    "the stack %1\$s names no file of Compose" to Res.string.docker_error_no_compose_file,
    "Podman has no daemon.json: it keeps its settings in containers.conf" to Res.string.docker_error_podman_daemon,
    "rootless Docker reads ~/.config/docker/daemon.json of the login and restarts with systemctl --user restart docker; the app changes the daemon.json of the system Docker alone" to Res.string.docker_error_rootless_daemon,
    "the command needs the login of the registry" to Res.string.docker_error_registry_login,
    "the host did not tell its time" to Res.string.docker_error_host_time,
    "the rows chosen have nothing to install" to Res.string.docker_error_nothing_to_install,
)

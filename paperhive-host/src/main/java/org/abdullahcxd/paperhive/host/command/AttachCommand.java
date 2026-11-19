package org.abdullahcxd.paperhive.host.command;

import org.abdullahcxd.paperhive.host.console.ControlSocket;
import org.abdullahcxd.paperhive.host.server.ServerManager;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.NotNull;

import static org.abdullahcxd.paperhive.plugin.command.ConsoleText.*;

/** On the console; a socket client's attach is a session of its own (see {@code ControlSocket}). */
final class AttachCommand extends ServerCommand {

    AttachCommand(ServerManager servers) {
        super(servers, "attach", "type straight into a server and see only its output; `hive detach` to leave");
    }

    @Override
    protected void execute(@NotNull HiveCommandSender sender, @NotNull ServerInstance instance) {
        if (!sender.isConsole()) {
            sender.sendMessage(error("attach only works on the host console, or as " + command("hive attach <name>") + " on its own"));
            return;
        }
        servers.attach(instance);
        sender.sendMessage(success("Attached to " + name(instance.getName()) + muted(": lines go to it and only its output shows; ") + command("hive detach") + muted(" to leave")));
    }

}

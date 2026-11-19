package org.abdullahcxd.paperhive.host.command;

import org.abdullahcxd.paperhive.host.ServerManager;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.NotNull;

/** On the console; a socket client's attach is a session of its own (see {@code ControlSocket}). */
final class AttachCommand extends ServerCommand {

    AttachCommand(ServerManager servers) {
        super(servers, "attach", "type straight into a server and see only its output; `hive detach` to leave");
    }

    @Override
    protected void execute(@NotNull HiveCommandSender sender, ServerInstance instance) {
        if (!sender.isConsole()) {
            sender.sendMessage("attach only works on the host console or as `hive attach <name>` on its own");
            return;
        }
        servers.attach(instance);
        sender.sendMessage("Attached to " + instance.getName() + ": lines go to it and only its output shows; `hive detach` to leave");
    }

}

package org.example;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.event.Subscribe;
import org.abdullahcxd.paperhive.api.event.server.ServerStartedEvent;
import org.abdullahcxd.paperhive.bridge.HiveBukkitPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.Collection;
import java.util.List;

/** The server half: in each server, with Bukkit and the hive at hand. */
public final class ExampleServerPlugin extends HiveBukkitPlugin implements Listener {

  @Override
  public void onEnable() {
    registerCommand("hiveserver", "List, start, stop or restart the hive's servers", List.of("hs"), new HiveServerCommand());
    registerListener(this);
    // Hive events and messages too; Paperhive drops these when this server stops
    getHive().getEventBus().register(this);
    getHive().subscribe("announce", (from, channel, message) -> runTask(() -> getServer().broadcast(Component.text(message))));
    getHive().publish("example", "the server half is enabled in " + getHiveServer().getName());
  }

  @Subscribe
  private void onServerStarted(@NonNull ServerStartedEvent event) {
    // Hive events arrive on a Paperhive thread: hop onto this server's main thread for Bukkit
    if (!event.getServer().equals(getHiveServer())) runTask(() -> getServer().broadcast(Component.text(event.getServer().getName() + " is up")));
  }

  @EventHandler
  public void onJoin(@NonNull PlayerJoinEvent event) {
    getHive().publish("example", event.getPlayer().getName() + " joined " + getHiveServer().getName());
  }

  private final class HiveServerCommand implements BasicCommand {

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String @NotNull [] args) {
      CommandSender sender = source.getSender();
      if (args.length == 1 && args[0].equals("list")) {
        for (HiveServer server : getHive().getServers()) {
          sender.sendMessage(server.getName() + ": " + server.getState() + " on port " + server.getPort());
        }
        return;
      }
      HiveServer target = args.length == 2 ? getHive().getServer(args[1]) : null;
      if (target == null) {
        sender.sendMessage("/hiveserver list | start|stop|restart <server>");
        return;
      }
      switch (args[0]) {
        case "start" -> sender.sendMessage(target.start() ? "Starting " + target.getName() : target.getName() + " is " + target.getState());
        case "stop" -> {
          target.stop();
          sender.sendMessage("Stopping " + target.getName());
        }
        case "restart" -> {
          target.restart();
          sender.sendMessage("Restarting " + target.getName());
        }
        default -> sender.sendMessage("/hiveserver list | start|stop|restart <server>");
      }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String @NotNull [] args) {
      if (args.length <= 1) return List.of("list", "start", "stop", "restart");
      return getHive().getServers().stream().map(HiveServer::getName).toList();
    }

  }

}

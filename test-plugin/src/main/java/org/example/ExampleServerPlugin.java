package org.example;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.HiveServerState;
import org.abdullahcxd.paperhive.api.message.HiveChannel;
import org.abdullahcxd.paperhive.api.message.HiveService;
import org.abdullahcxd.paperhive.api.message.MessageCodec;
import org.abdullahcxd.paperhive.bridge.HiveBukkitPlugin;
import org.abdullahcxd.paperhive.bridge.event.HiveMessageEvent;
import org.abdullahcxd.paperhive.bridge.event.HiveServerStateEvent;
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

  private HiveChannel<String> events;
  private HiveService<String, Integer> players;

  @Override
  public void onEnable() {
    // typed channels and services, keyed in this plugin's namespace ("exampleplugin:...") on every server and the host
    events = channel("events", MessageCodec.STRING);
    players = service("players", MessageCodec.STRING, MessageCodec.INTEGER);
    // answer "how many players?" for this server, on its main thread
    handle(players, request -> getServer().getOnlinePlayers().size());
    // messages on exampleplugin:announce become Bukkit HiveMessageEvents here
    relay(key("announce"));

    registerCommand("hiveserver", "List, start, stop or restart the hive's servers", List.of("hs"), new HiveServerCommand());
    registerListener(this);
    long enables = getHive().getData().increment(key("server-half-enables"), 1); // shared by the whole hive
    events.publish("the server half is enabled in " + getHiveServer().getName() + " (#" + enables + " in this hive)");
  }

  @EventHandler
  public void onHiveServer(@NonNull HiveServerStateEvent event) {
    // a Bukkit event on the main thread: no hopping needed
    if (event.getTo() == HiveServerState.RUNNING && !event.isThisServer()) {
      getServer().broadcast(Component.text(event.getServer().getName() + " is up"));
    }
  }

  @EventHandler
  public void onAnnounce(@NonNull HiveMessageEvent event) {
    if (event.is(key("announce"))) getServer().broadcast(Component.text("[Hive] " + event.getMessage()));
  }

  @EventHandler
  public void onJoin(@NonNull PlayerJoinEvent event) {
    events.publish(event.getPlayer().getName() + " joined " + getHiveServer().getName());
  }

  private final class HiveServerCommand implements BasicCommand {

    private static final String USAGE = "/hiveserver list | players <server> | start|stop|restart <server>";

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
        sender.sendMessage(USAGE);
        return;
      }
      switch (args[0]) {
        // ask another server, continue on this one's main thread
        case "players" -> players.request(target, "")
            .thenAcceptAsync(count -> sender.sendMessage(target.getName() + " has " + count + " player(s)"), mainThread())
            .exceptionallyAsync(e -> {
              sender.sendMessage(target.getName() + " didn't answer: " + e.getMessage());
              return null;
            }, mainThread());
        case "start" -> sender.sendMessage(target.start() ? "Starting " + target.getName() : target.getName() + " is " + target.getState());
        case "stop" -> {
          target.stop();
          sender.sendMessage("Stopping " + target.getName());
        }
        case "restart" -> {
          target.restart();
          sender.sendMessage("Restarting " + target.getName());
        }
        default -> sender.sendMessage(USAGE);
      }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String @NotNull [] args) {
      if (args.length <= 1) return List.of("list", "players", "start", "stop", "restart");
      return getHive().getServers().stream().map(HiveServer::getName).toList();
    }

  }

}

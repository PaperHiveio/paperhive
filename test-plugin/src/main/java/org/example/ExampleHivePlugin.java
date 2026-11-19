package org.example;

import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.Paperhive;
import org.abdullahcxd.paperhive.api.event.Priority;
import org.abdullahcxd.paperhive.api.event.Subscribe;
import org.abdullahcxd.paperhive.api.event.server.ServerCrashEvent;
import org.abdullahcxd.paperhive.api.event.server.ServerStartEvent;
import org.abdullahcxd.paperhive.plugin.HiveContext;
import org.abdullahcxd.paperhive.plugin.PaperhivePlugin;
import org.abdullahcxd.paperhive.plugin.command.HiveCommand;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.abdullahcxd.paperhive.plugin.context.PluginContext;
import org.abdullahcxd.paperhive.plugin.context.ServerContext;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.List;

/** The host half: loaded once, for the whole hive. */
public final class ExampleHivePlugin extends PaperhivePlugin {

  @Override
  public void onEnable(@NotNull HiveContext<PluginContext> context) {
    // a Hive command: typed at the host's hive> prompt or sent with `java -jar paperhive.jar hive greet <who>`
    context.getContext().getCommands().register(new GreetCommand());
    // what the server halves say
    Paperhive.getHive().subscribe("example", (from, channel, message) ->
        getLogger().info("Message from {}: {}", from == null ? "the host" : from.getName(), message));
    // Hive events: any object with @Subscribe methods
    Paperhive.getHive().getEventBus().register(this);
  }

  @Subscribe
  private void onStart(@NonNull ServerStartEvent event) {
    // cancelling would keep it stopped; here: only servers whose name isn't "locked" may start
    if (event.getServer().getName().equals("locked")) event.setCancelled(true);
  }

  @Subscribe(priority = Priority.MONITOR)
  private void onCrash(@NonNull ServerCrashEvent event) {
    getLogger().warn("{} crashed", event.getServer().getName());
  }

  @Override
  public void onServerStart(@NotNull HiveContext<ServerContext> context) {
    HiveServer server = context.getContext().getServer();
    getLogger().info("Server {} is up on port {}", server.getName(), context.getContext().getServerPort());
    // host code may not call Bukkit itself; this runs on that server's main thread
    server.runOnServer(() -> getLogger().info("Now on {}'s thread {}", server.getName(), Thread.currentThread().getName()));
  }

  @Override
  public void onDisable() {
    getLogger().info("Bye");
  }

  private static final class GreetCommand extends HiveCommand {

    GreetCommand() {
      super("greet", "<who>", "say hello", "hi");
    }

    @Override
    public void execute(@NotNull HiveCommandSender sender, @NotNull List<String> args) {
      if (args.size() != 1) sendUsage(sender);
      else sender.sendMessage("Hello, " + args.getFirst() + "!");
    }

    @Override
    public @NotNull List<String> complete(@NotNull HiveCommandSender sender, @NotNull List<String> args) {
      return args.size() == 1 ? List.of("world") : List.of();
    }

  }

}

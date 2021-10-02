package nl.rkslot.pluginreloader;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.SimplePluginManager;

public class PluginFileMonitor {

	private static final Field pluginsField;
	private static final Field lookupNamesField;
	private static final Field commandMapField;
	private static final Field permissionsField;
	private static final Field knownCommandsField;

	static {
		try {
			final Class<?> pluginManager = SimplePluginManager.class;
			pluginsField = pluginManager.getDeclaredField("plugins");
			pluginsField.setAccessible(true);
			lookupNamesField = pluginManager.getDeclaredField("lookupNames");
			lookupNamesField.setAccessible(true);
			commandMapField = pluginManager.getDeclaredField("commandMap");
			commandMapField.setAccessible(true);
			permissionsField = pluginManager.getDeclaredField("permissions");
			permissionsField.setAccessible(true);
			knownCommandsField = SimpleCommandMap.class.getDeclaredField("knownCommands");
			knownCommandsField.setAccessible(true);
		} catch (final NoSuchFieldException e) {
			throw new RuntimeException(e);
		}
	}

	private final AtomicLong lastModified = new AtomicLong();
	private final AtomicBoolean reloadLock = new AtomicBoolean();
	private long reloadStart = 0;
	private Integer reloadCount = 0;

	@SuppressWarnings("unchecked")
	public PluginFileMonitor(final PluginReloader main, final File file, final String pluginName, final int interval) {
		main.getLogger().info("Started monitoring plugin " + pluginName + " at " + file.getPath());
		Bukkit.getScheduler().runTaskAsynchronously(main, () -> {
			if (!file.exists()) {
				main.getLogger().warning("File " + file.getPath() + " does not exist! Aborting.");
				return;
			}

			this.lastModified.set(file.lastModified());

			Bukkit.getScheduler().runTaskTimerAsynchronously(main, () -> {
				if (!file.exists()) {
					main.getLogger().warning("File " + file.getPath() + " no longer exists");
					return;
				}

				synchronized(this.reloadLock) {
					if (this.reloadLock.get()) {
						main.getLogger().warning("Already reloading plugin in a different task. Or, was the lock never released due to a bug?");
						return;
					}

					final long fileLastModified = file.lastModified();

					if (fileLastModified > this.lastModified.get()) {
						this.lastModified.set(fileLastModified);
						this.reloadCount++;
						this.reloadStart = System.currentTimeMillis();
						this.reloadLock.set(true);
						main.getLogger().info("Plugin file " + file.getName() + " was updated, reloading...");
						Bukkit.getScheduler().runTask(main, () -> {
							final PluginManager pm = Bukkit.getPluginManager();
							final Plugin plugin = pm.getPlugin(pluginName);
							if (plugin == null) {
								main.getLogger().warning("No plugin with name '" + pluginName + "' found");
								synchronized(this.reloadLock) {
									this.reloadLock.set(false);
								}
								return;
							}

							try {
								main.getLogger().info("Letting Bukkit disable the plugin");
								pm.disablePlugin(plugin);

								final SimpleCommandMap commandMap = (SimpleCommandMap) commandMapField.get(pm);

								final Map<String, Command> knownCommands = (Map<String, Command>) knownCommandsField.get(commandMap);
								final Deque<String> toRemove = new ArrayDeque<>();
								final Set<Command> toUnregister = new HashSet<>();
								for (final Map.Entry<String, Command> entry : knownCommands.entrySet()) {
									final String name = entry.getKey();
									final Command command = entry.getValue();
									if (command instanceof PluginIdentifiableCommand) {
										PluginIdentifiableCommand pc = (PluginIdentifiableCommand) command;
										if (pc.getPlugin() == plugin) {
											toUnregister.add(command);
											toRemove.add(name);
										}
									}
								}

								toUnregister.forEach(command -> {
									main.getLogger().info("Unregistering command '" + command.getName() + "'");
									command.unregister(commandMap);
								});

								while (!toRemove.isEmpty()) {
									final String name = toRemove.pop();
									main.getLogger().info("Removing command '" + name + "' from known commands");
									knownCommands.remove(name);
								}

								main.getLogger().info("Removing plugin from Bukkit plugin list");
								final List<Plugin> plugins = (List<Plugin>) pluginsField.get(pm);
								plugins.remove(plugin);
								main.getLogger().info("Removing plugin from Bukkit lookup name list");
								final Map<String, Plugin> lookupNames = (Map<String, Plugin>) lookupNamesField.get(pm);
								lookupNames.remove(pluginName);
							} catch (final Exception e) {
								main.getLogger().severe("Failed to disable plugin.");
								e.printStackTrace();
								synchronized(this.reloadLock) {
									this.reloadLock.set(false);
								}
								return;
							}

							try {
								main.getLogger().info("Loading new plugin");
								final Plugin newPlugin = pm.loadPlugin(file);
								main.getLogger().info("Enabling plugin");
								pm.enablePlugin(newPlugin);

							} catch (final Exception e) {
								main.getLogger().severe("Failed to load plugin.");
								e.printStackTrace();
								synchronized(this.reloadLock) {
									this.reloadLock.set(false);
								}
								return;
							}

							main.getLogger().info("Successfully reloaded the plugin! Took " + (System.currentTimeMillis() - this.reloadStart) + "ms");

							synchronized(this.reloadLock) {
								this.reloadLock.set(false);
							}
							return;

						});
					}
				}
			}, interval, interval);
		});
	}

	int getReloadCount() {
		return this.reloadCount;
	}

	void forceReload() {
		this.lastModified.set(0);
	}

}

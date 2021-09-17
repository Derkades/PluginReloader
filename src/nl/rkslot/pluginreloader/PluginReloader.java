package nl.rkslot.pluginreloader;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public class PluginReloader extends JavaPlugin {

	private final Map<String, PluginFileMonitor> pluginMonitors = new HashMap<>();

	@Override
	public void onEnable() {
		super.saveDefaultConfig();

		final List<Map<?, ?>> mapList = super.getConfig().getMapList("plugins");
		mapList.forEach(map -> {
			final String name = (String) map.get("name");
			final String jarName = (String) map.get("file");
			final File file = new File("plugins", jarName);
			final int interval = (int) map.get("interval") * 20;
			this.pluginMonitors.put(name, new PluginFileMonitor(this, file, name, interval));
		});
	}

	public int getReloadCount(final Plugin plugin) {
		final PluginFileMonitor mon = this.pluginMonitors.get(plugin.getName());
		if (mon == null) {
			throw new IllegalArgumentException("Unknown plugin " + plugin.getName() + ", is this plugin managed by PluginReloader?");
		}
		return mon.getReloadCount();
	}

	public void forceReload(final Plugin plugin) {
		final PluginFileMonitor mon = this.pluginMonitors.get(plugin.getName());
		if (mon == null) {
			throw new IllegalArgumentException("Unknown plugin " + plugin.getName() + ", is this plugin managed by PluginReloader?");
		}
		mon.forceReload();
	}

}

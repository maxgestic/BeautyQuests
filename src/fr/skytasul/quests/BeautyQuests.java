package fr.skytasul.quests;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import com.google.common.io.ByteStreams;

import fr.skytasul.quests.api.QuestsAPI;
import fr.skytasul.quests.commands.Commands;
import fr.skytasul.quests.commands.CommandsManager;
import fr.skytasul.quests.editors.Editor;
import fr.skytasul.quests.gui.Inventories;
import fr.skytasul.quests.gui.creation.RequirementsGUI;
import fr.skytasul.quests.gui.creation.RewardsGUI;
import fr.skytasul.quests.gui.creation.stages.StagesGUI;
import fr.skytasul.quests.gui.quests.PlayerListGUI;
import fr.skytasul.quests.players.PlayersManager;
import fr.skytasul.quests.scoreboards.ScoreboardManager;
import fr.skytasul.quests.utils.DebugUtils;
import fr.skytasul.quests.utils.Lang;
import fr.skytasul.quests.utils.SpigotUpdater;
import fr.skytasul.quests.utils.compatibility.Dependencies;
import fr.skytasul.quests.utils.compatibility.Dynmap;
import fr.skytasul.quests.utils.nms.NMS;
import net.citizensnpcs.api.npc.NPC;

public class BeautyQuests extends JavaPlugin{

	private static BeautyQuests instance;
	private BukkitRunnable saveTask;

	public static String lastVersion;
	
	private FileConfiguration config;
	private YamlConfiguration data;
	private File dataFile;
	public static QuestsLogger logger;
	static File saveFolder;
	
	/* ------------------ */

	private List<Quest> quests = new ArrayList<>();
	private Map<NPC, NPCStarter> npcs = new HashMap<>();
	
	/* ------------------ */

	private static boolean disable = false;
	public static boolean loadingFailure = false;
	public static boolean savingFailure = false;
	
	public static int lastID = 0;

	/* ---------------------------------------------- */

	public void onLoad(){
		instance = this;
		try{
			if (!getDataFolder().exists()) getDataFolder().mkdir();
			logger = new QuestsLogger(this);
			Field f = JavaPlugin.class.getDeclaredField("logger");
			f.setAccessible(true);
			f.set(this, logger);
			System.out.println("New logger inserted: " + getLogger().getClass().getName());
		}catch (Throwable ex){
			System.out.println("Failed to insert new logger to BeautyQuests. Actual: " + getLogger().getClass().getName());
			logger = null;
			ex.printStackTrace();
		}
	}
	
	public void onEnable(){
		try{
			Dependencies.initialize(getServer().getPluginManager(), logger);
		}catch (Throwable ex){
			logger.severe("Error when initializing compatibilities. Consider restarting.");
			ex.printStackTrace();
		}
		
		new BukkitRunnable() {
			public void run(){
				try {
					long lastMillis = System.currentTimeMillis();
					getLogger().info(loadAllDatas() + " quests loaded ("
							+ (((double) System.currentTimeMillis() - lastMillis) / 1000D) + "s)!");

					launchSaveCycle();
				}catch (Throwable e) {
					e.printStackTrace();
				}
			}
		}.runTaskLater(this, 40L);

		loadResource("config.yml");

		NMS.intializeNMS();
		
		config = getConfig();
		saveFolder = new File(getDataFolder(), "quests");
		if (!saveFolder.exists()) saveFolder.mkdirs();
		dataFile = new File(getDataFolder(), "data.yml");
		if (!dataFile.exists()){
			try {
				dataFile.createNewFile();
				getLogger().info("data.yml created.");
			} catch (IOException e) {
				e.printStackTrace();
				getLogger().severe("Couldn't create data file.");
				getLogger().severe("This is a fatal error. Now disabling.");
				disable = true;
				this.setEnabled(false);
				return;
			}
		}
		data = YamlConfiguration.loadConfiguration(dataFile);
		
		if (data.contains("version")){
			lastVersion = data.getString("version");
			if (!lastVersion.equals(getDescription().getVersion())){
				createFolderBackup("You are using a new version for the first time.");
				createDataBackup("You are using a new version for the first time.");
			}
		}else lastVersion = getDescription().getVersion();
		data.options().header("Do not edit ANYTHING here.");
		data.options().copyHeader(true);

		registerCommands();

		loadConfigParameters(true);
		
		logger.launchFlushTimer();
		try {
			new SpigotUpdater(this, 39255);
		} catch (IOException e1) {
			e1.printStackTrace();
		}
	}

	public FileConfiguration getConfig(){
		//return YamlConfiguration.loadConfiguration(loadResource("config.yml"));
		FileConfiguration config = null;
		try{
			config = new FileConfiguration(super.getConfig()) {
				
				public String saveToString() {return null;}
				
				public void loadFromString(String paramString) throws InvalidConfigurationException {}
				
				protected String buildHeader() {return null;}
			};
			config.load(loadResource("config.yml"));
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
			getLogger().severe("Error when loading configuration file. Now disabling.");
			this.setEnabled(false);
			disable = true;
		}
		return config;
	}

	public void onDisable(){
		Editor.leaveAll();
		stopSaveCycle();
		
		try {
			if (!disable) getLogger().info(saveAllConfig(true) + " quests saved");
			if (logger != null) logger.close();
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}

	private void registerCommands(){
		CommandsManager questCommand = new CommandsManager((sender) -> {
			if (!(sender instanceof Player)) return;
			Player p = (Player) sender;
			if (!p.hasPermission("beautyquests.command.listPlayer")){
				Lang.INCORRECT_SYNTAX.send(p);
			}else Inventories.create(p, new PlayerListGUI(PlayersManager.getPlayerAccount(p)));
		});
		PluginCommand cmd = getCommand("beautyquests");
		cmd.setPermission("beautyquests.command");
		cmd.setExecutor(questCommand);
		cmd.setTabCompleter(questCommand);
		questCommand.registerCommandsClass(new Commands());
	}
	
	private void loadConfigParameters(boolean init){
		try{
			config = getConfig();
			/*				static initialization				*/
			if (init){
				loadLang();
				StagesGUI.initialize(); // 			initializing default stage types
				RequirementsGUI.initialize(); //	initializing default requirements
				RewardsGUI.initialize(); //			initializing default rewards
			}
			
			QuestsConfiguration.initConfiguration(config);
		}catch (Exception ex){
			getLogger().severe("Error when loading.");
			ex.printStackTrace();
			//getLogger().severe(DebugUtils.stackTraceMessage(ex, "Error when loading.", true));
		}
	}
			
	private int loadAllDatas() throws Throwable{
		if (disable) return 666;
		
		ScoreboardManager.initialize();
		if (Dependencies.dyn){
			try{
				Dynmap.intitialize();
			}catch (Throwable ex){
				getLogger().severe("An error occured while initializing dynmap integration.");
				ex.printStackTrace();
				Dynmap.unload();
			}
		}
		
		quests.clear();
		lastID = data.getInt("lastID");

		
		try{
			PlayersManager.load(data);
		}catch (Throwable ex){
			createDataBackup("Error when loading player datas.");
			ex.printStackTrace();
		}

		for (File file : saveFolder.listFiles()){
			if (!file.getName().substring(file.getName().lastIndexOf(".") + 1).equals("yml")) continue;
			loadingFailure = false;
			try{
				Quest quest = Quest.loadFromFile(file);
				if (quest != null) {
					addQuest(quest);
				}else logger.severe("Quest from file " + file.getName() + " not activated");
				if (loadingFailure) createQuestBackup(quest, "Error when loading quest.");
			}catch (Throwable ex){
				ex.printStackTrace();
				continue;
			}
		}
		QuestsConfiguration.firstQuest = QuestsAPI.getQuestFromID(QuestsConfiguration.firstQuestID);
		
		getServer().getPluginManager().registerEvents(new QuestsListener(), this);
		
		return quests.size();
	}

	public int saveAllConfig(boolean unload) throws Throwable{
		if (unload){
			ScoreboardManager.unload();
		}
		
		int amount = 0;
		for (Quest qu : quests){
			savingFailure = false;
			try{
				File file = qu.file;
				if (!file.exists()) file.createNewFile();
				YamlConfiguration fc = YamlConfiguration.loadConfiguration(file);
				List<Map<String, Object>> ls = new ArrayList<>();
				ls.add(qu.serialize());
				if (savingFailure) createQuestBackup(qu, "Error when saving quest.");
				fc.set("quest", ls);
				fc.save(file);
				amount++;
				
				if (unload) qu.unloadAll();
			}catch (Throwable ex){
				getLogger().warning("Error when saving quest ID " + qu.getID());
				ex.printStackTrace();
				continue;
			}
		}
		data.set("lastID", lastID);
		data.set("version", getDescription().getVersion());
		
		try{
			PlayersManager.save(data);
		}catch (Throwable ex){
			createDataBackup("Error when saving player datas.");
			ex.printStackTrace();
		}
		data.save(dataFile);
		
		if (unload){
			resetDatas();
		}
		
		return amount;
	}
	
	private void resetDatas(){
		quests.clear();
		npcs.clear();
		HandlerList.unregisterAll(this);
		if (Dependencies.dyn) Dynmap.unload();
	}
	
	private void launchSaveCycle(){
		if (QuestsConfiguration.saveCycle > 0){
			int cycle = QuestsConfiguration.saveCycle * 60 * 20;
			saveTask = new BukkitRunnable() {
				public void run() {
					try {
						logger.info(saveAllConfig(false) + " quests saved ~ periodic save");
					}catch (Throwable e) {
						logger.severe("Error when saving !");
						e.printStackTrace();
					}
				}
			};
			logger.info("Periodic saves task started (" + cycle + " ticks). Task ID: " + saveTask.runTaskTimer(this, cycle, cycle).getTaskId());
		}
	}
	
	private void stopSaveCycle(){
		if (QuestsConfiguration.saveCycle > 0 && saveTask != null){
			saveTask.cancel();
			saveTask = null;
			logger.info("Periodic saves task stopped.");
		}
	}
	
	public boolean createFolderBackup(String msg){
		getLogger().info(msg + " Creating backup...");
		try {
			File backupDir = backupDir();
			FileUtils.copyDirectory(saveFolder, backupDir);
			getLogger().info("Quests backup created in " + backupDir.getName());
			return true;
		}catch (Exception e) {
			getLogger().severe("An error occured while creating the backup.");
			e.printStackTrace();
			return false;
		}
	}
	
	public boolean createDataBackup(String msg){
		getLogger().info(msg + " Creating backup...");
		try{
			getLogger().info("Datas backup created in " + Files.copy(dataFile.toPath(), new File(backupDir(), "data.yml").toPath()).getParent().getFileName());
			return true;
		}catch (Exception e) {
			getLogger().severe("An error occured while creating the backup.");
			e.printStackTrace();
			return false;
		}
	}

	public boolean createQuestBackup(Quest quest, String msg){
		getLogger().info(msg + " Creating backup...");
		try{
			getLogger().info("Quest backup created at " + Files.copy(quest.file.toPath(), new File(saveFolder, quest.getID() + "-backup" + format.format(new Date()) + ".yml").toPath()).getFileName());
			return true;
		}catch (Exception e) {
			getLogger().severe("An error occured while creating the backup.");
			e.printStackTrace();
			return false;
		}
	}

	private SimpleDateFormat format = new SimpleDateFormat("yyyy'-'MM'-'dd'-'hh'-'mm'-'ss");
	private File backupDir(){
		File f = new File(getDataFolder(), "backup-" + format.format(new Date()));
		if (!f.exists()) f.mkdir();
		return f;
	}

	private File loadResource(String resource) {
        File folder = getDataFolder();
        if (!folder.exists())
            folder.mkdir();
        File resourceFile = new File(folder, resource);
        try {
            if (!resourceFile.exists()) {
                resourceFile.createNewFile();
                try (InputStream in = getResource(resource);
                     OutputStream out = new FileOutputStream(resourceFile)) {
                    ByteStreams.copy(in, out);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return resourceFile;
    }
	
	private YamlConfiguration loadLang() {
		String s = "en.yml";
		if (config.getString("lang") != null) s = config.getString("lang") + ".yml";
		File lang = new File(getDataFolder(), s);
		if (!lang.exists()) {
			try {
				getDataFolder().mkdir();
				lang.createNewFile();
				InputStream defConfigStream = this.getResource(s);
				if (defConfigStream != null) {
					YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defConfigStream, StandardCharsets.UTF_8));
					defConfig.save(lang);
					Lang.setFile(defConfig);
					getLogger().info("Loaded language file " + s);
					return defConfig;
				}
			} catch(IOException e) {
				e.printStackTrace();
				getLogger().severe("Couldn't create language file.");
				getLogger().severe("This is a fatal error. Now disabling.");
				disable = true;
				this.setEnabled(false);
			}
		}
		YamlConfiguration conf = YamlConfiguration.loadConfiguration(lang);
		for(Lang item : Lang.values()) {
			if (conf.getString(item.getPath()) == null) {
				DebugUtils.logMessage("Copying default lang value to " + item.getPath());
				conf.set(item.getPath(), item.getDefault());
			}else {
				/*if (!conf.getString(item.getPath()).equals(item.getDefault())){
	        		System.out.println(item.getPath() + " new = " + item.getDefault());
	        	}*/
			}
		}
		Lang.setFile(conf);
		File LANG_FILE = lang;
		try {
			conf.save(LANG_FILE);
		} catch(IOException e) {
			getLogger().warning("Failed to save lang.yml.");
			getLogger().warning("Report this stack trace to SkytAsul on SpigotMC.");
			e.printStackTrace();
		}
		getLogger().info("Loaded language file " + s);
		return conf;
	}
	
	public void performReload(CommandSender sender){
		try {
			sender.sendMessage("§c§l-- ⚠ Warning ! This command can occur §omuch§r§c§l bugs ! --");
			sender.sendMessage("§a " + BeautyQuests.getInstance().saveAllConfig(true) + " quests saved");
			BeautyQuests.getInstance().resetDatas();
			//BeautyQuests.getInstance().stopSaveCycle();
		} catch (Throwable e) {
			sender.sendMessage("§cError when saving datas. §lInterrupting operation!");
			e.printStackTrace();
			return;
		}
		
		try{
			BeautyQuests.getInstance().loadResource("config.yml");
			BeautyQuests.getInstance().loadConfigParameters(false);
			sender.sendMessage("§a Configuration parameters has been reloadeds.");
		}catch (Throwable e){
			sender.sendMessage("§cError when reloading configuration parameters. §lInterrupting operation!");
			e.printStackTrace();
			return;
		}
		
		sender.sendMessage("§7...Waiting for loading quests...");
		new BukkitRunnable() {
			public void run() {
				try {
					data = YamlConfiguration.loadConfiguration(dataFile);
					sender.sendMessage("§a " + BeautyQuests.getInstance().loadAllDatas() + " quests loaded");
					sender.sendMessage("§a§lPlugin entierely reloaded from files !");
				} catch (Throwable e) {
					sender.sendMessage("§cError when loading the data file. §lOperation failed!");
					e.printStackTrace();
				}
			}
		}.runTaskLater(BeautyQuests.getInstance(), 20L);
	}
	
	void removeQuest(Quest quest){
		quests.remove(quest);
		NPCStarter starter = npcs.get(quest.getStarter());
		starter.removeQuest(quest);
		if (starter.getQuests().isEmpty()) starter.delete();
	}

	public void addQuest(Quest quest){
		quests.add(quest);
		NPCStarter starter = null;
		if (!npcs.containsKey(quest.getStarter())) {
			starter = new NPCStarter(quest.getStarter());
			npcs.put(quest.getStarter(), starter);
		}else starter = npcs.get(quest.getStarter());
		starter.addQuest(quest);
		quest.create();
	}

	public List<Quest> getQuests(){
		return quests;
	}

	public Map<NPC, NPCStarter> getNPCs(){
		return npcs;
	}
	
	public FileConfiguration getDataFile(){
		return data;
	}
	

	public static BeautyQuests getInstance(){
		return instance;
	}
	
}

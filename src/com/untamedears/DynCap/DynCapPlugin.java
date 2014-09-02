package com.untamedears.DynCap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.java.JavaPlugin;


public class DynCapPlugin extends JavaPlugin implements Listener {
	private DynCapCommands commands;
	private int dynamicPlayerCap = 175;
	private Logger log;
	private float averageTimeToJoin = 0F;
	private int numberOfJoins = 0;
	private List <QueueItem> loginQueue = new ArrayList<QueueItem>();
	private String firstJoinMessage;
	private String updateMessage;
	private String toFastJoinMessage;
	private String banMessage;
	private int minimumJoinTime;
	private int timeOutTime;
	private Set<String> whiteListedPlayers;
	
	public void onEnable() {
		log = this.getLogger();
		commands = new DynCapCommands(this, log);
		
		Bukkit.getPluginManager().registerEvents(this, this);
		for (String command : getDescription().getCommands().keySet()) {
			getCommand(command).setExecutor(commands);
		}

		// Give the console permission
		ConsoleCommandSender console = getServer().getConsoleSender();
		console.addAttachment(this, "dyncap.console", true);
				
		this.saveDefaultConfig();

		initConfig();
		
		this.getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable()
		{
		    @Override  
		    public void run() 
		    {
		    	removeOldQueueItems(timeOutTime);
		    }}, 0L, 100);		
	}

	public void onDisable() {}
	
	public void initConfig()
	{
		FileConfiguration config = getConfig();
		firstJoinMessage = config.getString("messages.firstJoin", "The server is full, you have been added to the login queue. Your current position is %d. Please try again in no less than 10 seconds, and no more than 60 seconds.");
		toFastJoinMessage = config.getString("messages.toFastJoin", "You rejoined way to quick, you have been moved to the end of the login queue. Next time please join no less than every 10 seconds.");
		updateMessage = config.getString("messages.updateMessage", "Your posistion in the queue is %d. Please try again in no less than 10 seconds, and no more than 60 seconds.");
		banMessage = config.getString("messages.banMessage", "You are banned. If you believe this is a mistake, message modmail at www.reddit.com/r/civcraft");
		minimumJoinTime = config.getInt("timers.minimumJoinTime", 5);
		timeOutTime = config.getInt("timers.timeOutTime", 60);
		dynamicPlayerCap = config.getInt("other.initialDynCap", 175);
		whiteListedPlayers = config.getConfigurationSection("whiteListedPlayers").getKeys(false);
	}
	
	@EventHandler
	public void onServerListPingEvent(ServerListPingEvent event) 
	{
		event.setMaxPlayers(getPlayerCap());
	}

	public void setPlayerCap(int cap) {
		dynamicPlayerCap = cap;
		//updatePlayerCap(getPlayerCount());
	}

	public int getPlayerCap() {
		return dynamicPlayerCap;
	}

	public int getPlayerCount() {
		return this.getServer().getOnlinePlayers().length;
	}
	
	public int getQueueSize()
	{
		return loginQueue.size();
	}
	
	public synchronized QueueItem getQueueItem(int queueIndex)
	{
		if (queueIndex < 0 || queueIndex >= loginQueue.size())
		{
			return null;
		}
		else
		{
			return loginQueue.get(queueIndex);
		}
	}
			
	@EventHandler(priority=EventPriority.LOWEST, ignoreCancelled = false)
	public void onAsyncPlayerPreLoginEvent(AsyncPlayerPreLoginEvent event) 
	{
		try
		{
			//log.info("login event called!");
			String playerName = event.getName().toLowerCase();
			
			//if the player is whitelisted(admin/mod)
			if (whiteListedPlayers.contains(playerName))
			{
				event.allow();
				return;
			}
			if(this.getServer().getBannedPlayers().contains(this.getServer().getOfflinePlayer(event.getName())))
			{
				event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, banMessage);
				return;
			}
			
			int position = getQueuePosition(playerName);
			//log.info("posistion is:" + position);
			//if the server is not full, and there is no queue
			if ((getPlayerCount() < getPlayerCap() && loginQueue.isEmpty()))
			{
				//log.info("allowed " + playerName + " to join, server is not full and has no queue!");
				event.allow();
				updateAverageTimeToJoin(0);
				return;
			}
			//server is either full, or has a queue
			else
			{
				//if the server has a queue, but there is enough space for the player
				if(position != -1 && position + 1 <= getPlayerCap() - getPlayerCount())
				{
					//log.info("allowed " + playerName + " to join, server is not full and he is in a queue!");
					event.allow();
					updateAverageTimeToJoin(loginQueue.get(position).getSecondsSinceFirstAttempt());
					removeFromQueue(position);
					return;
				}
				//if the server has a queue, and there is not enough space for the player
				else if (position != -1)
				{
					if (getQueueItem(position).getSecondsSinceLastAttempt() <= minimumJoinTime)
					{
						removeFromQueue(position);
						event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_FULL, toFastJoinMessage);
						return;
					}
					else
					{
					getQueueItem(position).updateDate();
					event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_FULL, String.format(updateMessage, (position + 1)));
					return;
					}
				}
				else
				{
					if ((!loginQueue.isEmpty() && loginQueue.size() <= (getPlayerCap() - getPlayerCount())))
					{
						//if for some reason the person is in the queue remove them
						if (position != -1)
						{
							removeFromQueue(position);
						}
						updateAverageTimeToJoin(0);
						event.allow();
						return;
					}
					//log.info("disallowed " + playerName + " added him to queue");
					QueueItem queueItem = new QueueItem(playerName);
					addToQueue(queueItem);
					event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_FULL, String.format(firstJoinMessage, loginQueue.size()));
					return;
				}
			}
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
	}
	
	private synchronized void removeFromQueue(int index)
	{
		loginQueue.remove(index);
	}
	private synchronized void addToQueue(QueueItem queueItem)
	{
		loginQueue.add(queueItem);
	}
	//returning -1  means error/not contained
	public synchronized int getQueuePosition(String name)
	{
		if (loginQueue.isEmpty())
		{
			//log.info("queue is empty!");
			return -1;
		}
		for (int x = 0; x < loginQueue.size(); x++)
		{
			//log.info("x is:" + x + " and queueItem name is " + queue.get(x).getName() + " while paramter is " + name);
			if (getQueueItem(x).getName().equalsIgnoreCase(name))
			{
				return x;
			}
		}
		return -1;
	}	
	
	
	public void updateAverageTimeToJoin(int newJoinTime)
	{
		numberOfJoins++;
		Float tempFloat = Integer.valueOf(numberOfJoins).floatValue();
		Float tempFloat2 = Integer.valueOf(newJoinTime).floatValue();
		averageTimeToJoin = ((tempFloat -1F)/tempFloat) * averageTimeToJoin + (tempFloat2/tempFloat);
	}
	
	public void resetAverageTimeToJoin()
	{
		averageTimeToJoin = 0F;
		numberOfJoins = 0;
	}
	
	public float getAverageTimeToJoin()
	{
		return averageTimeToJoin;
	}
	
	//timeOut is in seconds
	private synchronized void removeOldQueueItems(int timeOut)
	{
		if (loginQueue.isEmpty())
		{
			return;
		}
		for (int x = 0; x< loginQueue.size(); x++)
		{
			if (loginQueue.get(x).getSecondsSinceLastAttempt() > timeOut)
			{
				removeFromQueue(x);
				x -- ;
			}
		}
	}
	
	private void moveQueueItem(int oldIndex, int newIndex)
	{
		if (oldIndex < 0 || oldIndex >= loginQueue.size() || newIndex < 0 || newIndex >= loginQueue.size())
		{
			return;
		}
		QueueItem tempItem = loginQueue.get(oldIndex);
		loginQueue.remove(oldIndex);
		loginQueue.add(newIndex, tempItem);
	}

}


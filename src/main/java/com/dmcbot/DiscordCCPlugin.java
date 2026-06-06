package com.dmcbot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.StatChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;
import okhttp3.*;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Slf4j
@PluginDescriptor(
		name = "Dutch Mayhem Clan",
		description = "Sends OSRS clan chat to the Dutch Mayhem Clan Discord.",
		tags = {"discord", "clan", "chat", "bot", "wilderness", "loot", "split", "dmc", "dutch mayhem clan", "bingo"}
)
public class DiscordCCPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private DiscordCCConfig config;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ItemManager itemManager;

	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	// ── Bingo event tracking state ────────────────────────────────────────────

	/** Last known level per skill — used to detect level-ups. */
	private final Map<String, Integer> lastLevels = new HashMap<>();

	/** Last XP boundary (in units of XP_SEND_THRESHOLD) sent per 99+ skill. */
	private final Map<String, Integer> lastXpBoundary = new HashMap<>();

	/**
	 * How often to report XP for 99+ skills.
	 * We fire once per 100k XP gained so the bot can check XP-milestone bingo tiles
	 * without flooding the server on every single action.
	 */
	private static final int XP_SEND_THRESHOLD = 100_000;

	// ── Game message patterns ─────────────────────────────────────────────────

	private static final Pattern QUEST_PATTERN = Pattern.compile(
			"(?:Congratulations, you(?:'ve| have) completed a quest|You have completed the quest): (.+?)!?\\s*$",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern CLOG_PATTERN = Pattern.compile(
			"New item added to your collection log: (.+?)\\.",
			Pattern.CASE_INSENSITIVE);

	// Matches e.g. "Congratulations, you've completed a Easy combat task: Snuffed Out."
	private static final Pattern CA_PATTERN = Pattern.compile(
			"Congratulations, you(?:'ve| have) completed (?:a|an) (\\w+) combat (?:task|achievement): (.+?)\\.",
			Pattern.CASE_INSENSITIVE);

	// Matches e.g. "You have completed a Clue Scroll (hard)."
	private static final Pattern CLUE_PATTERN = Pattern.compile(
			"You have completed a [Cc]lue [Ss]croll \\((\\w+)\\)",
			Pattern.CASE_INSENSITIVE);

	// Matches e.g. "Congratulations, you've completed all of the Hard tasks in the Ardougne Diary."
	private static final Pattern DIARY_PATTERN = Pattern.compile(
			"Congratulations, you(?:'ve| have) completed all of the (Easy|Medium|Hard|Elite) tasks? in the (.+?) (?:Diary|diary)",
			Pattern.CASE_INSENSITIVE);

	// ── Config ────────────────────────────────────────────────────────────────

	@Provides
	DiscordCCConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DiscordCCConfig.class);
	}

	// ── Chat messages ─────────────────────────────────────────────────────────

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		ChatMessageType type = event.getType();

		// Clan chat forwarding (unchanged behaviour)
		if (type == ChatMessageType.CLAN_CHAT || type == ChatMessageType.CLAN_MESSAGE)
		{
			String message = event.getMessage();
			if (!message.startsWith("To talk in your clan"))
			{
				String senderName = Text.removeTags(event.getName());
				sendToPythonServer(senderName, message);
			}
		}

		// Personal game messages — parse for bingo-relevant events
		if (type == ChatMessageType.GAMEMESSAGE)
		{
			handleBingoGameMessage(event.getMessage());
		}
	}

	private void handleBingoGameMessage(String rawMessage)
	{
		if (!isBingoConfigured()) return;

		String rsn = getLocalRsn();
		if (rsn == null) return;

		String msg = Text.removeTags(rawMessage);
		JsonObject payload = null;
		Matcher m;

		// Quest completion
		m = QUEST_PATTERN.matcher(msg);
		if (m.find())
		{
			payload = new JsonObject();
			payload.addProperty("rsn",  rsn);
			payload.addProperty("type", "quest");
			payload.addProperty("name", m.group(1).trim());
		}

		// Collection log (new entry)
		if (payload == null)
		{
			m = CLOG_PATTERN.matcher(msg);
			if (m.find())
			{
				payload = new JsonObject();
				payload.addProperty("rsn",  rsn);
				payload.addProperty("type", "collection_log");
				payload.addProperty("item", m.group(1).trim());
			}
		}

		// Combat achievement
		if (payload == null)
		{
			m = CA_PATTERN.matcher(msg);
			if (m.find())
			{
				payload = new JsonObject();
				payload.addProperty("rsn",  rsn);
				payload.addProperty("type", "combat_achievement");
				payload.addProperty("tier", m.group(1));
				payload.addProperty("task", m.group(2).trim());
			}
		}

		// Clue scroll completion
		if (payload == null)
		{
			m = CLUE_PATTERN.matcher(msg);
			if (m.find())
			{
				payload = new JsonObject();
				payload.addProperty("rsn",        rsn);
				payload.addProperty("type",       "clue_completion");
				payload.addProperty("difficulty", m.group(1).toLowerCase());
			}
		}

		// Achievement diary
		if (payload == null)
		{
			m = DIARY_PATTERN.matcher(msg);
			if (m.find())
			{
				payload = new JsonObject();
				payload.addProperty("rsn",    rsn);
				payload.addProperty("type",   "diary");
				payload.addProperty("tier",   m.group(1));
				// Match the bot's region naming (tiles store "<area> Diary", e.g. "Varrock Diary")
				payload.addProperty("region", m.group(2).trim() + " Diary");
			}
		}

		if (payload != null)
		{
			sendBingoEvent(payload);
		}
	}

	// ── Stat changes (level-ups + XP milestones for 99+ skills) ──────────────

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (!isBingoConfigured()) return;

		String rsn = getLocalRsn();
		if (rsn == null) return;

		String skillName = event.getSkill().getName();
		int    newLevel  = event.getLevel();
		int    newXp     = event.getXp();

		// Level-up detection — first event per skill just initialises the map
		if (!lastLevels.containsKey(skillName))
		{
			lastLevels.put(skillName, newLevel);
		}
		else
		{
			int oldLevel = lastLevels.get(skillName);
			if (newLevel > oldLevel)
			{
				lastLevels.put(skillName, newLevel);

				JsonObject payload = new JsonObject();
				payload.addProperty("rsn",   rsn);
				payload.addProperty("type",  "level_up");
				payload.addProperty("skill", skillName);
				payload.addProperty("level", newLevel);
				payload.addProperty("xp",    newXp);
				sendBingoEvent(payload);
			}
		}

		// XP milestone reporting for 99+ skills
		// Send once per XP_SEND_THRESHOLD gained so the bot can detect dynamic XP-target tiles.
		if (newLevel >= 99)
		{
			int boundary = newXp / XP_SEND_THRESHOLD;

			if (!lastXpBoundary.containsKey(skillName))
			{
				lastXpBoundary.put(skillName, boundary);
			}
			else if (boundary > lastXpBoundary.get(skillName))
			{
				lastXpBoundary.put(skillName, boundary);

				JsonObject payload = new JsonObject();
				payload.addProperty("rsn",   rsn);
				payload.addProperty("type",  "xp_milestone");
				payload.addProperty("skill", skillName);
				payload.addProperty("xp",    newXp);
				sendBingoEvent(payload);
			}
		}
	}

	// ── NPC / Player loot ─────────────────────────────────────────────────────

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		// Wilderness loot split tracking (existing behaviour)
		if (isInWilderness())
		{
			sendWildyLootItems(event.getItems(), "NPC");
		}

		// Bingo drop tracking — all NPC drops regardless of location
		sendBingoDrops(event.getItems());
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived event)
	{
		if (!isInWilderness())
		{
			return;
		}
		sendWildyLootItems(event.getItems(), "PLAYER");
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		// Wilderness loot keys deposit their actual contents into a chest
		// outside the wilderness, so this path intentionally skips isInWilderness().
		String source = event.getName();
		if (source == null) return;

		String lootType;
		if (source.equalsIgnoreCase("Loot Chest"))
		{
			lootType = "PLAYER";
		}
		else if (source.equalsIgnoreCase("Larran's small chest") ||
				source.equalsIgnoreCase("Larran's big chest"))
		{
			lootType = "NPC";
		}
		else
		{
			return;
		}

		sendWildyLootItems(event.getItems(), lootType);
	}

	private static final WorldArea WILDERNESS_ABOVE_GROUND = new WorldArea(2944, 3523, 448, 448, 0);
	private static final WorldArea WILDERNESS_UNDERGROUND  = new WorldArea(2944, 9918, 320, 442, 0);

	private boolean isInWilderness()
	{
		if (client.getLocalPlayer() == null)
		{
			return false;
		}
		WorldPoint loc = client.getLocalPlayer().getWorldLocation();
		return loc.isInArea2D(WILDERNESS_ABOVE_GROUND, WILDERNESS_UNDERGROUND);
	}

	private void sendWildyLootItems(Collection<ItemStack> items, String lootType)
	{
		if (config.serverUrl() == null || config.serverUrl().isEmpty() ||
				config.secretToken() == null || config.secretToken().isEmpty())
		{
			return;
		}

		String rsn = client.getLocalPlayer() != null
				? Text.removeTags(client.getLocalPlayer().getName())
				: "";

		if (rsn.isEmpty())
		{
			return;
		}

		JsonArray itemsArray = new JsonArray();

		for (ItemStack item : items)
		{
			int    itemId   = item.getId();
			int    quantity = item.getQuantity();
			String itemName = itemManager.getItemComposition(itemId).getName();
			int    gePrice  = itemManager.getItemPrice(itemId);

			// Skip the loot-key items themselves — the real value arrives later
			// via the chest-open LootReceived event.
			if (itemName.equalsIgnoreCase("Loot key") ||
					itemName.equalsIgnoreCase("Larran's key"))
			{
				continue;
			}

			JsonObject entry = new JsonObject();
			entry.addProperty("itemId",   itemId);
			entry.addProperty("itemName", itemName);
			entry.addProperty("quantity", quantity);
			entry.addProperty("gePrice",  gePrice);
			itemsArray.add(entry);
		}

		if (itemsArray.size() == 0)
		{
			return;
		}

		JsonObject payload = new JsonObject();
		payload.addProperty("rsn",      rsn);
		payload.addProperty("lootType", lootType);
		payload.add("items", itemsArray);

		postJson(config.serverUrl() + "/api/wildy-loot", payload);
	}

	private void sendBingoDrops(Collection<ItemStack> items)
	{
		if (!isBingoConfigured()) return;

		String rsn = getLocalRsn();
		if (rsn == null) return;

		for (ItemStack item : items)
		{
			int    itemId   = item.getId();
			int    quantity = item.getQuantity();
			String itemName = itemManager.getItemComposition(itemId).getName();
			int    gePrice  = itemManager.getItemPrice(itemId);

			JsonObject payload = new JsonObject();
			payload.addProperty("rsn",      rsn);
			payload.addProperty("type",     "drop");
			payload.addProperty("item",     itemName);
			payload.addProperty("quantity", quantity);
			payload.addProperty("coins",    (long) gePrice * quantity);
			sendBingoEvent(payload);
		}
	}

	// ── Shared helpers ────────────────────────────────────────────────────────

	private void sendToPythonServer(String playerName, String message)
	{
		if (config.serverUrl() == null || config.serverUrl().isEmpty() ||
				config.secretToken() == null || config.secretToken().isEmpty())
		{
			return;
		}

		JsonObject payload = new JsonObject();
		payload.addProperty("playerName", playerName);
		payload.addProperty("message", message);

		postJson(config.serverUrl() + "/api/chat", payload);
	}

	private void sendBingoEvent(JsonObject payload)
	{
		postJson(config.serverUrl() + "/api/bingo-event", payload);
	}

	private boolean isBingoConfigured()
	{
		return config.serverUrl() != null && !config.serverUrl().isEmpty()
				&& config.secretToken() != null && !config.secretToken().isEmpty();
	}

	private String getLocalRsn()
	{
		if (client.getLocalPlayer() == null)
		{
			return null;
		}
		String rsn = Text.removeTags(client.getLocalPlayer().getName());
		return rsn.isEmpty() ? null : rsn;
	}

	private void postJson(String url, JsonObject payload)
	{
		RequestBody body    = RequestBody.create(JSON, payload.toString());
		Request     request = new Request.Builder()
				.url(url)
				.addHeader("Authorization", config.secretToken())
				.post(body)
				.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Failed to POST to {}: {}", url, e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				response.close();
			}
		});
	}
}

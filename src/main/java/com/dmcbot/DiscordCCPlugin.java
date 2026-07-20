package com.dmcbot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Slf4j
@PluginDescriptor(
		name = "Dutch Mayhem Clan",
		description = "Dutch Mayhem Clan companion: clan chat mirror to Discord, wilderness/PvP loot-split tracking and clan bingo events.",
		tags = {"discord", "clan", "chat", "bot", "wilderness", "loot", "split", "dmc", "dutch mayhem clan", "bingo", "pvp", "pk"}
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

	/** Payload format version — lets the server distinguish old/new plugin builds. */
	private static final String PLUGIN_VERSION = "2.0.0";

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

	/**
	 * On login the client replays StatChanged for every skill, sometimes with
	 * placeholder values first — without a grace period that looks like ~23
	 * simultaneous level-ups. StatChanged events inside the grace window only
	 * update the maps; nothing is sent.
	 */
	private static final int LOGIN_GRACE_TICKS = 3;
	private int ticksSinceLogin = 0;

	// ── Killer attribution ────────────────────────────────────────────────────

	/**
	 * Who killed us: interacting-at-death misses kills where we weren't fighting
	 * back. Instead we remember the last player who actually damaged us (hitsplat
	 * + targeting evidence) and use them when death follows within the timeout.
	 * Players merely targeting/following us never count without damage.
	 */
	private static final int ATTACKER_TIMEOUT_TICKS = 25; // ~15s

	private String lastAttackerName;
	private int lastAttackerTick = -1;
	private String lastTargeterName;

	// Players we dealt damage to (name → tick). A player's death within
	// ATTACKER_TIMEOUT_TICKS of our last hit on them counts as our kill —
	// the reliable signal for loot-key kills, which emit no personal
	// "You have defeated X!" game message.
	private final Map<String, Integer> damagedPlayers = new HashMap<>();

	// ── Retry queue for failed POSTs ──────────────────────────────────────────

	private static final int MAX_RETRIES = 3;
	private static final int MAX_RETRY_QUEUE = 50;
	private static final int RETRIES_PER_TICK = 2;

	private static final class PendingPost
	{
		final String url;
		final JsonObject payload;
		final int attempt;

		PendingPost(String url, JsonObject payload, int attempt)
		{
			this.url = url;
			this.payload = payload;
			this.attempt = attempt;
		}
	}

	private final ConcurrentLinkedDeque<PendingPost> retryQueue = new ConcurrentLinkedDeque<>();

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

	@Override
	protected void shutDown()
	{
		lastLevels.clear();
		lastXpBoundary.clear();
		retryQueue.clear();
		lastAttackerName = null;
		lastAttackerTick = -1;
		lastTargeterName = null;
		damagedPlayers.clear();
	}

	// ── Login/logout state ────────────────────────────────────────────────────

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOGGED_IN:
				// Fresh grace window after every (re)login and world hop
				ticksSinceLogin = 0;
				break;
			case LOGIN_SCREEN:
			case HOPPING:
				// Next login may be a different account — forget seeded levels
				lastLevels.clear();
				lastXpBoundary.clear();
				lastAttackerName = null;
				lastAttackerTick = -1;
				lastTargeterName = null;
				damagedPlayers.clear();
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (ticksSinceLogin < LOGIN_GRACE_TICKS)
		{
			ticksSinceLogin++;
		}
		flushRetryQueue();
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

		// Personal game messages — bingo-relevant events. PK kills are
		// detected from the victim's death (onActorDeath), not a chat
		// message: loot-key kills emit no "You have defeated X!" line.
		if (type == ChatMessageType.GAMEMESSAGE)
		{
			handleBingoGameMessage(event.getMessage());
		}
	}

	private void handleBingoGameMessage(String rawMessage)
	{
		if (!isConfigured()) return;

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
		if (!isConfigured()) return;

		String rsn = getLocalRsn();
		if (rsn == null) return;

		String skillName = event.getSkill().getName();
		int    newLevel  = event.getLevel();
		int    newXp     = event.getXp();

		// Inside the login grace window (and while not fully logged in) events
		// only seed/refresh the maps — no sends. This kills the login burst.
		boolean suppress = ticksSinceLogin < LOGIN_GRACE_TICKS
				|| client.getGameState() != GameState.LOGGED_IN;

		// Level-up detection — first event per skill just initialises the map
		if (!lastLevels.containsKey(skillName) || suppress)
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

			if (!lastXpBoundary.containsKey(skillName) || suppress)
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
		// NPC loot is no longer part of the wildy split-tracker (PvP only).
		// Bingo drop tracking — all NPC drops regardless of location.
		sendBingoDrops(event.getItems());
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived event)
	{
		// Wilderness + PvP/Deadman worlds (whole world is a PvP zone there)
		if (!isPvpEnvironment())
		{
			return;
		}

		String victim = event.getPlayer() != null
				? Text.removeTags(event.getPlayer().getName())
				: null;

		sendWildyLootItems(event.getItems(), false, victim);
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		// Wilderness loot keys deposit their actual contents into a chest
		// outside the wilderness, so this path intentionally skips isPvpEnvironment().
		String source = event.getName();
		if (source == null) return;

		if (source.equalsIgnoreCase("Loot Chest"))
		{
			sendWildyLootItems(event.getItems(), true, null);
		}
	}

	// ── Deaths ────────────────────────────────────────────────────────────────

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		// Identity hint only — no timestamp. Someone targeting us becomes the
		// attacker candidate, but only a hitsplat promotes them to attacker.
		if (event.getSource() instanceof Player
				&& event.getSource() != client.getLocalPlayer()
				&& event.getTarget() == client.getLocalPlayer())
		{
			lastTargeterName = Text.removeTags(event.getSource().getName());
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		Actor actor = event.getActor();

		// Damage we dealt to another player — kill-attribution candidate.
		if (actor instanceof Player && actor != client.getLocalPlayer()
				&& event.getHitsplat().isMine() && actor.getName() != null)
		{
			damagedPlayers.put(Text.removeTags(actor.getName()), client.getTickCount());
		}

		if (actor != client.getLocalPlayer()) return;

		// Damage landed on us — attribute it to whoever is targeting us right
		// now, falling back to the last player seen targeting us.
		String attacker = null;
		for (Player p : client.getPlayers())
		{
			if (p != client.getLocalPlayer() && p.getInteracting() == client.getLocalPlayer())
			{
				attacker = Text.removeTags(p.getName());
				break;
			}
		}
		if (attacker == null)
		{
			attacker = lastTargeterName;
		}

		if (attacker != null)
		{
			lastAttackerName = attacker;
			lastAttackerTick = client.getTickCount();
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Actor actor = event.getActor();

		// Another player died — our kill if we damaged them recently.
		if (actor instanceof Player && actor != client.getLocalPlayer())
		{
			handlePotentialKill((Player) actor);
			return;
		}

		if (actor != client.getLocalPlayer()) return;
		if (!isConfigured() || !isPvpEnvironment()) return;

		String rsn = getLocalRsn();
		if (rsn == null) return;

		// Recent damage-dealer first; fallback: whoever we were interacting with
		String killer = null;
		if (lastAttackerName != null
				&& client.getTickCount() - lastAttackerTick <= ATTACKER_TIMEOUT_TICKS)
		{
			killer = lastAttackerName;
		}
		if (killer == null)
		{
			Actor interacting = client.getLocalPlayer().getInteracting();
			if (interacting instanceof Player)
			{
				killer = Text.removeTags(interacting.getName());
			}
		}

		JsonObject payload = new JsonObject();
		payload.addProperty("rsn", rsn);
		if (killer != null && !killer.isEmpty())
		{
			payload.addProperty("killer", killer);
		}
		addPosition(payload);
		postJson(config.serverUrl() + "/api/wildy-death", payload);
	}

	/**
	 * A nearby player died — attribute it to us only if we dealt them damage
	 * within ATTACKER_TIMEOUT_TICKS. Works for loot-key kills (no "defeated"
	 * message) and plots at the victim's tile, i.e. the actual kill spot.
	 */
	private void handlePotentialKill(Player victim)
	{
		if (!isConfigured() || !isPvpEnvironment()) return;
		if (victim.getName() == null) return;

		String rsn = getLocalRsn();
		if (rsn == null) return;

		String victimName = Text.removeTags(victim.getName());
		Integer hitTick = damagedPlayers.remove(victimName);
		if (hitTick == null || client.getTickCount() - hitTick > ATTACKER_TIMEOUT_TICKS)
		{
			return; // we did not (recently) damage them — not our kill
		}

		JsonObject payload = new JsonObject();
		payload.addProperty("rsn",    rsn);
		payload.addProperty("victim", victimName);
		addPosition(payload, victim);
		postJson(config.serverUrl() + "/api/wildy-kill", payload);
	}

	// ── Location helpers ──────────────────────────────────────────────────────

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

	private boolean isPvpWorld()
	{
		EnumSet<WorldType> types = client.getWorldType();
		return types.contains(WorldType.PVP) || types.contains(WorldType.DEADMAN);
	}

	private boolean isPvpEnvironment()
	{
		return isInWilderness() || isPvpWorld();
	}

	/** Adds our own position — used for deaths. */
	private void addPosition(JsonObject payload)
	{
		addPosition(payload, client.getLocalPlayer());
	}

	/** Adds the given actor's position, world and client timestamp to a payload. */
	private void addPosition(JsonObject payload, Actor actor)
	{
		if (actor != null)
		{
			WorldPoint loc = actor.getWorldLocation();
			payload.addProperty("x",     loc.getX());
			payload.addProperty("y",     loc.getY());
			payload.addProperty("plane", loc.getPlane());
		}
		payload.addProperty("world", client.getWorld());
		payload.addProperty("ts",    System.currentTimeMillis());
	}

	// ── Senders ───────────────────────────────────────────────────────────────

	private void sendWildyLootItems(Collection<ItemStack> items, boolean keyed, String victim)
	{
		if (!isConfigured())
		{
			return;
		}

		String rsn = getLocalRsn();
		if (rsn == null)
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
		payload.addProperty("lootType", "PLAYER");
		payload.addProperty("keyed",    keyed);
		if (victim != null && !victim.isEmpty())
		{
			payload.addProperty("victim", victim);
		}
		addPosition(payload);
		payload.add("items", itemsArray);

		postJson(config.serverUrl() + "/api/wildy-loot", payload);
	}

	/** One batched request per loot event instead of one request per item. */
	private void sendBingoDrops(Collection<ItemStack> items)
	{
		if (!isConfigured()) return;

		String rsn = getLocalRsn();
		if (rsn == null) return;

		JsonArray itemsArray = new JsonArray();

		for (ItemStack item : items)
		{
			int    itemId   = item.getId();
			int    quantity = item.getQuantity();
			String itemName = itemManager.getItemComposition(itemId).getName();
			int    gePrice  = itemManager.getItemPrice(itemId);

			JsonObject entry = new JsonObject();
			entry.addProperty("item",     itemName);
			entry.addProperty("quantity", quantity);
			entry.addProperty("coins",    (long) gePrice * quantity);
			itemsArray.add(entry);
		}

		if (itemsArray.size() == 0) return;

		JsonObject payload = new JsonObject();
		payload.addProperty("rsn",  rsn);
		payload.addProperty("type", "drop_batch");
		payload.add("items", itemsArray);
		sendBingoEvent(payload);
	}

	// ── Shared helpers ────────────────────────────────────────────────────────

	private void sendToPythonServer(String playerName, String message)
	{
		if (!isConfigured())
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

	private boolean isConfigured()
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
		postJson(url, payload, 0);
	}

	private void postJson(String url, JsonObject payload, int attempt)
	{
		payload.addProperty("plugin_version", PLUGIN_VERSION);

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
				// Network failure only (auth/4xx responses land in onResponse and
				// are never retried). Requeue with backoff via the game-tick drain.
				if (attempt < MAX_RETRIES && retryQueue.size() < MAX_RETRY_QUEUE)
				{
					retryQueue.add(new PendingPost(url, payload, attempt + 1));
				}
				else
				{
					log.warn("Failed to POST to {} (giving up): {}", url, e.getMessage());
				}
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				response.close();
			}
		});
	}

	/** Drains a couple of failed POSTs per game tick (~0.6s spacing). */
	private void flushRetryQueue()
	{
		for (int i = 0; i < RETRIES_PER_TICK; i++)
		{
			PendingPost pending = retryQueue.poll();
			if (pending == null)
			{
				return;
			}
			postJson(pending.url, pending.payload, pending.attempt);
		}
	}
}

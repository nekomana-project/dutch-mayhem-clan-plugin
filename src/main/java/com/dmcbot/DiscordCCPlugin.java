package com.dmcbot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;
import okhttp3.*;

import java.io.IOException;
import java.util.Collection;


@Slf4j
@PluginDescriptor(
		name = "Dutch Mayhem Clan",
		description = "Sends OSRS clan chat to the Dutch Mayhem Clan Discord bot and tracks wilderness loot splits",
		tags = {"discord", "clan", "chat", "bot", "wilderness", "loot", "split", "dmc", "dutch mayhem clan"}
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

	@Provides
	DiscordCCConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DiscordCCConfig.class);
	}

	// ── Clan chat ─────────────────────────────────────────────────────────────

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.CLAN_CHAT && event.getType() != ChatMessageType.CLAN_MESSAGE)
		{
			return;
		}

		String message = event.getMessage();

		// Filter the clan channel join prompt sent on login
		if (message.startsWith("To talk in your clan"))
		{
			return;
		}

		String senderName = Text.removeTags(event.getName());
		sendToPythonServer(senderName, message);
	}

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

		postJson(config.serverUrl(), payload);
	}

	// ── Wilderness loot ───────────────────────────────────────────────────────

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		if (!isInWilderness())
		{
			return;
		}
		sendLootItems(event.getItems(), "NPC");
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived event)
	{
		if (!isInWilderness())
		{
			return;
		}
		sendLootItems(event.getItems(), "PLAYER");
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

	private void sendLootItems(Collection<ItemStack> items, String lootType)
	{
		if (config.wildyLootUrl() == null || config.wildyLootUrl().isEmpty() ||
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
		payload.addProperty("rsn",          rsn);
		payload.addProperty("lootType",     lootType);
		payload.addProperty("minLootValue", config.minLootValue());
		payload.addProperty("sendNpcLoot",  config.sendNpcLoot());
		payload.addProperty("sendPvpLoot",  config.sendPvpLoot());
		payload.add("items", itemsArray);

		postJson(config.wildyLootUrl(), payload);
	}

	// ── Shared HTTP helper ────────────────────────────────────────────────────

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

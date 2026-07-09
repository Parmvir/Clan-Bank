package com.creepsinc.clanbank;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

/**
 * Companion to a self-hosted Discord clan bank bot (see the bot's own repo
 * for setup — each clan runs their own instance and points this plugin at
 * it). Polls the bot's local API for the logged-in player's own loan status
 * and clan name, and lets them request/settle a loan from the sidebar
 * panel. Does not read game state beyond the player's own name, and never
 * sends input or automates any in-game action — every action still goes
 * through the bot's normal officer-approval flow.
 */
@Slf4j
@PluginDescriptor(
	name = "Clan Bank",
	description = "Shows your clan bank loan status in-game, backed by a self-hosted companion Discord bot",
	tags = {"clan", "bank", "loans", "overlay"}
)
public class ClanBankPlugin extends Plugin
{
	private static final int MIN_REFRESH_SECONDS = 10;

	@Inject
	private Client client;

	@Inject
	private ClanBankConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ClanBankOverlay overlay;

	@Inject
	private ClanBankPanel panel;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ClanBankApiClient apiClient;

	@Inject
	private ScheduledExecutorService executor;

	private NavigationButton navButton;
	private ScheduledFuture<?> pollTask;
	private volatile ClanBankStatus status;
	private volatile String lastError;

	@Provides
	ClanBankConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ClanBankConfig.class);
	}

	@Override
	protected void startUp()
	{
		if (config.showOverlay())
		{
			overlayManager.add(overlay);
		}

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Clan Bank")
			.icon(icon)
			.priority(5)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		int interval = Math.max(MIN_REFRESH_SECONDS, config.refreshSeconds());
		pollTask = executor.scheduleWithFixedDelay(this::poll, 0, interval, TimeUnit.SECONDS);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		clientToolbar.removeNavigation(navButton);
		if (pollTask != null)
		{
			pollTask.cancel(true);
			pollTask = null;
		}
		status = null;
		lastError = null;
	}

	private void poll()
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			return;
		}

		String rsn = client.getLocalPlayer().getName();
		if (rsn == null || rsn.isEmpty())
		{
			return;
		}

		if (config.apiKey() == null || config.apiKey().isEmpty())
		{
			lastError = "Set an API key in plugin settings";
			status = null;
			panel.update(status, lastError);
			return;
		}

		try
		{
			status = apiClient.fetchStatus(config.apiUrl(), config.apiKey(), rsn);
			lastError = null;
		}
		catch (IOException e)
		{
			log.debug("Failed to fetch clan bank status", e);
			lastError = "Bot unreachable";
			status = null;
		}

		panel.update(status, lastError);
	}

	ClanBankStatus getStatus()
	{
		return status;
	}

	String getLastError()
	{
		return lastError;
	}
}

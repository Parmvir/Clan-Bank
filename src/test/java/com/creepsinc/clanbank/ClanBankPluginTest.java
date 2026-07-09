package com.creepsinc.clanbank;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Run this class directly from IntelliJ (right-click > Run) to launch a full
 * RuneLite client with this plugin already loaded and enabled — no need for
 * any third-party sideloading tool. This is RuneLite's own standard way of
 * running a plugin under development; it uses your existing RuneLite
 * profile/login from ~/.runelite, same as your normal client.
 */
public class ClanBankPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ClanBankPlugin.class);
		RuneLite.main(args);
	}
}

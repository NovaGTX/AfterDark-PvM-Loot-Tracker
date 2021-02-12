package com.AfterDarkLootTracker;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AfterDarkLootTrackerTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AfterDarkLootTrackerPlugin.class);
		RuneLite.main(args);
	}
}
package com.AfterDarkLootTracker;

import com.google.inject.Provides;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigManager;

@ConfigGroup("AfterDarkLootTracker")
public interface AfterDarkLootTrackerPluginConfig extends Config {
    @ConfigItem(
            keyName = "userID",
            name = "User ID",
            description = "The ID used to authenticate users",
            position = 1
    )
    default String userID() {
        return "";
    }

    @ConfigItem(
            keyName = "authToken",
            name = "Auth Token",
            description = "The key used to authenticate users",
            position = 2
    )
    default String authToken() {
        return "";
    }

}

package com.AfterDarkLootTracker;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Provides;

import javax.inject.Inject;


import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.WidgetID;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;
import org.bson.Document;


import java.lang.reflect.Field;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@Slf4j
@PluginDescriptor(
        name = "AfterDark Loot Tracker",
        description = "Automatically stores PvM competition drops in the AfterDark database.",
        tags = {"pvm", "loot", "tracker", "afterdark"}
)
public class AfterDarkLootTrackerPlugin extends Plugin {
    @Inject
    private Client client;

    @Inject
    private ChatMessageManager chatMessageManager;

    @Inject
    private ItemManager itemManager;

    @Inject
    private AfterDarkLootTrackerPluginConfig config;

    @VisibleForTesting
    String eventType;
    @VisibleForTesting
    LootRecordType lootRecordType;

    MongoDatabase database;
    private static final DateFormat DATE_FORMAT = new SimpleDateFormat("MMM. dd, yyyy");

    RareItemIds obj = new RareItemIds();
    int[] ids = obj.getIds();

    // Activity/Event loot handling
    private boolean chestLooted;
    private static final Pattern CLUE_SCROLL_PATTERN = Pattern.compile("You have completed [0-9]+ ([a-z]+) Treasure Trails?\\.");
    private static final int THEATRE_OF_BLOOD_REGION = 12867;


    @Override
    protected void startUp() throws Exception {
        log.info("AfterDarkLootTracker started!");
        try {

            MongoClient mongoClient = MongoClients.create(
                    "mongodb+srv://" + config.userID() + ":" + config.authToken() + "@db-cluster.a66g4.mongodb.net/AfterDarkDrops?retryWrites=true&w=majority");

            database = mongoClient.getDatabase("AfterDarkDrops");

            //count check for authentication... MongoDB server connections are created on daemon threads
            //long story short you'll not to able to check the connection related errors while creating the Mongo Client
            database.getCollection("AfterDarkDrops").countDocuments();

            log.info("---------------------Connected to Database ---------------------------");
        } catch (Exception e) {
            log.error(e.getMessage());
            shutDown();
        }

    }

    @Override
    protected void shutDown() {
        log.info("AfterDarkLootTracker stopped!");
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged gameStateChanged) {
        if (gameStateChanged.getGameState() == GameState.LOGGED_IN) {
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "AfterDark Loot Tracker connected to server.", null);
        }
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived npcLootReceived) {
        Collection<ItemStack> items = npcLootReceived.getItems();
        lootReceived(items, npcLootReceived.getNpc().getName());
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded widgetLoaded) {
        final ItemContainer container;

        switch (widgetLoaded.getGroupId()) {
            case (WidgetID.BARROWS_REWARD_GROUP_ID):
                setEvent(LootRecordType.EVENT, "Barrows");
                container = client.getItemContainer(InventoryID.BARROWS_REWARD);
                break;
            case (WidgetID.CHAMBERS_OF_XERIC_REWARD_GROUP_ID):
                if (chestLooted) {
                    return;
                }
                setEvent(LootRecordType.EVENT, "Chambers of Xeric");
                container = client.getItemContainer(InventoryID.CHAMBERS_OF_XERIC_CHEST);
                chestLooted = true;
                break;
            case (WidgetID.THEATRE_OF_BLOOD_GROUP_ID):
                if (chestLooted) {
                    return;
                }
                int region = WorldPoint.fromLocalInstance(client, client.getLocalPlayer().getLocalLocation()).getRegionID();
                if (region != THEATRE_OF_BLOOD_REGION) {
                    return;
                }
                setEvent(LootRecordType.EVENT, "Theatre of Blood");
                container = client.getItemContainer(InventoryID.THEATRE_OF_BLOOD_CHEST);
                chestLooted = true;
                break;
            case (WidgetID.CLUE_SCROLL_REWARD_GROUP_ID):
                // event type should be set via ChatMessage for clue scrolls.
                // Clue Scrolls use same InventoryID as Barrows
                container = client.getItemContainer(InventoryID.BARROWS_REWARD);

                if (eventType == null) {
                    log.debug("Clue scroll reward interface with no event!");
                    return;
                }
                break;
            default:
                return;
        }

        if (container == null) {
            return;
        }

        // Convert container items to array of ItemStack
        final Collection<ItemStack> items = Arrays.stream(container.getItems())
                .filter(item -> item.getId() > 0)
                .map(item -> new ItemStack(item.getId(), item.getQuantity(), client.getLocalPlayer().getLocalLocation()))
                .collect(Collectors.toList());


        if (items.isEmpty()) {
            log.debug("No items to find for Event: {} | Container: {}", eventType, container);
            return;
        }

        lootReceived(items, eventType.toString());
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM) {
            return;
        }

        final String message = event.getMessage();

        // Check if message is for a clue scroll reward
        final Matcher m = CLUE_SCROLL_PATTERN.matcher(Text.removeTags(message));
        if (m.find()) {
            final String type = m.group(1).toLowerCase();
            switch (type) {
                case "beginner":
                    setEvent(LootRecordType.EVENT, "Clue Scroll (Beginner)");
                    return;
                case "easy":
                    setEvent(LootRecordType.EVENT, "Clue Scroll (Easy)");
                    return;
                case "medium":
                    setEvent(LootRecordType.EVENT, "Clue Scroll (Medium)");
                    return;
                case "hard":
                    setEvent(LootRecordType.EVENT, "Clue Scroll (Hard)");
                    return;
                case "elite":
                    setEvent(LootRecordType.EVENT, "Clue Scroll (Elite)");
                    return;
                case "master":
                    setEvent(LootRecordType.EVENT, "Clue Scroll (Master)");
                    return;
            }
        }
    }

    private void lootReceived(Collection<ItemStack> items, String npcName) {
        for (ItemStack item : items) {
            if (item != null) {
                if (contains(ids, item.getId()) && !client.getWorldType().equals(WorldType.TOURNAMENT) && !client.getWorldType().equals(WorldType.LEAGUE)) {
                    ItemComposition itemComposition = itemManager.getItemComposition(item.getId());
                    lootReceivedChatMessage(itemComposition);
                    Thread databaseThread = new Thread() {
                        public void run() {
                            connectToDatabase(database, client.getLocalPlayer().getName(), itemComposition.getName(), npcName);
                        }
                    };
                    databaseThread.start();
                }

            }
        }
    }

    private void setEvent(LootRecordType lootRecordType, String eventType) {
        this.lootRecordType = lootRecordType;
        this.eventType = eventType;
    }

    private void resetEvent() {
        lootRecordType = null;
        eventType = null;
    }

    private void lootReceivedChatMessage(final ItemComposition items) {
        final String message = new ChatMessageBuilder()
                .append(ChatColorType.HIGHLIGHT)
                .append("Your item: [")
                .append(items.getName())
                .append("] was recorded in the AfterDark PvM Competition Database.")
                .build();

        chatMessageManager.queue(
                QueuedMessage.builder()
                        .type(ChatMessageType.CONSOLE)
                        .runeLiteFormattedMessage(message)
                        .build());
    }

    private void connectToDatabase(MongoDatabase database, String playerName, String item, String npc) {
        String date = DATE_FORMAT.format(new Date());

        //Preparing a document
        Document document = new Document();
        document.append("player", playerName);
        document.append("item", item);
        document.append("npc", npc);
        document.append("date", date);
        //Inserting the document into the collection
        database.getCollection("AfterDarkDrops").insertOne(document);
        //Reset event
        resetEvent();
    }

    @Provides
    AfterDarkLootTrackerPluginConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(AfterDarkLootTrackerPluginConfig.class);
    }

    public static boolean contains(final int[] arr, final int key) {
        return Arrays.stream(arr).anyMatch(i -> i == key);
    }

}

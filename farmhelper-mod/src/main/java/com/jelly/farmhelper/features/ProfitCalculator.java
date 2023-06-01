package com.jelly.farmhelper.features;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.jelly.farmhelper.config.enums.MacroEnum;
import com.jelly.farmhelper.config.interfaces.FarmConfig;
import com.jelly.farmhelper.config.interfaces.MiscConfig;
import com.jelly.farmhelper.config.interfaces.ProfitCalculatorConfig;
import com.jelly.farmhelper.events.BlockChangeEvent;
import com.jelly.farmhelper.gui.Stat;
import com.jelly.farmhelper.macros.MacroHandler;
import com.jelly.farmhelper.network.APIHelper;
import com.jelly.farmhelper.utils.Clock;
import com.jelly.farmhelper.utils.LogUtils;
import com.jelly.farmhelper.utils.ScoreboardUtils;
import com.jelly.farmhelper.utils.Utils;
import gg.essential.elementa.UIComponent;
import gg.essential.elementa.components.UIImage;
import gg.essential.elementa.state.BasicState;
import net.minecraft.block.BlockCrops;
import net.minecraft.block.BlockNetherWart;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StringUtils;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.json.simple.JSONObject;

import java.util.*;
import java.util.concurrent.TimeUnit;

public class ProfitCalculator {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public static long realProfit = 0;
    public static long blocksBroken = 0;

    public static final BasicState<String> profit = new BasicState<>("$0");
    public static final BasicState<String> profitHr = new BasicState<>("$0");
    public static final BasicState<String> runtime = new BasicState<>("0h 0m 0s");
    public static final BasicState<String> blocksPerSecond = new BasicState<>("0 BPS");
    public static Multimap<String, DroppedItem> itemsDropped = ArrayListMultimap.create();

    public static final HashMap<String, GuiItem> dropToShow = new HashMap<>();

    private static final String path = "/assets/farmhelper/textures/gui/";
    private static boolean cantConnectToApi = false;

    public static final List<BazaarItem> cropsToCount = new ArrayList<BazaarItem>() {{
        add(new BazaarItem("Hay Bale", "ENCHANTED_HAY_BLOCK", HAY_ENCHANTED_TIER_1, 54).setImage());
        add(new BazaarItem("Seeds", "ENCHANTED_SEEDS", ENCHANTED_TIER_1, 3).setImage());
        add(new BazaarItem("Carrot", "ENCHANTED_CARROT", ENCHANTED_TIER_1, 3).setImage());
        add(new BazaarItem("Potato", "ENCHANTED_POTATO", ENCHANTED_TIER_1, 3).setImage());
        add(new BazaarItem("Melon", "ENCHANTED_MELON_BLOCK", ENCHANTED_TIER_2, 2).setImage());
        add(new BazaarItem("Pumpkin", "ENCHANTED_PUMPKIN", ENCHANTED_TIER_2, 10).setImage());
        add(new BazaarItem("Sugar Cane", "ENCHANTED_SUGAR_CANE", ENCHANTED_TIER_2, 4).setImage());
        add(new BazaarItem("Cocoa Beans", "ENCHANTED_COCOA", ENCHANTED_TIER_1, 3).setImage());
        add(new BazaarItem("Nether Wart", "MUTANT_NETHER_STALK", ENCHANTED_TIER_2, 4).setImage());
        add(new BazaarItem("Cactus Green", "ENCHANTED_CACTUS", ENCHANTED_TIER_2, 3).setImage());
        add(new BazaarItem("Red Mushroom", "ENCHANTED_RED_MUSHROOM", ENCHANTED_TIER_1, 10).setImage());
        add(new BazaarItem("Brown Mushroom", "ENCHANTED_BROWN_MUSHROOM", ENCHANTED_TIER_1, 10).setImage());
    }};

    public static final List<BazaarItem> rngDropToCount = new ArrayList<BazaarItem>() {{
        add(new BazaarItem("Cropie", "CROPIE", 1, 25_000).setImage());
        add(new BazaarItem("Squash", "SQUASH", 1, 75_000).setImage());
        add(new BazaarItem("Fermento", "FERMENTO", 1, 250_000).setImage());
        add(new BazaarItem("Burrowing Spores", "BURROWING_SPORES", 1, 1).setImage());
    }};

    public static HashMap<String, Double> bazaarPrices = new HashMap<>();

    public static final int ENCHANTED_TIER_1 = 160;
    public static final int ENCHANTED_TIER_2 = 25600;
    public static final int HAY_ENCHANTED_TIER_1 = 144; // enchanted hay bale

    private final Clock updateClock = new Clock();
    private static final Clock updateBazaarClock = new Clock();

    public static final List<String> rngDropItemsList = Arrays.asList("Cropie", "Squash", "Fermento", "Burrowing Spores");

    public static float startingPurse = -1;

    @SubscribeEvent
    public void onTickUpdateProfit(TickEvent.ClientTickEvent event) {
        if (!MacroHandler.isMacroing) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (updateClock.passed()) {
            updateClock.reset();
            updateClock.schedule(100);

            long totalProfit = 0;
            long totalProfitBasedOnConditions = 0;

            for (DroppedItem item : itemsDropped.values()) {
                if (item.amount < 0) continue;
                if (bazaarPrices.containsKey(StringUtils.stripControlCodes(item.name))) {
                    Optional<BazaarItem> isCrop = cropsToCount.stream().filter(crop -> StringUtils.stripControlCodes(item.name).equalsIgnoreCase(crop.localizedName)).findFirst();
                    if (isCrop.isPresent()) {
                        BazaarItem crop = isCrop.get();
                        double price = bazaarPrices.get(crop.localizedName);
                        totalProfit += price * (item.amount * 1.0f / crop.amountToEnchanted);
                        totalProfitBasedOnConditions += price * (item.amount * 1.0f / crop.amountToEnchanted);
                        dropToShow.put(item.name, new GuiItem((int) Math.floor(item.amount * 1.0F / crop.amountToEnchanted), crop.image));
                    } else {
                        Optional<BazaarItem> isArmor = rngDropToCount.stream().filter(armor -> StringUtils.stripControlCodes(item.name).equalsIgnoreCase(armor.localizedName)).findFirst();
                        if (isArmor.isPresent()) {
                            double price = bazaarPrices.get(isArmor.get().localizedName);
                            totalProfit += price * item.amount * 1.0f;
                            if (ProfitCalculatorConfig.countRNGtoHourly) {
                                totalProfitBasedOnConditions += price * item.amount * 1.0f;
                            }
                            dropToShow.put(item.name, new GuiItem(item.amount, isArmor.get().image));
                        }
                    }
                } else if (cantConnectToApi) {
                    Optional<BazaarItem> optional = cropsToCount.stream().filter(bzItem -> bzItem.localizedName.equals(StringUtils.stripControlCodes(item.name))).findAny();
                    if (optional.isPresent()) {
                        double price = optional.get().npcPrice * item.amount;
                        totalProfit += price;
                        totalProfitBasedOnConditions += price;
                        dropToShow.put(item.name, new GuiItem((int) Math.floor(item.amount * 1.0F / optional.get().amountToEnchanted), optional.get().image));
                    }
                }
            }

            realProfit = totalProfit;
            realProfit += checkForBountiful();
            totalProfitBasedOnConditions += checkForBountiful();
            profit.set("$" + Utils.formatNumber(Math.round(realProfit * 0.95)));
            profitHr.set("$" + Utils.formatNumber(Math.round(getHourProfit(totalProfitBasedOnConditions * 0.95))));
            runtime.set(Utils.formatTime(System.currentTimeMillis() - MacroHandler.startTime));
            float bps = Math.round((((FarmConfig.cropType == MacroEnum.SUGARCANE || FarmConfig.cropType == MacroEnum.CACTUS) ? 0.5f : 1) * blocksBroken) / (System.currentTimeMillis() - MacroHandler.startTime) * 10000f) / 10f;
            blocksPerSecond.set(bps + " BPS");
        }
    }

    @SubscribeEvent
    public void onBlockChange(BlockChangeEvent event) {
        if (!MacroHandler.isMacroing) return;
        switch (FarmConfig.cropType) {
            case CACTUS:
                if (event.old.getBlock() == Blocks.cactus && event.update.getBlock() != Blocks.cactus) {
                    blocksBroken++;
                }
                break;
            case PUMPKIN_MELON:
                if (event.old.getBlock() == Blocks.pumpkin && event.update.getBlock() != Blocks.pumpkin) {
                    blocksBroken++;
                }
                if (event.old.getBlock() == Blocks.melon_block && event.update.getBlock() != Blocks.melon_block) {
                    blocksBroken++;
                }
                break;
            case SUGARCANE:
                if (event.old.getBlock() == Blocks.reeds && event.update.getBlock() != Blocks.reeds) {
                    blocksBroken ++;
                }
                break;
            case MUSHROOM: case MUSHROOM_TP_PAD:
                if (event.old.getBlock() == Blocks.brown_mushroom && event.update.getBlock() != Blocks.brown_mushroom) {
                    blocksBroken ++;
                }
                if (event.old.getBlock() == Blocks.red_mushroom && event.update.getBlock() != Blocks.red_mushroom) {
                    blocksBroken ++;
                }
                break;
            case COCOABEANS:
                if (event.old.getBlock() == Blocks.cocoa && event.update.getBlock() != Blocks.cocoa) {
                    blocksBroken ++;
                }
                break;
            case CARROT_NW_WHEAT_POTATO:
                if (event.old.getBlock() instanceof BlockCrops && !(event.update.getBlock() instanceof BlockCrops)) {
                    blocksBroken ++;
                }
                if (event.old.getBlock() instanceof BlockNetherWart && !(event.update.getBlock() instanceof BlockNetherWart)) {
                    blocksBroken ++;
                }
                break;
        }

    }

    private static float checkForBountiful() {
        ItemStack currentItem = mc.thePlayer.getCurrentEquippedItem();
        if (currentItem == null || currentItem.getItem() == null || !currentItem.getDisplayName().contains("Bountiful")) {
            return 0;
        }
        float currentPurse = getCurrentPurse();
        if (currentPurse == 0) {
            return 0;
        }
        if (startingPurse == -1) {
            startingPurse = currentPurse;
        } else {
            return (currentPurse - startingPurse);
        }
        return 0;
    }

    public static float getCurrentPurse() {
        float currentPurse = 0;

        for (String l : ScoreboardUtils.getScoreboardLines()) {
            String line = ScoreboardUtils.cleanSB(l);
            if (line.contains("Purse:") || line.contains("Piggy:")) {
                try {
                    currentPurse = Float.parseFloat(StringUtils.stripControlCodes(line).split(" ")[1].replace(",", "").replace("(+1)", "").trim());
                    break;
                } catch (Exception ignored) {
                    return 0;
                }
            }
        }
        return currentPurse;
    }

    @SubscribeEvent
    public void onTickUpdateBazaarPrices(TickEvent.ClientTickEvent event) {
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (updateBazaarClock.passed()) {
            updateBazaarClock.reset();
            updateBazaarClock.schedule(1000 * 60 * 5);
            new Thread(ProfitCalculator::fetchBazaarPrices).start();
        }
    }

    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        if (!MacroHandler.isMacroing) return;
        if (event.type != 0) return;
        String message = StringUtils.stripControlCodes(event.message.getUnformattedText());
        Optional<String> optional = rngDropItemsList.stream().filter(message::contains).findAny();
        if (!optional.isPresent()) return;

        String itemName = optional.get();
        LogUtils.debugLog("Rng drop detected: " + itemName);
        if (itemsDropped.containsKey(itemName)) {
            Collection<DroppedItem> droppedItem = itemsDropped.get(itemName);
            if (droppedItem.size() == 0) {
                itemsDropped.put(itemName, new DroppedItem(itemName, 1));
            } else {
                boolean added = false;
                for (DroppedItem loopDiff : droppedItem) {
                    if (loopDiff.amount > 0) {
                        loopDiff.add(1);
                        added = true;
                        break;
                    }
                }
                if (!added) {
                    itemsDropped.get(itemName).add(new DroppedItem(itemName, 1));
                }
            }
        } else {
            itemsDropped.put(itemName, new DroppedItem(itemName, 1));
        }
    }

    @SubscribeEvent
    public void onRenderGui(RenderGameOverlayEvent event) {
        if (!MacroHandler.isMacroing) return;
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        if (!MiscConfig.debugMode) return;
        if (mc.theWorld == null || mc.thePlayer == null) return;

        int x = 210;

        mc.fontRendererObj.drawStringWithShadow("Bountiful: " + checkForBountiful(), x, 2, 0xFFFFFF);
    }

    public static void onInventoryChanged(ItemStack item, int size) {
        if (rngDropItemsList.stream().anyMatch(item.getDisplayName()::contains)) {
            LogUtils.debugLog("Rng drop detected: " + item.getDisplayName());
            return;
        }
        if (itemsDropped.containsKey(StringUtils.stripControlCodes(item.getDisplayName()))) {
            Collection<DroppedItem> droppedItem = itemsDropped.get(StringUtils.stripControlCodes(item.getDisplayName()));
            if (droppedItem.size() == 0) {
                itemsDropped.put(StringUtils.stripControlCodes(item.getDisplayName()), new DroppedItem(StringUtils.stripControlCodes(item.getDisplayName()), size));
            } else {
                boolean added = false;
                for (DroppedItem loopDiff : droppedItem) {
                    if ((size < 0 && loopDiff.amount < 0) || (size > 0 && loopDiff.amount > 0)) {
                        loopDiff.add(size);
                        added = true;
                    }
                }
                if (!added) {
                    if (size < 0) return;
                    itemsDropped.put(StringUtils.stripControlCodes(item.getDisplayName()), new DroppedItem(StringUtils.stripControlCodes(item.getDisplayName()), size));
                }
            }
        } else {
            itemsDropped.put(StringUtils.stripControlCodes(item.getDisplayName()), new DroppedItem(StringUtils.stripControlCodes(item.getDisplayName()), size));
        }
    }

    public static String getImageName(String name) {
        switch (name) {
            case "Hay Bale":
                return "ehaybale.png";
            case "Seeds":
                return "eseeds.png";
            case "Carrot":
                return "ecarrot.png";
            case "Potato":
                return "epotato.png";
            case "Melon":
                return "emelon.png";
            case "Pumpkin":
                return "epumpkin.png";
            case "Sugar Cane":
                return "ecane.png";
            case "Cocoa Beans":
                return "ecocoabeans.png";
            case "Nether Wart":
                return "mnw.png";
            case "Cactus Green":
                return "ecactus.png";
            case "Red Mushroom":
                return "eredmushroom.png";
            case "Brown Mushroom":
                return "ebrownmushroom.png";
            case "Cropie":
                return "cropie.png";
            case "Squash":
                return "squash.png";
            case "Fermento":
                return "fermento.png";
            case "Burrowing Spores":
                return "burrowingspores.png";
            default:
                throw new IllegalArgumentException("No image for " + name);
        }
    }

    public static double getHourProfit(double total) {
        if (TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - MacroHandler.startTime) > 0) {
            return 3600f * total / TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - MacroHandler.startTime);
        }
        return 0;
    }

    public static void resetProfit() {
        blocksBroken = 0;
        itemsDropped.clear();
        dropToShow.clear();
    }

    private static class DroppedItem {
        public String name;
        public int amount;

        public DroppedItem(String name, int amount) {
            this.name = name;
            this.amount = amount;
        }

        public void add(int amount) {
            this.amount += amount;
        }
    }

    public static void fetchBazaarPrices() {
        updateBazaarClock.reset();
        try {
            JSONObject json = APIHelper.readJsonFromUrl("https://api.hypixel.net/skyblock/bazaar","User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_11_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/50.0.2661.102 Safari/537.36");
            JSONObject json1 = (JSONObject) json.get("products");

            for (BazaarItem item : cropsToCount) {
                JSONObject json2 = (JSONObject) json1.get(item.bazaarId);
                JSONObject json3 = (JSONObject) json2.get("quick_status");
                bazaarPrices.put(item.localizedName, (Double) (json3).get("buyPrice"));
            }
            for (BazaarItem bazaarItem : rngDropToCount) {
                JSONObject json2 = (JSONObject) json1.get(bazaarItem.bazaarId);
                JSONObject json3 = (JSONObject) json2.get("quick_status");
                bazaarPrices.put(bazaarItem.localizedName, (Double) (json3).get("buyPrice"));
            }
            LogUtils.debugLog("Bazaar prices updated");
            cantConnectToApi = false;

        } catch (Exception e) {
            e.printStackTrace();
            LogUtils.debugLog("Failed to update bazaar prices");
            cantConnectToApi = true;
        }
    }

    public static class BazaarItem {
        public String localizedName;
        public String bazaarId;
        public int amountToEnchanted;

        public UIComponent image;
        public int npcPrice;

        public BazaarItem(String localizedName, String bazaarId, int amountToEnchanted, int npcPrice) {
            this.localizedName = localizedName;
            this.bazaarId = bazaarId;
            this.amountToEnchanted = amountToEnchanted;
            this.npcPrice = npcPrice;
        }

        public BazaarItem setImage() {
            this.image = UIImage.ofResource(path + getImageName(localizedName));
            return this;
        }
    }

    public static class GuiItem {
        public int enchantedAmount;
        public UIComponent img;
        private final BasicState<String> enchantedAmountState = new BasicState<>("0");
        public UIComponent stat;

        public GuiItem(int enchantedAmount, UIComponent img) {
            this.enchantedAmount = enchantedAmount;
            this.img = img;
            this.stat = new Stat(img).bind(getEnchantedAmount());
        }

        public BasicState<String> getEnchantedAmount() {
            enchantedAmountState.set(enchantedAmount + "");
            return enchantedAmountState;
        }
    }
}

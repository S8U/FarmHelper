package com.jelly.farmhelper.features;

import com.jelly.farmhelper.FarmHelper;
import com.jelly.farmhelper.config.interfaces.FailsafeConfig;
import com.jelly.farmhelper.config.interfaces.JacobConfig;
import com.jelly.farmhelper.macros.MacroHandler;
import com.jelly.farmhelper.remote.command.commands.ReconnectCommand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Timer;
import java.util.TimerTask;

public class AutoReconnect {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public static double waitTime = 0;

    @SubscribeEvent
    public final void tick(TickEvent.ClientTickEvent event) {
        if (ReconnectCommand.isEnabled) {
            if (ReconnectCommand.reconnectClock.getRemainingTime() > 0) {
                return;
            } else if (!((Failsafe.jacobWait.passed() && JacobConfig.jacobFailsafe) && (BanwaveChecker.banwaveOn && FailsafeConfig.banwaveDisconnect))) {
                ReconnectCommand.isEnabled = false;
                FMLClientHandler.instance().connectToServer(new GuiMultiplayer(new GuiMainMenu()), new ServerData("bozo", FarmHelper.gameState.serverIP, false));
            }
        }

        if (event.phase == TickEvent.Phase.END || !MacroHandler.isMacroing)
            return;
        if(BanwaveChecker.banwaveOn && FailsafeConfig.banwaveDisconnect)
            return;
        if(!Failsafe.jacobWait.passed() && JacobConfig.jacobFailsafe)
            return;
        if ((mc.currentScreen instanceof GuiDisconnected)) {
            if (waitTime >= (FailsafeConfig.reconnectDelay * 20)) {
                waitTime = 0;
                try {
                    FMLClientHandler.instance().connectToServer(new GuiMultiplayer(new GuiMainMenu()), new ServerData("bozo", FarmHelper.gameState.serverIP != null ? FarmHelper.gameState.serverIP : "mc.hypixel.net", false));
                } catch (Exception e) {
                    e.printStackTrace();
                    System.out.println("Failed to reconnect to server!");
                }
            } else {
                waitTime++;
            }
        }
    }

}

package gripe._90.appliede.integration.ae2wtlib;

import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;

import appeng.api.config.Actionable;
import appeng.api.features.GridLinkables;
import appeng.init.client.InitScreens;
import appeng.items.tools.powered.WirelessTerminalItem;

import gripe._90.appliede.integration.Addons;

public class AE2WTIntegration {
    private static Item TERMINAL;

    public static Item getWirelessTerminalItem() {
        if (TERMINAL == null) {
            TERMINAL = new WTTItem();
            GridLinkables.register(TERMINAL, WirelessTerminalItem.LINKABLE_HANDLER);
        }

        return TERMINAL;
    }

    public static MenuType<?> getWirelessTerminalMenu() {
        return WTTMenu.TYPE;
    }

    public static ItemStack getChargedTerminal() {
        if (TERMINAL != null) {
            var stack = TERMINAL.getDefaultInstance();
            var terminal = (WTTItem) TERMINAL;
            terminal.injectAEPower(stack, terminal.getAEMaxPower(stack), Actionable.MODULATE);
            return stack;
        }

        return ItemStack.EMPTY;
    }

    public static void addTerminalToAE2WTLibTab(BuildCreativeModeTabContentsEvent event) {
        if (TERMINAL != null && event.getTabKey().location().getNamespace().equals(Addons.AE2WTLIB.getModId())) {
            event.accept(TERMINAL);
            event.accept(AE2WTIntegration.getChargedTerminal());
        }
    }

    public static class Client {
        public static void initScreen() {
            InitScreens.register(
                    WTTMenu.TYPE, WTTScreen::new, "/screens/appliede/wireless_transmutation_terminal.json");
        }
    }
}

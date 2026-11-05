package dev.modsync.client;

import dev.modsync.ModSync;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = ModSync.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClientEvents {
    private static boolean shown;

    private ClientEvents() {}

    /**
     * Когда игра дошла до главного меню, а синхронизация ещё идёт / требует перезапуска / завершилась ошибкой -
     * показываем экран ModSync вместо меню (один раз; дальше он сам вернёт в меню).
     */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (shown || !(event.getNewScreen() instanceof TitleScreen title)) return;
        switch (ClientSync.state()) {
            case CHECKING, DOWNLOADING, RESTART, FAILED -> {
                shown = true;
                event.setNewScreen(new ClientScreens.Sync(title));
            }
            default -> {
                // IDLE / DISABLED / UP_TO_DATE - ничего не мешаем
            }
        }
    }
}

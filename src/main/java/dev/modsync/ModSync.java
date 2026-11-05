package dev.modsync;

import com.mojang.logging.LogUtils;
import dev.modsync.client.ClientSync;
import dev.modsync.common.ModScanner;
import dev.modsync.server.ServerSync;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

@Mod(ModSync.MODID)
public class ModSync {
    public static final String MODID = "modsync";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ModSync(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, ModSyncConfig.SPEC);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            // заранее считаем хэши локальных модов в фоне
            ModScanner.Local.warmUp();
            // конфиг к этому моменту уже загружен: идём на сервер синхронизации из client.serverAddress
            modBus.addListener((FMLClientSetupEvent e) -> ClientSync.start());
        } else {
            NeoForge.EVENT_BUS.addListener(this::onServerAboutToStart);
            NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        }
    }

    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        if (event.getServer().isDedicatedServer()) {
            ServerSync.start();
        }
    }

    private void onServerStopped(ServerStoppedEvent event) {
        ServerSync.stop();
    }
}

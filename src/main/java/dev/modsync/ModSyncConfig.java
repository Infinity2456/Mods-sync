package dev.modsync;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class ModSyncConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    // ---- сервер ----
    public static final ModConfigSpec.BooleanValue SERVER_ENABLED = B
            .comment("Включить раздачу модов клиентам (только выделенный сервер)")
            .define("server.enabled", true);
    public static final ModConfigSpec.IntValue SYNC_PORT = B
            .comment("TCP-порт синхронизации. Мод сам открывает его при старте сервера.",
                    "Это отдельный порт, не порт Minecraft - его нужно открыть в файрволе / пробросить на роутере / в панели хостинга.")
            .defineInRange("server.syncPort", 25566, 1, 65535);
    public static final ModConfigSpec.ConfigValue<String> BIND_ADDRESS = B
            .comment("IP-адрес, на котором слушать порт синхронизации. Пусто = все интерфейсы.")
            .define("server.bindAddress", "");
    public static final ModConfigSpec.IntValue MAX_CONNECTIONS = B
            .comment("Максимум одновременных соединений синхронизации")
            .defineInRange("server.maxConnections", 8, 1, 256);
    public static final ModConfigSpec.ConfigValue<String> SECRET = B
            .comment("Общий секрет (пароль) синхронизации. Должен совпадать на сервере и клиенте.",
                    "Если задан: порт отдаёт моды только клиентам с этим секретом, а клиент проверяет подпись манифеста.",
                    "Пустой секрет = без защиты: любой, кто видит порт, может скачать моды, а перехватчик трафика - подменить их.")
            .define("server.secret", "");
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXCLUDED = B
            .comment("Моды, которые НЕ отправляются клиентам: modId или имя файла (например \"voicechat\" или \"admintools-1.0.jar\").",
                    "Моды, у которых в neoforge.mods.toml зависимость от neoforge/minecraft помечена side=\"SERVER\", исключаются автоматически.")
            .defineListAllowEmpty("server.excludedMods", List.of(), () -> "", o -> o instanceof String);

    // ---- клиент ----
    public static final ModConfigSpec.ConfigValue<String> SERVER_ADDRESS = B
            .comment("Адрес сервера синхронизации в виде host:port (например \"play.example.com:25566\").",
                    "Порт можно не указывать - тогда используется 25566. Пусто = синхронизация отключена.",
                    "Клиент при каждом запуске игры обращается сюда, сравнивает моды и докачивает недостающие.",
                    "Моды = исполняемый код: указывайте только сервер, которому доверяете.")
            .define("client.serverAddress", "");
    public static final ModConfigSpec.ConfigValue<String> CLIENT_SECRET = B
            .comment("Общий секрет синхронизации (см. server.secret). Должен совпадать с серверным.")
            .define("client.secret", "");
    public static final ModConfigSpec.IntValue CONNECT_TIMEOUT = B
            .comment("Таймаут подключения к серверу синхронизации (секунды)")
            .defineInRange("client.connectTimeoutSeconds", 10, 1, 120);
    public static final ModConfigSpec.BooleanValue AUTO_RESTART = B
            .comment("Пытаться автоматически перезапустить игру после установки модов")
            .define("client.autoRestart", true);

    // ---- общее ----
    public static final ModConfigSpec.IntValue MAX_FILE_MB = B
            .comment("Максимальный размер одного мода (МБ)")
            .defineInRange("limits.maxFileMb", 512, 1, 4096);
    public static final ModConfigSpec.IntValue MAX_TOTAL_MB = B
            .comment("Максимальный суммарный размер загрузки на клиенте (МБ)")
            .defineInRange("limits.maxTotalMb", 2048, 1, 16384);

    public static final ModConfigSpec SPEC = B.build();

    private ModSyncConfig() {}
}

package dev.modsync.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

public final class ClientScreens {
    private ClientScreens() {}

    /** Единый экран ModSync: проверка / загрузка / перезапуск / ошибка. Содержимое зависит от {@link ClientSync#state()}. */
    public static final class Sync extends Screen {
        private final Screen next;
        private ClientSync.State built;
        private int ticks;
        private boolean restarting;

        public Sync(Screen next) {
            super(Component.translatable("modsync.title"));
            this.next = next;
        }

        @Override
        protected void init() {
            built = ClientSync.state();
            int cx = width / 2, y = height / 2 + 40;
            switch (built) {
                case CHECKING, DOWNLOADING -> addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL,
                        b -> ClientSync.cancel()).bounds(cx - 100, y, 200, 20).build());
                case RESTART -> {
                    String key = ClientSync.plan() != null && ClientSync.plan().canRelaunch()
                            ? "modsync.restart.now" : "modsync.restart.close";
                    addRenderableWidget(Button.builder(Component.translatable(key), b -> restart())
                            .bounds(cx - 100, y, 200, 20).build());
                }
                case FAILED -> {
                    addRenderableWidget(Button.builder(Component.translatable("modsync.retry"), b -> {
                        ClientSync.start();
                        rebuildWidgets();
                    }).bounds(cx - 105, y, 100, 20).build());
                    addRenderableWidget(Button.builder(Component.translatable("modsync.continue"), b -> leave())
                            .bounds(cx + 5, y, 100, 20).build());
                }
                default -> {
                }
            }
        }

        private void leave() {
            if (minecraft != null) minecraft.setScreen(next);
        }

        private void restart() {
            if (restarting) return;
            restarting = true;
            Restarter.restart(ClientSync.plan());
        }

        @Override
        public void tick() {
            ClientSync.State s = ClientSync.state();
            if (s != built) {
                rebuildWidgets();
                ticks = 0;
            }
            switch (s) {
                case UP_TO_DATE, DISABLED -> leave();
                case RESTART -> {
                    Restarter.Plan p = ClientSync.plan();
                    if (p != null && p.canRelaunch() && ++ticks >= 100) restart();
                }
                default -> {
                }
            }
        }

        @Override
        public boolean shouldCloseOnEsc() {
            return false;
        }

        private void lines(GuiGraphics g, Component text, int y, int color) {
            for (FormattedCharSequence l : font.split(text, Math.min(width - 40, 360))) {
                g.drawCenteredString(font, l, width / 2, y, color);
                y += 11;
            }
        }

        @Override
        public void render(GuiGraphics gg, int mx, int my, float pt) {
            super.render(gg, mx, my, pt);
            int cx = width / 2, cy = height / 2;
            gg.drawCenteredString(font, title, cx, cy - 60, 0xFFFFFF);
            switch (ClientSync.state()) {
                case CHECKING -> lines(gg, Component.translatable("modsync.checking", ClientSync.address()), cy - 40, 0xAAAAAA);
                case DOWNLOADING -> {
                    gg.drawCenteredString(font, Component.translatable("modsync.download.file", ClientSync.currentName()),
                            cx, cy - 30, 0xAAAAAA);
                    int bw = 200, x0 = cx - bw / 2, y0 = cy - 10;
                    double p = ClientSync.total() == 0 ? 0 : Math.min(1.0, (double) ClientSync.done() / ClientSync.total());
                    gg.fill(x0 - 1, y0 - 1, x0 + bw + 1, y0 + 11, 0xFFFFFFFF);
                    gg.fill(x0, y0, x0 + bw, y0 + 10, 0xFF000000);
                    gg.fill(x0, y0, x0 + (int) (bw * p), y0 + 10, 0xFF55FF55);
                    gg.drawCenteredString(font, Component.literal(ClientSync.Fmt.size(ClientSync.done()) + " / "
                                    + ClientSync.Fmt.size(ClientSync.total()) + "  (" + ClientSync.filesDone() + "/"
                                    + ClientSync.filesTotal() + ")"), cx, y0 + 16, 0xFFFFFF);
                }
                case RESTART -> {
                    Restarter.Plan p = ClientSync.plan();
                    boolean auto = p != null && p.canRelaunch();
                    lines(gg, Component.translatable("modsync.installed"), cy - 40, 0x55FF55);
                    lines(gg, Component.translatable(auto ? "modsync.restart.auto" : "modsync.restart.manual"), cy - 22, 0xDDDDDD);
                }
                case FAILED -> {
                    String e = ClientSync.error();
                    Component msg = "cancelled".equals(e)
                            ? Component.translatable("modsync.cancelled")
                            : Component.translatable("modsync.error", e);
                    lines(gg, msg, cy - 40, 0xFF5555);
                    lines(gg, Component.translatable("modsync.failed.hint", ClientSync.address()), cy - 10, 0xAAAAAA);
                }
                default -> {
                }
            }
        }
    }
}

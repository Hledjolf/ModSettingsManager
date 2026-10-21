package modsettings.adapter;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.HytaleServer;
import java.util.LinkedHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import modsettings.json.Json;
import modsettings.ui.SettingsWindow;
import modsettings.ui.UiOps;

/**
 * The settings window as a raw {@code .ui} page. All state and layout live in {@link SettingsWindow}; this
 * class only builds the page, decodes the client's events and sends the window's updates.
 */
public final class SettingsPage extends InteractiveCustomUIPage<SettingsPage.NoEvent> {
    /** Events are decoded from the raw JSON, so the typed codec is never used. */
    public static final class NoEvent {
        static final BuilderCodec<NoEvent> CODEC = BuilderCodec.builder(NoEvent.class, NoEvent::new).build();
    }

    static final long SEARCH_DELAY_MS = 300;

    private final SettingsWindow window;
    private final HytaleLogger logger;
    private volatile ScheduledFuture<?> pendingSearch;
    private volatile boolean closed;

    public SettingsPage(PlayerRef player, SettingsWindow window, HytaleLogger logger) {
        super(player, CustomPageLifetime.CanDismiss, NoEvent.CODEC);
        this.window = window;
        this.logger = logger;
    }

    /** Opens the page for a player, on the player's world thread. Returns false if the player is not in a world. */
    public static boolean open(PlayerRef player, SettingsWindow window, HytaleLogger logger) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) return false;
        World world = ref.getStore().getExternalData().getWorld();
        Runnable r = () -> {
            Ref<EntityStore> now = player.getReference();
            if (now == null || !now.isValid()) return;
            Store<EntityStore> store = now.getStore();
            Player p = store.getComponent(now, Player.getComponentType());
            if (p != null) p.getPageManager().openCustomPage(now, store, new SettingsPage(player, window, logger));
        };
        if (world.isInThread()) r.run();
        else world.execute(r);
        return true;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        cmd.append(SettingsWindow.DOC);
        UiTranslate.into(window.open(), cmd, events);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String rawData) {
        Map<String, Object> data;
        try {
            data = new LinkedHashMap<>(Json.parseObject(rawData));
        } catch (RuntimeException bad) {
            logger.atWarning().log("[ModSettingsManager] undecodable page event %s", rawData);
            return;
        }
        Object action = data.remove("Action");
        if ("search".equals(action)) {
            // Each keystroke sends an event, and every page update must be acknowledged before the client
            // sends the next one. Redraw the list once typing pauses, so keystrokes are not dropped.
            window.onEvent("search", data);
            ScheduledFuture<?> old = pendingSearch;
            if (old != null) old.cancel(false);
            pendingSearch = HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> send(window.modListOps()), SEARCH_DELAY_MS, TimeUnit.MILLISECONDS);
            return;
        }
        window.onEvent(action == null ? null : action.toString(), data).whenComplete((ops, err) -> {
            if (err != null) {
                logger.atSevere().withCause(err).log("[ModSettingsManager] settings window failed on %s", action);
                return;
            }
            send(ops);
        });
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        closed = true;
        ScheduledFuture<?> f = pendingSearch;
        if (f != null) f.cancel(false);
        super.onDismiss(ref, store);
    }

    private void send(UiOps ops) {
        // A late update (a search redraw, a slow hook) must never land on another page the player opened since.
        if (closed || ops == null || ops.isEmpty()) return;
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder ev = new UIEventBuilder();
        UiTranslate.into(ops, cmd, ev);
        sendUpdate(cmd, ev, false);
    }
}

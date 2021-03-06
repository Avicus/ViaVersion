package us.myles.ViaVersion.bukkit.providers;

import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import us.myles.ViaVersion.api.Via;
import us.myles.ViaVersion.api.data.UserConnection;
import us.myles.ViaVersion.api.protocol.ProtocolRegistry;
import us.myles.ViaVersion.api.protocol.ProtocolVersion;
import us.myles.ViaVersion.bukkit.tasks.protocol1_12to1_11_1.BukkitInventoryUpdateTask;
import us.myles.ViaVersion.bukkit.util.NMSUtil;
import us.myles.ViaVersion.protocols.base.ProtocolInfo;
import us.myles.ViaVersion.protocols.protocol1_12to1_11_1.providers.InventoryQuickMoveProvider;
import us.myles.ViaVersion.protocols.protocol1_12to1_11_1.storage.ItemTransaction;
import us.myles.ViaVersion.util.ReflectionUtil;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BukkitInventoryQuickMoveProvider extends InventoryQuickMoveProvider {

    private static Map<UUID, BukkitInventoryUpdateTask> updateTasks = new ConcurrentHashMap<UUID, BukkitInventoryUpdateTask>();
    private boolean supported;
    // packet class
    private Class<?> windowClickPacketClass;
    private Object clickTypeEnum;
    // Use for nms
    private Method nmsItemMethod;
    private Method craftPlayerHandle;
    private Field connection;
    private Method packetMethod;

    public BukkitInventoryQuickMoveProvider() {
        this.supported = isSupported();
        setupReflection();
    }

    @Override
    public boolean registerQuickMove(short windowId, short slotId, short actionId, UserConnection userConnection) {
        if (!supported) {
            return false;
        }
        if (slotId < 0) { // clicked out of inv slot
            return false;
        }
        ProtocolInfo info = userConnection.get(ProtocolInfo.class);
        UUID uuid = info.getUuid();
        BukkitInventoryUpdateTask updateTask = updateTasks.get(uuid);
        final boolean registered = updateTask != null;
        if (!registered) {
            updateTask = new BukkitInventoryUpdateTask(this, uuid);
            updateTasks.put(uuid, updateTask);
        }
        // http://wiki.vg/index.php?title=Protocol&oldid=13223#Click_Window
        updateTask.addItem(windowId, slotId, actionId);
        if (!registered) {
            Via.getPlatform().runSync(updateTask, 5L);
        }
        return true;
    }

    public Object buildWindowClickPacket(Player p, ItemTransaction storage) {
        if (!supported) {
            return null;
        }
        InventoryView inv = p.getOpenInventory();
        short slotId = storage.getSlotId();
        if (slotId > inv.countSlots()) {
            return null; // wrong container open?
        }
        ItemStack itemstack = inv.getItem(slotId);
        if (itemstack == null) {
            return null;
        }
        Object packet = null;
        try {
            packet = windowClickPacketClass.newInstance();
            Object nmsItem = nmsItemMethod.invoke(null, itemstack);
            ReflectionUtil.set(packet, "a", (int) storage.getWindowId());
            ReflectionUtil.set(packet, "slot", (int) slotId);
            ReflectionUtil.set(packet, "button", 0); // shift + left mouse click
            ReflectionUtil.set(packet, "d", storage.getActionId());
            ReflectionUtil.set(packet, "item", nmsItem);
            int protocolId = ProtocolRegistry.SERVER_PROTOCOL;
            if (protocolId == ProtocolVersion.v1_8.getId()) {
                ReflectionUtil.set(packet, "shift", 1);
            } else if (protocolId >= ProtocolVersion.v1_9.getId()) { // 1.9+
                ReflectionUtil.set(packet, "shift", clickTypeEnum);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return packet;
    }

    public boolean sendPlayer(Player p, Object packet) {
        if (packet == null) {
            return false;
        }
        try {
            Object entityPlayer = craftPlayerHandle.invoke(p);
            Object playerConnection = connection.get(entityPlayer);
            // send
            packetMethod.invoke(playerConnection, packet);
        } catch (IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public void onTaskExecuted(UUID uuid) {
        updateTasks.remove(uuid);
    }

    private void setupReflection() {
        if (!supported) {
            return;
        }
        try {
            this.windowClickPacketClass = NMSUtil.nms("PacketPlayInWindowClick");
            int protocolId = ProtocolRegistry.SERVER_PROTOCOL;
            if (protocolId >= ProtocolVersion.v1_9.getId()) {
                Class<?> eclassz = NMSUtil.nms("InventoryClickType");
                Object[] constants = eclassz.getEnumConstants();
                this.clickTypeEnum = constants[1]; // QUICK_MOVE
            }
            Class<?> craftItemStack = NMSUtil.obc("inventory.CraftItemStack");
            this.nmsItemMethod = craftItemStack.getDeclaredMethod("asNMSCopy", ItemStack.class);
        } catch (Exception e) {
            throw new RuntimeException("Couldn't find required inventory classes", e);
        }
        try {
            this.craftPlayerHandle = NMSUtil.obc("entity.CraftPlayer").getDeclaredMethod("getHandle");
        } catch (NoSuchMethodException | ClassNotFoundException e) {
            throw new RuntimeException("Couldn't find CraftPlayer", e);
        }
        try {
            this.connection = NMSUtil.nms("EntityPlayer").getDeclaredField("playerConnection");
        } catch (NoSuchFieldException | ClassNotFoundException e) {
            throw new RuntimeException("Couldn't find Player Connection", e);
        }
        try {
            this.packetMethod = NMSUtil.nms("PlayerConnection").getDeclaredMethod("a", windowClickPacketClass);
        } catch (NoSuchMethodException | ClassNotFoundException e) {
            throw new RuntimeException("Couldn't find CraftPlayer", e);
        }
    }

    private boolean isSupported() {
        int protocolId = ProtocolRegistry.SERVER_PROTOCOL;
        if (protocolId >= ProtocolVersion.v1_8.getId() && protocolId <= ProtocolVersion.v1_11_1.getId()) {
            return true; // 1.8-1.11.2
        }
        // this is not needed on 1.12+ servers
        return false;
    }
}
package gripe._90.appliede.me.misc;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

import appeng.api.config.Actionable;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.AEKeyFilter;
import appeng.api.storage.MEStorage;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.UpgradeInventories;
import appeng.capabilities.Capabilities;
import appeng.helpers.externalstorage.GenericStackItemStorage;
import appeng.me.storage.DelegatingMEInventory;
import appeng.util.ConfigInventory;
import appeng.util.Platform;

import gripe._90.appliede.AppliedE;
import gripe._90.appliede.me.service.KnowledgeService;

import moze_intel.projecte.api.proxy.IEMCProxy;

public class EMCInterfaceLogic implements IActionHost, IGridTickable, IUpgradeableObject {
    protected final EMCInterfaceLogicHost host;
    protected final IManagedGridNode mainNode;

    private final ConfigInventory config;
    private final ConfigInventory storage;
    private final IUpgradeInventory upgrades;

    private final MEStorage localInvHandler;
    private final GenericStack[] plannedWork;
    private final IActionSource source = IActionSource.ofMachine(this);

    private final LazyOptional<IItemHandler> storageHolder;
    private final LazyOptional<MEStorage> localInvHolder;

    public EMCInterfaceLogic(IManagedGridNode node, EMCInterfaceLogicHost host, Item is) {
        this(node, host, is, 9);
    }

    @SuppressWarnings("UnstableApiUsage")
    public EMCInterfaceLogic(IManagedGridNode node, EMCInterfaceLogicHost host, Item is, int slots) {
        this.host = host;
        mainNode = node.setFlags(GridFlags.REQUIRE_CHANNEL)
                .addService(IGridTickable.class, this)
                .setIdlePowerUsage(10);

        config = ConfigInventory.configStacks(AEItemKey.filter(), slots, this::onConfigRowChanged, false);
        storage = ConfigInventory.storage(new StorageFilter(), slots, this::onStorageChanged);
        upgrades = UpgradeInventories.forMachine(is, 1, this::onStorageChanged);

        localInvHandler = new DelegatingMEInventory(storage);
        plannedWork = new GenericStack[slots];

        config.useRegisteredCapacities();
        storage.useRegisteredCapacities();

        storageHolder = LazyOptional.of(() -> storage).lazyMap(GenericStackItemStorage::new);
        localInvHolder = LazyOptional.of(() -> localInvHandler);
    }

    public ConfigInventory getConfig() {
        return config;
    }

    public ConfigInventory getStorage() {
        return storage;
    }

    @Override
    public IUpgradeInventory getUpgrades() {
        return upgrades;
    }

    public void readFromNBT(CompoundTag tag) {
        config.readFromChildTag(tag, "config");
        storage.readFromChildTag(tag, "storage");
        upgrades.readFromNBT(tag, "upgrades");

        updatePlan();
        notifyNeighbours();
    }

    public void writeToNBT(CompoundTag tag) {
        config.writeToChildTag(tag, "config");
        storage.writeToChildTag(tag, "storage");
        upgrades.writeToNBT(tag, "upgrades");
    }

    @Nullable
    @Override
    public IGridNode getActionableNode() {
        return mainNode.getNode();
    }

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        return new TickingRequest(5, 120, !hasWorkToDo(), true);
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        if (!mainNode.isActive()) {
            return TickRateModulation.SLEEP;
        }

        var couldDoWork = false;

        for (var i = 0; i < plannedWork.length; i++) {
            var work = plannedWork[i];

            if (work != null) {
                couldDoWork = tryUsePlan(i, work.what(), (int) work.amount()) || couldDoWork;

                if (couldDoWork) {
                    updatePlan(i);
                }
            }
        }

        return hasWorkToDo()
                ? couldDoWork ? TickRateModulation.URGENT : TickRateModulation.SLOWER
                : TickRateModulation.SLEEP;
    }

    private boolean hasWorkToDo() {
        for (var requiredWork : plannedWork) {
            if (requiredWork != null) {
                return true;
            }
        }

        return false;
    }

    private void updatePlan() {
        var hadWork = hasWorkToDo();

        for (var i = 0; i < config.size(); i++) {
            updatePlan(i);
        }

        var hasWork = hasWorkToDo();

        if (hadWork != hasWork) {
            mainNode.ifPresent((grid, node) -> {
                if (hasWork) {
                    grid.getTickManager().alertDevice(node);
                } else {
                    grid.getTickManager().sleepDevice(node);
                }
            });
        }
    }

    private void updatePlan(int slot) {
        var req = config.getStack(slot);
        var stored = storage.getStack(slot);

        if (req == null && stored != null) {
            plannedWork[slot] = new GenericStack(stored.what(), -stored.amount());
        } else if (req != null) {
            if (stored == null) {
                plannedWork[slot] = req;
            } else if (req.what().equals(stored.what())) {
                plannedWork[slot] = req.amount() != stored.amount()
                        ? new GenericStack(req.what(), req.amount() - stored.amount())
                        : null;
            } else {
                plannedWork[slot] = new GenericStack(stored.what(), -stored.amount());
            }
        } else {
            plannedWork[slot] = null;
        }
    }

    private boolean tryUsePlan(int slot, AEKey what, int amount) {
        if (!(what instanceof AEItemKey item)) {
            return false;
        }

        var grid = mainNode.getGrid();

        if (grid == null) {
            return false;
        }

        if (amount < 0) {
            amount = -amount;
            var inSlot = storage.getStack(slot);

            if (!what.matches(inSlot) || inSlot.amount() < amount) {
                return true;
            }

            var depositedItems = grid.getService(KnowledgeService.class)
                    .getStorage()
                    .insertItem(
                            item,
                            amount,
                            Actionable.MODULATE,
                            source,
                            upgrades.isInstalled(AppliedE.LEARNING_CARD.get()));

            if (depositedItems > 0) {
                storage.extract(slot, what, depositedItems, Actionable.MODULATE);
                return true;
            }
        }

        if (amount > 0) {
            return storage.insert(slot, what, amount, Actionable.SIMULATE) != amount
                    || acquireFromNetwork(grid, slot, what, amount);
        }

        return false;
    }

    private boolean acquireFromNetwork(IGrid grid, int slot, AEKey what, long amount) {
        if (!(what instanceof AEItemKey item)) {
            return false;
        }

        var acquiredItems = grid.getService(KnowledgeService.class)
                .getStorage()
                .extractItem(item, amount, Actionable.MODULATE, source, true);

        if (acquiredItems > 0) {
            var inserted = storage.insert(slot, what, acquiredItems, Actionable.MODULATE);

            if (inserted < acquiredItems) {
                throw new IllegalStateException("Bad attempt at managing inventory. Voided items: " + inserted);
            }

            return true;
        } else {
            return false;
        }
    }

    private void onConfigRowChanged() {
        host.saveChanges();
        updatePlan();
        notifyNeighbours();
    }

    private void onStorageChanged() {
        host.saveChanges();
        updatePlan();
    }

    public void notifyNeighbours() {
        mainNode.ifPresent((grid, node) -> {
            if (node.isActive()) {
                grid.getTickManager().wakeDevice(node);
            }
        });

        var be = host.getBlockEntity();

        if (be != null && be.getLevel() != null) {
            Platform.notifyBlocksOfNeighbors(be.getLevel(), be.getBlockPos());
        }
    }

    public void addDrops(List<ItemStack> drops) {
        for (var i = 0; i < storage.size(); i++) {
            var stack = storage.getStack(i);

            if (stack != null) {
                stack.what()
                        .addDrops(
                                stack.amount(),
                                drops,
                                host.getBlockEntity().getLevel(),
                                host.getBlockEntity().getBlockPos());
            }
        }

        for (var is : this.upgrades) {
            if (!is.isEmpty()) {
                drops.add(is);
            }
        }
    }

    public <T> LazyOptional<T> getCapability(Capability<T> cap) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return storageHolder.cast();
        } else if (cap == Capabilities.STORAGE) {
            return localInvHolder.cast();
        } else {
            return LazyOptional.empty();
        }
    }

    public void invalidateCaps() {
        storageHolder.invalidate();
        localInvHolder.invalidate();
    }

    private class StorageFilter implements AEKeyFilter {
        @Override
        public boolean matches(AEKey what) {
            if (!(what instanceof AEItemKey item)) {
                return false;
            }

            var grid = mainNode.getGrid();
            return grid == null
                    || grid.getService(KnowledgeService.class).knowsItem(item)
                    || (upgrades.isInstalled(AppliedE.LEARNING_CARD.get())
                            && IEMCProxy.INSTANCE.hasValue(item.toStack()));
        }
    }
}

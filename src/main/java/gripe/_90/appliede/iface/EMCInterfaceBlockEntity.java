package gripe._90.appliede.iface;

import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.blockentity.grid.AENetworkBlockEntity;
import appeng.me.helpers.BlockEntityNodeListener;

import gripe._90.appliede.AppliedE;

public class EMCInterfaceBlockEntity extends AENetworkBlockEntity implements EMCInterfaceLogicHost {
    private static final IGridNodeListener<EMCInterfaceBlockEntity> NODE_LISTENER = new BlockEntityNodeListener<>() {
        @Override
        public void onGridChanged(EMCInterfaceBlockEntity nodeOwner, IGridNode node) {
            nodeOwner.logic.notifyNeighbours();
        }
    };

    private final EMCInterfaceLogic logic = createLogic();

    public EMCInterfaceBlockEntity(BlockPos pos, BlockState blockState) {
        super(AppliedE.EMC_INTERFACE_BE.get(), pos, blockState);
    }

    protected EMCInterfaceLogic createLogic() {
        return new EMCInterfaceLogic(getMainNode(), this);
    }

    @Override
    protected IManagedGridNode createMainNode() {
        return GridHelper.createManagedNode(this, NODE_LISTENER);
    }

    @Override
    public EMCInterfaceLogic getInterfaceLogic() {
        return logic;
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        if (getMainNode().hasGridBooted()) {
            logic.notifyNeighbours();
        }
    }

    @Override
    public void saveAdditional(CompoundTag data) {
        super.saveAdditional(data);
        logic.writeToNBT(data);
    }

    @Override
    public void loadTag(CompoundTag data) {
        super.loadTag(data);
        logic.readFromNBT(data);
    }

    @Override
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        super.addAdditionalDrops(level, pos, drops);
        logic.addDrops(drops);
    }

    @Override
    public void clearContent() {
        super.clearContent();
        getStorage().clear();
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return AppliedE.EMC_INTERFACE.get().asItem().getDefaultInstance();
    }

    @NotNull
    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        var capability = logic.getCapability(cap);
        return capability.isPresent() ? capability : super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        logic.invalidateCaps();
    }
}
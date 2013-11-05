/**
 * Copyright (c) SpaceToad, 2011 http://www.mod-buildcraft.com
 *
 * BuildCraft is distributed under the terms of the Minecraft Mod Public License
 * 1.0, or MMPL. Please check the contents of the license located in
 * http://www.mod-buildcraft.com/MMPL-1.0.txt
 */
package buildcraft.builders;

import buildcraft.BuildCraftCore;
import buildcraft.api.core.IAreaProvider;
import buildcraft.api.core.LaserKind;
import buildcraft.api.filler.FillerManager;
import buildcraft.api.filler.IFillerPattern;
import buildcraft.api.gates.IAction;
import buildcraft.api.gates.IActionReceptor;
import buildcraft.api.power.IPowerReceptor;
import buildcraft.api.power.PowerHandler;
import buildcraft.api.power.PowerHandler.PowerReceiver;
import buildcraft.api.power.PowerHandler.Type;
import buildcraft.core.Box;
import buildcraft.core.IMachine;
import buildcraft.core.TileBuildCraft;
import buildcraft.core.network.PacketUpdate;
import buildcraft.core.network.TileNetworkData;
import buildcraft.core.proxy.CoreProxy;
import buildcraft.core.triggers.ActionMachineControl;
import buildcraft.core.triggers.ActionMachineControl.Mode;
import buildcraft.core.utils.Utils;
import java.io.IOException;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.ForgeDirection;

public class TileFiller extends TileBuildCraft implements ISidedInventory, IPowerReceptor, IMachine, IActionReceptor {

	private static int[] SLOTS_GRID = Utils.createSlotArray(0, 9);
	private static int[] SLOTS_INPUT = Utils.createSlotArray(9, 27);
	public @TileNetworkData
	Box box = new Box();
	public @TileNetworkData
	int currentPatternId = 0;
	public @TileNetworkData
	boolean done = true;
	public IFillerPattern currentPattern;
	boolean forceDone = false;
	private ItemStack contents[];
	private PowerHandler powerHandler;
	private ActionMachineControl.Mode lastMode = ActionMachineControl.Mode.Unknown;

	public TileFiller() {
		contents = new ItemStack[getSizeInventory()];
		powerHandler = new PowerHandler(this, Type.MACHINE);
		initPowerProvider();
	}

	private void initPowerProvider() {
		powerHandler.configure(30, 50, 25, 100);
		powerHandler.configurePowerPerdition(1, 1);
	}

	@Override
	public void initialize() {
		super.initialize();

		if (!CoreProxy.proxy.isRenderWorld(worldObj)) {
			IAreaProvider a = Utils.getNearbyAreaProvider(worldObj, xCoord, yCoord, zCoord);

			if (a != null) {
				box.initialize(a);

				if (a instanceof TileMarker) {
					((TileMarker) a).removeFromWorld();
				}

				if (!CoreProxy.proxy.isRenderWorld(worldObj) && box.isInitialized()) {
					box.createLasers(worldObj, LaserKind.Stripes);
				}
				sendNetworkUpdate();
			}
		}

		computeRecipe();
	}

	@Override
	public void updateEntity() {
		super.updateEntity();

		if (done) {
			if (lastMode == Mode.Loop) {
				done = false;
			} else
				return;
		}

		if (powerHandler.getEnergyStored() >= 25) {
			doWork(powerHandler);
		}
	}

	@Override
	public void doWork(PowerHandler workProvider) {
		if (CoreProxy.proxy.isRenderWorld(worldObj))
			return;

		if (lastMode == Mode.Off)
			return;

		if (powerHandler.useEnergy(25, 25, true) < 25)
			return;

		if (box.isInitialized() && currentPattern != null && !done) {
			ItemStack stack = null;
			int stackId = 0;

			for (int s = 9; s < getSizeInventory(); ++s) {
				if (getStackInSlot(s) != null && getStackInSlot(s).stackSize > 0) {

					stack = contents[s];
					stackId = s;

					break;
				}
			}

			done = currentPattern.iteratePattern(this, box, stack);

			if (stack != null && stack.stackSize == 0) {
				contents[stackId] = null;
			}

			if (done) {
				worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
				sendNetworkUpdate();
			}
		}

		if (powerHandler.getEnergyStored() >= 25) {
			doWork(workProvider);
		}
	}

	@Override
	public final int getSizeInventory() {
		return 36;
	}

	@Override
	public ItemStack getStackInSlot(int i) {
		return contents[i];
	}

	public void computeRecipe() {
		if (CoreProxy.proxy.isRenderWorld(worldObj))
			return;

		IFillerPattern newPattern = FillerManager.registry.findMatchingRecipe(this);

		if (newPattern == currentPattern)
			return;

		currentPattern = newPattern;

		if (currentPattern == null || forceDone) {
			done = lastMode != Mode.Loop;
			forceDone = false;
		} else {
			done = false;
		}

		if (worldObj != null) {
			worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
		}

		if (currentPattern == null) {
			currentPatternId = 0;
		} else {
			currentPatternId = currentPattern.getId();
		}

		if (CoreProxy.proxy.isSimulating(worldObj)) {
			sendNetworkUpdate();
		}
	}

	@Override
	public ItemStack decrStackSize(int i, int j) {
		if (contents[i] != null) {
			if (contents[i].stackSize <= j) {
				ItemStack itemstack = contents[i];
				contents[i] = null;
				// onInventoryChanged();

				computeRecipe();

				return itemstack;
			}

			ItemStack itemstack1 = contents[i].splitStack(j);

			if (contents[i].stackSize == 0) {
				contents[i] = null;
			}
			// onInventoryChanged();

			computeRecipe();

			return itemstack1;
		} else
			return null;
	}

	@Override
	public void setInventorySlotContents(int i, ItemStack itemstack) {
		contents[i] = itemstack;
		if (itemstack != null && itemstack.stackSize > getInventoryStackLimit()) {
			itemstack.stackSize = getInventoryStackLimit();
		}

		computeRecipe();
		// onInventoryChanged();
	}

	@Override
	public ItemStack getStackInSlotOnClosing(int slot) {
		if (contents[slot] == null)
			return null;
		ItemStack toReturn = contents[slot];
		contents[slot] = null;
		return toReturn;
	}

	@Override
	public String getInvName() {
		return "Filler";
	}

	@Override
	public void readFromNBT(NBTTagCompound nbttagcompound) {
		super.readFromNBT(nbttagcompound);

		Utils.readStacksFromNBT(nbttagcompound, "Items", contents);

		if (nbttagcompound.hasKey("box")) {
			box.initialize(nbttagcompound.getCompoundTag("box"));
		}

		done = nbttagcompound.getBoolean("done");
		lastMode = Mode.values()[nbttagcompound.getByte("lastMode")];

		forceDone = done;
	}

	@Override
	public void writeToNBT(NBTTagCompound nbttagcompound) {
		super.writeToNBT(nbttagcompound);

		Utils.writeStacksToNBT(nbttagcompound, "Items", contents);

		if (box != null) {
			NBTTagCompound boxStore = new NBTTagCompound();
			box.writeToNBT(boxStore);
			nbttagcompound.setTag("box", boxStore);
		}

		nbttagcompound.setBoolean("done", done);
		nbttagcompound.setByte("lastMode", (byte) lastMode.ordinal());
	}

	@Override
	public int getInventoryStackLimit() {
		return 64;
	}

	@Override
	public boolean isUseableByPlayer(EntityPlayer entityplayer) {
		if (worldObj.getBlockTileEntity(xCoord, yCoord, zCoord) != this)
			return false;
		return entityplayer.getDistanceSq(xCoord + 0.5D, yCoord + 0.5D, zCoord + 0.5D) <= 64D;
	}

	@Override
	public void invalidate() {
		super.invalidate();
		destroy();
	}

	@Override
	public void destroy() {
		if (box != null) {
			box.deleteLasers();
		}
	}

	@Override
	public void handleDescriptionPacket(PacketUpdate packet) throws IOException {
		boolean initialized = box.isInitialized();

		super.handleDescriptionPacket(packet);

		currentPattern = FillerManager.registry.getPattern(currentPatternId);
		worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);

		if (!initialized && box.isInitialized()) {
			box.createLasers(worldObj, LaserKind.Stripes);
		}
	}

	@Override
	public void handleUpdatePacket(PacketUpdate packet) throws IOException {
		boolean initialized = box.isInitialized();

		super.handleUpdatePacket(packet);

		currentPattern = FillerManager.registry.getPattern(currentPatternId);
		worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);

		if (!initialized && box.isInitialized()) {
			box.createLasers(worldObj, LaserKind.Stripes);
		}
	}

	@Override
	public PowerReceiver getPowerReceiver(ForgeDirection side) {
		return powerHandler.getPowerReceiver();
	}

	@Override
	public boolean isActive() {
		return !done && lastMode != Mode.Off;
	}

	@Override
	public boolean manageFluids() {
		return false;
	}

	@Override
	public boolean manageSolids() {
		return true;
	}

	@Override
	public void openChest() {
	}

	@Override
	public void closeChest() {
	}

	@Override
	public void actionActivated(IAction action) {
		if (action == BuildCraftCore.actionOn) {
			lastMode = ActionMachineControl.Mode.On;
		} else if (action == BuildCraftCore.actionOff) {
			lastMode = ActionMachineControl.Mode.Off;
		} else if (action == BuildCraftCore.actionLoop) {
			lastMode = ActionMachineControl.Mode.Loop;
		}
	}

	@Override
	public boolean allowAction(IAction action) {
		return true;
	}

	@Override
	public boolean isItemValidForSlot(int slot, ItemStack stack) {
		if (slot < 9) {
			if (getStackInSlot(slot) != null)
				return false;
			return stack.itemID == Block.brick.blockID || stack.itemID == Block.glass.blockID;
		}
		return true;
	}

	@Override
	public int[] getAccessibleSlotsFromSide(int side) {
		if (ForgeDirection.UP.ordinal() == side) {
			return SLOTS_GRID;
		}
		return SLOTS_INPUT;
	}

	@Override
	public boolean canInsertItem(int slot, ItemStack stack, int side) {
		return isItemValidForSlot(slot, stack);
	}

	@Override
	public boolean canExtractItem(int slot, ItemStack stack, int side) {
		return true;
	}
}
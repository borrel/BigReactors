package erogenousbeef.bigreactors.common.multiblock.helpers;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;

public abstract class FluidHelper {

	private FluidStack[] fluids;
	private int capacity;
	
	private int ticksSinceLastUpdate;
	private static final int minimumTicksBetweenUpdates = 60;
	private static final int minimumDevianceForUpdate = 50; // at least 50mB difference before we send a fueling update to the client

	int[] fluidLevelAtLastUpdate;

	private static final int FORCE_UPDATE = -1000;
	private int numberOfFluids;
	
	private boolean separateChambers;
	
	/**
	 * @param separate True if capacity is applied to each fluid separately, false if they should be treated like a single tank with multiple fluids inside.
	 */
	public FluidHelper(boolean separate) {
		numberOfFluids = getNumberOfFluidTanks();
		
		fluids = new FluidStack[numberOfFluids];
		fluidLevelAtLastUpdate = new int[numberOfFluids];

		for(int i = 0; i < numberOfFluids; i++) {
			fluids[i] = null;
			fluidLevelAtLastUpdate[i] = FORCE_UPDATE;
		}
		
		capacity = 0;
		separateChambers = separate;
	}

	public abstract int getNumberOfFluidTanks();
	protected abstract String[] getNBTTankNames();
	
	public boolean shouldSendFuelingUpdate() {
		ticksSinceLastUpdate++;
		if(minimumTicksBetweenUpdates < ticksSinceLastUpdate) {
			int dev = 0;
			boolean shouldUpdate = false;
			for(int i = 0; i < numberOfFluids && !shouldUpdate; i++) {
				
				if(fluids[i] == null && fluidLevelAtLastUpdate[i] > 0) {
					shouldUpdate = true;
				}
				else if(fluids[i] != null) {
					if(fluidLevelAtLastUpdate[i] == FORCE_UPDATE) {
						shouldUpdate = true;
					}
					else {
						dev += Math.abs(fluids[i].amount - fluidLevelAtLastUpdate[i]);
					}
				}
				// else, both levels are zero, no-op
				
				if(dev >= minimumDevianceForUpdate) {
					shouldUpdate = true;
				}
			}
			
			if(shouldUpdate) {
				resetLastSeenFluidLevels();
			}
			
			ticksSinceLastUpdate = 0;
			return shouldUpdate;
		}
		
		return false;
	}
	
	public int getCapacity() { return capacity; }
	
	public void setCapacity(int newCapacity) {
		int oldCapacity = capacity;
		capacity = newCapacity;

		if(oldCapacity > capacity) {
			clampContentsToCapacity();
		}
	}
	
	protected void merge(FluidHelper other) {
		capacity += other.capacity;
		for(int i = 0; i < fluids.length; i++) {
			if(other.fluids[i] != null ){
				if(fluids[i] == null) {
					fluids[i] = other.fluids[i];
				}
				else {
					if(fluids[i].isFluidEqual(other.fluids[i])) {
						// If fluids match, absorb the other stack
						fluids[i].amount += other.fluids[i].amount;
					}
					else if(fluids[i].amount < other.fluids[i].amount) {
						// If fluids do not match, take the bigger stack
						fluids[i] = other.fluids[i];
					}
				}
			}
		}
	}
	
	/**
	 * @return Total amount of stuff contained, across all fluid tanks
	 */
	public int getTotalAmount() {
		int amt = 0;
		for(int i = 0; i < fluids.length; i++) {
			amt += getFluidAmount(i);
		}
		return amt;
	}
	
	protected NBTTagCompound writeToNBT(NBTTagCompound destination) {
		String[] tankNames = getNBTTankNames();
		
		if(tankNames.length != fluids.length) { throw new IllegalArgumentException("getNBTTankNames must return the same number of strings as there are fluid stacks"); }

		FluidStack stack;
		for(int i = 0; i < tankNames.length; i++) {
			stack = fluids[i];
			if(stack != null) {
				destination.setCompoundTag(tankNames[i], stack.writeToNBT(new NBTTagCompound()));
			}
		}
		
		return destination;
	}
	
	protected void readFromNBT(NBTTagCompound data) {
		String[] tankNames = getNBTTankNames();
		
		if(tankNames.length != fluids.length) { throw new IllegalArgumentException("getNBTTankNames must return the same number of strings as there are fluid stacks"); }

		for(int i = 0; i < tankNames.length; i++) {
			if(data.hasKey(tankNames[i])) {
				fluids[i] = FluidStack.loadFluidStackFromNBT(data.getCompoundTag(tankNames[i]));
				fluidLevelAtLastUpdate[i] = fluids[i].amount;
			}
		}
	}
	
	////// FLUID HELPERS //////
	protected void setFluid(int idx, FluidStack newFluid) {
		fluids[idx] = newFluid;
	}
	
	protected int getFluidAmount(int idx) {
		if(fluids[idx] == null) { return 0; }
		else { return fluids[idx].amount; }
		
	}
	
	protected Fluid getFluidType(int idx) {
		if(fluids[idx] == null) { return null; }
		else { return fluids[idx].getFluid(); }
	}
	
	protected abstract boolean isFluidValidForStack(int stackIdx, Fluid fluid);
	
	protected boolean canAddToStack(int idx, Fluid incoming) {
		if(idx < 0 || idx >= fluids.length || incoming == null) { return false; }
		else if(fluids[idx] == null) { return isFluidValidForStack(idx, incoming); }
		return fluids[idx].getFluid().getID() == incoming.getID();
	}
	
	protected boolean canAddToStack(int idx, FluidStack incoming) {
		if(idx < 0 || idx >= fluids.length || incoming == null) { return false; }
		else if(fluids[idx] == null) {return isFluidValidForStack(idx, incoming.getFluid()); }
		return fluids[idx].isFluidEqual(incoming);
	}
	
	protected int addFluidToStack(int idx, int fluidAmount) {
		if(fluids[idx].getFluid() == null) {
			throw new IllegalArgumentException("Cannot add fluid with only an integer when tank is empty!");
		}
		
		int amtToAdd = Math.min(fluidAmount, getCapacity() - getRemainingSpaceForFluid(idx));
		
		fluids[idx].amount += amtToAdd;
		return amtToAdd;
	}
	
	/**
	 * Drain some fluid from a given stack
	 * @param idx Index of the stack (FUEL or WASTE)
	 * @param amount Nominal amount to drain
	 * @return Amount actually drained
	 */
	protected int drainFluidFromStack(int idx, Fluid fluid, int amount) {
		if(fluids[idx] == null) { return 0; }
		
		if(fluids[idx].getFluid().getID() != fluid.getID()) { return 0; }

		return drainFluidFromStack(idx, amount);
	}
	
	/**
	 * Drain fluid from a given stack, without caring what type it is.
	 * @param idx Index of the stack
	 * @param amount Amount to drain
	 * @return
	 */
	protected int drainFluidFromStack(int idx, int amount) {
		if(fluids[idx] == null) { return 0; }

		if(fluids[idx].amount <= amount) {
			amount = fluids[idx].amount;
			fluids[idx] = null;
		}
		else {
			fluids[idx].amount -= amount;
		}
		return amount;
	}
	
	protected void clampContentsToCapacity() {
		if(separateChambers) {
			// Clamp each tank to capacity
			for(int i = 0; i < fluids.length; i++) {
				if(fluids[i] != null) {
					fluids[i].amount = Math.min(getCapacity(), fluids[i].amount);
				}
			}
		}
		else {
			if(getTotalAmount() > capacity) {
				int diff = getTotalAmount() - capacity;
				
				// Reduce stuff in the tanks. Start with waste, to be nice to players.
				for(int i = fluids.length - 1; i >= 0 && diff > 0; i--) {
					if(fluids[i] != null) {
						if(diff > fluids[i].amount) {
							diff -= fluids[i].amount;
							fluids[i] = null;
						}
						else {
							fluids[i].amount -= diff;
							diff = 0;
						}
					}
				}
			}
			// Else: nothing to do, no need to clamp
		}
	}
	
	protected void resetLastSeenFluidLevels() {
		for(int i = 0; i < numberOfFluids; i++) {
			if(fluids[i] == null) {
				fluidLevelAtLastUpdate[i] = 0;
			}
			else {
				fluidLevelAtLastUpdate[i] = fluids[i].amount;
			}
		}
	}
	
	protected int getRemainingSpaceForFluid(int idx) {
		int containedFluidAmt;
		if(separateChambers) {
			containedFluidAmt = getFluidAmount(idx);
		}
		else {
			containedFluidAmt = getTotalAmount();
		}

		return getCapacity() - containedFluidAmt;
	}
	
	// IFluidHandler analogue
	public int fill(int idx, FluidStack incoming, boolean doFill) {
		if(incoming == null || idx < 0 || idx >= fluids.length) { return 0; }
		
		if(!canAddToStack(idx, incoming)) { return 0; }
		
		int amtToAdd = Math.min(incoming.amount, getCapacity() - getRemainingSpaceForFluid(idx));

		if(amtToAdd <= 0) { 
			return 0;
		}

		if(!doFill) { return amtToAdd; }

		if(fluids[idx] == null) {
			fluids[idx] = incoming.copy();
			fluids[idx].amount = amtToAdd;
		}
		else {
			fluids[idx].amount += amtToAdd;
		}

		return amtToAdd;
	}
	
	public FluidStack drain(int idx, FluidStack resource,
			boolean doDrain) {
		if(resource == null || resource.amount <= 0 || idx < 0 || idx >= fluids.length) { return null; }
		
		Fluid existingFluid = getFluidType(idx);
		if(existingFluid == null || existingFluid.getID() != resource.getFluid().getID()) { return null; }
		
		FluidStack drained = resource.copy();
		if(!doDrain) {
			drained.amount = Math.max(resource.amount, getFluidAmount(idx));
		}
		else {
			drained.amount = drainFluidFromStack(idx, resource.amount);
		}

		return drained;
	}

	public FluidStack drain(int idx, int maxDrain, boolean doDrain) {
		if(maxDrain <= 0 || idx < 0 || idx >= fluids.length) { return null; }
		
		if(getFluidType(idx) == null) { return null; }
		
		FluidStack drained = new FluidStack(getFluidType(idx), 0);

		if(!doDrain) {
			drained.amount = Math.max(getFluidAmount(idx), maxDrain);
		}
		else {
			drained.amount = drainFluidFromStack(idx, maxDrain);
		}

		return drained;
	}
	
	public boolean canFill(int idx, Fluid fluid) {
		return canAddToStack(idx, fluid);
	}
	
	public boolean canDrain(int idx, Fluid fluid) {
		if(fluid == null || idx < 0 || idx >= fluids.length) { return false; }

		if(fluids[idx] == null) { return false; }
		
		return fluids[idx].getFluid().getID() == fluid.getID();
	}
	
	private static FluidTankInfo[] emptyTankArray = new FluidTankInfo[0];
	
	public FluidTankInfo[] getTankInfo(int idx) {
		if(idx < 0 || idx >= fluids.length) { return emptyTankArray; }
		
		FluidTankInfo[] info = new FluidTankInfo[1];
		info[0] = new FluidTankInfo(fluids[idx] == null ? null : fluids[idx].copy(), getCapacity());
		return info;
	}
}

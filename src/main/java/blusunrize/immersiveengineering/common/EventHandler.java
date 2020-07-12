/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import blusunrize.immersiveengineering.api.CapabilitySkyhookData.SimpleSkyhookProvider;
import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.api.shader.CapabilityShader;
import blusunrize.immersiveengineering.api.shader.CapabilityShader.ShaderWrapper_Direct;
import blusunrize.immersiveengineering.api.shader.IShaderItem;
import blusunrize.immersiveengineering.api.tool.IDrillHead;
import blusunrize.immersiveengineering.api.wires.GlobalWireNetwork;
import blusunrize.immersiveengineering.api.wires.NetHandlerCapability;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IEntityProof;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.ISpawnInterdiction;
import blusunrize.immersiveengineering.common.blocks.IEBlocks.MetalDevices;
import blusunrize.immersiveengineering.common.blocks.IEMultiblockBlock;
import blusunrize.immersiveengineering.common.blocks.generic.MultiblockPartTileEntity;
import blusunrize.immersiveengineering.common.blocks.metal.CrusherTileEntity;
import blusunrize.immersiveengineering.common.blocks.metal.RazorWireTileEntity;
import blusunrize.immersiveengineering.common.items.DrillItem;
import blusunrize.immersiveengineering.common.items.IEItems.Misc;
import blusunrize.immersiveengineering.common.items.IEItems.Tools;
import blusunrize.immersiveengineering.common.items.IEShieldItem;
import blusunrize.immersiveengineering.common.network.MessageMinecartShaderSync;
import blusunrize.immersiveengineering.common.util.*;
import blusunrize.immersiveengineering.common.util.IEDamageSources.ElectricDamageSource;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityClassification;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.item.minecart.AbstractMinecartEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Rarity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.Hand;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.World;
import net.minecraftforge.event.AnvilUpdateEvent;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TickEvent.WorldTickEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.event.entity.living.LivingEvent.LivingJumpEvent;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteractSpecific;
import net.minecraftforge.event.furnace.FurnaceFuelBurnTimeEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fml.network.PacketDistributor;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;

public class EventHandler
{
	//TODO move to a capability
	public static final Map<RegistryKey<World>, Set<ISpawnInterdiction>> interdictionTiles = new HashMap<>();
	public static HashSet<IEExplosion> currentExplosions = new HashSet<IEExplosion>();
	public static final Queue<Pair<RegistryKey<World>, BlockPos>> requestedBlockUpdates = new LinkedList<>();
	public static final Set<TileEntity> REMOVE_FROM_TICKING = new HashSet<>();

	@SubscribeEvent
	public void onLoad(WorldEvent.Load event)
	{
		ImmersiveEngineering.proxy.onWorldLoad();
	}

	@SubscribeEvent
	public void onCapabilitiesAttachEntity(AttachCapabilitiesEvent<Entity> event)
	{
		if(event.getObject() instanceof AbstractMinecartEntity)
			event.addCapability(new ResourceLocation("immersiveengineering:shader"),
					new ShaderWrapper_Direct(new ResourceLocation(ImmersiveEngineering.MODID, "minecart")));
		if(event.getObject() instanceof PlayerEntity)
			event.addCapability(new ResourceLocation(ImmersiveEngineering.MODID, "skyhook_data"),
					new SimpleSkyhookProvider());
	}

	@SubscribeEvent
	public void onCapabilitiesAttachWorld(AttachCapabilitiesEvent<World> event)
	{
		event.addCapability(new ResourceLocation(ImmersiveEngineering.MODID, "wire_network"),
				new NetHandlerCapability.Provider(event.getObject()));
	}

	@SubscribeEvent
	public void onMinecartInteraction(EntityInteractSpecific event)
	{
		PlayerEntity player = event.getPlayer();
		ItemStack stack = event.getItemStack();
		if(!(event.getTarget() instanceof AbstractMinecartEntity))
			return;
		AbstractMinecartEntity cart = (AbstractMinecartEntity)event.getTarget();
		if(!stack.isEmpty()&&stack.getItem() instanceof IShaderItem)
		{
			cart.getCapability(CapabilityShader.SHADER_CAPABILITY).ifPresent(wrapper ->
			{
				wrapper.setShaderItem(Utils.copyStackWithAmount(stack, 1));
				if(!player.world.isRemote)
					ImmersiveEngineering.packetHandler.send(PacketDistributor.PLAYER.with(() -> (ServerPlayerEntity)player),
							new MessageMinecartShaderSync(cart, wrapper));
			});
			event.setCanceled(true);
			event.setCancellationResult(ActionResultType.SUCCESS);
		}
	}

	/*TODO re-add when the event exists again!
	@SubscribeEvent
	public void onMinecartUpdate(MinecartUpdateEvent event)
	{
		if(event.getMinecart().ticksExisted%3==0 && event.getMinecart().hasCapability(CapabilityShader.SHADER_CAPABILITY, null))
		{
			ShaderWrapper wrapper = event.getMinecart().getCapability(CapabilityShader.SHADER_CAPABILITY, null);
			if(wrapper!=null)
			{
				Vec3d prevPosVec = new Vec3d(event.getMinecart().prevPosX, event.getMinecart().prevPosY, event.getMinecart().prevPosZ);
				Vec3d movVec = prevPosVec.subtract(event.getMinecart().posX, event.getMinecart().posY, event.getMinecart().posZ);
				if(movVec.lengthSquared() > 0.0001)
				{
					movVec = movVec.normalize();
					Triple<ItemStack, ShaderRegistryEntry, ShaderCase> shader = ShaderRegistry.getStoredShaderAndCase(wrapper);
					if(shader!=null)
						shader.getMiddle().getEffectFunction().execute(event.getMinecart().world, shader.getLeft(), null, shader.getRight().getShaderType(), prevPosVec.add(0, .25, 0).add(movVec), movVec.scale(1.5f), .25f);
				}
			}
		}
	}*/


	public static List<ResourceLocation> lootInjections = Arrays.asList(
			new ResourceLocation(ImmersiveEngineering.MODID, "chests/stronghold_library"),
			new ResourceLocation(ImmersiveEngineering.MODID, "chests/village_blacksmith")
	);

	@SubscribeEvent
	public void onEntityJoiningWorld(EntityJoinWorldEvent event)
	{
		if(event.getEntity().world.isRemote&&event.getEntity() instanceof AbstractMinecartEntity&&
				event.getEntity().getCapability(CapabilityShader.SHADER_CAPABILITY).isPresent())
			ImmersiveEngineering.packetHandler.sendToServer(new MessageMinecartShaderSync(event.getEntity(), null));
	}

	@SubscribeEvent
	public void onWorldTick(WorldTickEvent event)
	{
		if(event.phase==TickEvent.Phase.START&&!event.world.isRemote)
		{
			GlobalWireNetwork.getNetwork(event.world).tick();

			if(!REMOVE_FROM_TICKING.isEmpty())
			{
				event.world.tickableTileEntities.removeAll(REMOVE_FROM_TICKING);
				REMOVE_FROM_TICKING.removeIf((te) -> te.getWorld()==event.world);
			}
		}
		if(event.phase==TickEvent.Phase.START)
		{
			if(!currentExplosions.isEmpty())
			{
				Iterator<IEExplosion> itExplosion = currentExplosions.iterator();
				while(itExplosion.hasNext())
				{
					IEExplosion ex = itExplosion.next();
					ex.doExplosionTick();
					if(ex.isExplosionFinished)
						itExplosion.remove();
				}
			}
			Iterator<Pair<RegistryKey<World>, BlockPos>> it = requestedBlockUpdates.iterator();
			while(it.hasNext())
			{
				Pair<RegistryKey<World>, BlockPos> curr = it.next();
				if(curr.getLeft().equals(event.world.func_234923_W_()))
				{
					BlockState state = event.world.getBlockState(curr.getRight());
					event.world.notifyBlockUpdate(curr.getRight(), state, state, 3);
					it.remove();
				}
			}
		}
	}

	public static HashMap<UUID, CrusherTileEntity> crusherMap = new HashMap<>();
	public static HashSet<Class<? extends MobEntity>> listOfBoringBosses = new HashSet<>();

	static
	{
		listOfBoringBosses.add(WitherEntity.class);
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onLivingDropsLowest(LivingDropsEvent event)
	{
		if(!event.isCanceled()&&Lib.DMG_Crusher.equals(event.getSource().getDamageType()))
		{
			CrusherTileEntity crusher = crusherMap.get(event.getEntityLiving().getUniqueID());
			if(crusher!=null)
			{
				for(ItemEntity item : event.getDrops())
					if(item!=null&&!item.getItem().isEmpty())
						crusher.doProcessOutput(item.getItem());
				crusherMap.remove(event.getEntityLiving().getUniqueID());
				event.setCanceled(true);
			}
		}
	}

	@SubscribeEvent
	public void onLivingDrops(LivingDropsEvent event)
	{
		if(!event.isCanceled()&&!event.getEntityLiving().isNonBoss())
		{
			Rarity r = Rarity.EPIC;
			for(Class<? extends MobEntity> boring : listOfBoringBosses)
				if(boring.isAssignableFrom(event.getEntityLiving().getClass()))
					return;
			ItemStack bag = new ItemStack(Misc.shaderBag.get(r));
			event.getDrops().add(new ItemEntity(event.getEntityLiving().world, event.getEntityLiving().getPosX(), event.getEntityLiving().getPosY(), event.getEntityLiving().getPosZ(), bag));
		}
	}

	@SubscribeEvent
	public void onLivingAttacked(LivingAttackEvent event)
	{
		if(event.getEntityLiving() instanceof PlayerEntity)
		{
			PlayerEntity player = (PlayerEntity)event.getEntityLiving();
			ItemStack activeStack = player.getActiveItemStack();
			if(!activeStack.isEmpty()&&activeStack.getItem() instanceof IEShieldItem&&event.getAmount() >= 3&&Utils.canBlockDamageSource(player, event.getSource()))
			{
				float amount = event.getAmount();
				((IEShieldItem)activeStack.getItem()).hitShield(activeStack, player, event.getSource(), amount, event);
			}
		}
	}


	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onLivingHurt(LivingHurtEvent event)
	{
		if(event.getSource().isFireDamage()&&event.getEntityLiving().getActivePotionEffect(IEPotions.flammable)!=null)
		{
			int amp = event.getEntityLiving().getActivePotionEffect(IEPotions.flammable).getAmplifier();
			float mod = 1.5f+((amp*amp)*.5f);
			event.setAmount(event.getAmount()*mod);
		}
		if(("flux".equals(event.getSource().getDamageType())||IEDamageSources.razorShock.equals(event.getSource())||
				event.getSource() instanceof ElectricDamageSource)&&event.getEntityLiving().getActivePotionEffect(IEPotions.conductive)!=null)
		{
			int amp = event.getEntityLiving().getActivePotionEffect(IEPotions.conductive).getAmplifier();
			float mod = 1.5f+((amp*amp)*.5f);
			event.setAmount(event.getAmount()*mod);
		}
		if(!event.isCanceled()&&!event.getEntityLiving().isNonBoss()&&event.getAmount() >= event.getEntityLiving().getHealth()&&event.getSource().getTrueSource() instanceof PlayerEntity&&((PlayerEntity)event.getSource().getTrueSource()).getHeldItem(Hand.MAIN_HAND).getItem() instanceof DrillItem)
			Utils.unlockIEAdvancement((PlayerEntity)event.getSource().getTrueSource(), "main/secret_drillbreak");
	}

	@SubscribeEvent
	public void onLivingJump(LivingJumpEvent event)
	{
		Vector3d motion = event.getEntity().getMotion();
		if(event.getEntityLiving().getActivePotionEffect(IEPotions.sticky)!=null)
			motion = motion.subtract(0, (event.getEntityLiving().getActivePotionEffect(IEPotions.sticky).getAmplifier()+1)*0.3F, 0);
		else if(event.getEntityLiving().getActivePotionEffect(IEPotions.concreteFeet)!=null)
			motion = Vector3d.ZERO;
		event.getEntity().setMotion(motion);
	}

	@SubscribeEvent
	public void onLivingUpdate(LivingUpdateEvent event)
	{
		if(event.getEntityLiving() instanceof PlayerEntity&&!event.getEntityLiving().getItemStackFromSlot(EquipmentSlotType.CHEST).isEmpty()&&ItemNBTHelper.hasKey(event.getEntityLiving().getItemStackFromSlot(EquipmentSlotType.CHEST), Lib.NBT_Powerpack))
		{
			ItemStack powerpack = ItemNBTHelper.getItemStack(event.getEntityLiving().getItemStackFromSlot(EquipmentSlotType.CHEST), Lib.NBT_Powerpack);
			if(!powerpack.isEmpty())
				powerpack.getItem().onArmorTick(powerpack, event.getEntityLiving().getEntityWorld(), (PlayerEntity)event.getEntityLiving());
		}
	}

	@SubscribeEvent
	public void onEnderTeleport(EnderTeleportEvent event)
	{
		if(event.getEntityLiving().getType().getClassification()==EntityClassification.MONSTER)
		{
			synchronized(interdictionTiles)
			{
				Set<ISpawnInterdiction> dimSet = interdictionTiles.get(event.getEntity().world.func_234923_W_());
				if(dimSet!=null)
				{
					Iterator<ISpawnInterdiction> it = dimSet.iterator();
					while(it.hasNext())
					{
						ISpawnInterdiction interdictor = it.next();
						if(interdictor instanceof TileEntity)
						{
							if(((TileEntity)interdictor).isRemoved()||((TileEntity)interdictor).getWorld()==null)
								it.remove();
							else if(
									Vector3d.func_237491_b_(((TileEntity)interdictor).getPos()).squareDistanceTo(event.getEntity().getPositionVec())
											<= interdictor.getInterdictionRangeSquared()
							)
								event.setCanceled(true);
						}
						else if(interdictor instanceof Entity)
						{
							if(!((Entity)interdictor).isAlive()||((Entity)interdictor).world==null)
								it.remove();
							else if(((Entity)interdictor).getDistanceSq(event.getEntity()) <= interdictor.getInterdictionRangeSquared())
								event.setCanceled(true);
						}
					}
				}
			}
		}
		if(event.getEntityLiving().getActivePotionEffect(IEPotions.stunned)!=null)
			event.setCanceled(true);
	}

	@SubscribeEvent
	public void onEntitySpawnCheck(LivingSpawnEvent.CheckSpawn event)
	{
		if(event.getResult()==Event.Result.ALLOW||event.getResult()==Event.Result.DENY
				||event.isSpawner())
			return;
		if(event.getEntityLiving().getType().getClassification()==EntityClassification.MONSTER)
		{
			synchronized(interdictionTiles)
			{
				RegistryKey<World> dimension = event.getEntity().world.func_234923_W_();
				if(interdictionTiles.containsKey(dimension))
				{
					Iterator<ISpawnInterdiction> it = interdictionTiles.get(dimension).iterator();
					while(it.hasNext())
					{
						ISpawnInterdiction interdictor = it.next();
						if(interdictor instanceof TileEntity)
						{
							if(((TileEntity)interdictor).isRemoved()||((TileEntity)interdictor).getWorld()==null)
								it.remove();
							else if(Vector3d.func_237489_a_(((TileEntity)interdictor).getPos()).squareDistanceTo(event.getEntity().getPositionVec()) <= interdictor.getInterdictionRangeSquared())
								event.setResult(Event.Result.DENY);
						}
						else if(interdictor instanceof Entity)
						{
							if(!((Entity)interdictor).isAlive()||((Entity)interdictor).world==null)
								it.remove();
							else if(((Entity)interdictor).getDistanceSq(event.getEntity()) <= interdictor.getInterdictionRangeSquared())
								event.setResult(Event.Result.DENY);
						}
					}
				}
			}
		}
	}

	@SubscribeEvent()
	public void digSpeedEvent(PlayerEvent.BreakSpeed event)
	{
		ItemStack current = event.getPlayer().getHeldItem(Hand.MAIN_HAND);
		//Stop the combustion drill from working underwater
		if(!current.isEmpty()&&current.getItem()==Tools.drill&&event.getPlayer().isInWater())
			if(((DrillItem)Tools.drill).getUpgrades(current).getBoolean("waterproof"))
				event.setNewSpeed(event.getOriginalSpeed()*5);
			else
				event.setCanceled(true);
		if(event.getState().getBlock()==MetalDevices.razorWire)
			if(current.getItem()!=Tools.wirecutter)
			{
				event.setCanceled(true);
				RazorWireTileEntity.applyDamage(event.getEntityLiving());
			}
		TileEntity te = event.getPlayer().getEntityWorld().getTileEntity(event.getPos());
		if(te instanceof IEntityProof&&!((IEntityProof)te).canEntityDestroy(event.getPlayer()))
			event.setCanceled(true);
	}

	@SubscribeEvent
	public void onAnvilChange(AnvilUpdateEvent event)
	{
		if(!event.getLeft().isEmpty()&&event.getLeft().getItem() instanceof IDrillHead&&((IDrillHead)event.getLeft().getItem()).getHeadDamage(event.getLeft()) > 0)
		{
			if(!event.getRight().isEmpty()&&event.getLeft().getItem().getIsRepairable(event.getLeft(), event.getRight()))
			{
				event.setOutput(event.getLeft().copy());
				int repair = Math.min(
						((IDrillHead)event.getOutput().getItem()).getHeadDamage(event.getOutput()),
						((IDrillHead)event.getOutput().getItem()).getMaximumHeadDamage(event.getOutput())/4);
				int cost = 0;
				for(; repair > 0&&cost < event.getRight().getCount(); ++cost)
				{
					((IDrillHead)event.getOutput().getItem()).damageHead(event.getOutput(), -repair);
					event.setCost(Math.max(1, repair/200));
					repair = Math.min(
							((IDrillHead)event.getOutput().getItem()).getHeadDamage(event.getOutput()),
							((IDrillHead)event.getOutput().getItem()).getMaximumHeadDamage(event.getOutput())/4);
				}
				event.setMaterialCost(cost);

				if(event.getName()==null||event.getName().isEmpty())
				{
					if(event.getLeft().hasDisplayName())
					{
						event.setCost(event.getCost()+5);
						event.getOutput().clearCustomName();
					}
				}
				else if(!event.getName().equals(event.getLeft().getDisplayName()))
				{
					event.setCost(event.getCost()+5);
					if(event.getLeft().hasDisplayName())
						event.setCost(event.getCost()+2);
					event.getOutput().setDisplayName(new StringTextComponent(event.getName()));
				}
			}
		}
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void breakLast(BlockEvent.BreakEvent event)
	{
		if(event.getState().getBlock() instanceof IEMultiblockBlock)
		{
			TileEntity te = event.getWorld().getTileEntity(event.getPos());
			if(te instanceof MultiblockPartTileEntity)
				((MultiblockPartTileEntity)te).onlyLocalDissassembly = event.getWorld().getWorldInfo().getGameTime();
		}
	}

	@SubscribeEvent
	public void onFurnaceBurnTime(FurnaceFuelBurnTimeEvent event)
	{
		if(Utils.isFluidRelatedItemStack(event.getItemStack()))
			FluidUtil.getFluidContained(event.getItemStack()).ifPresent(fs -> {
				if(!fs.isEmpty()&&fs.getFluid()==IEContent.fluidCreosote)
					event.setBurnTime((int)(0.8*fs.getAmount()));
			});
	}
}
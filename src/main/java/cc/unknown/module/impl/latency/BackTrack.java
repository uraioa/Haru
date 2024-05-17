package cc.unknown.module.impl.latency;

import java.awt.Color;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import cc.unknown.event.impl.EventLink;
import cc.unknown.event.impl.move.PreUpdateEvent;
import cc.unknown.event.impl.network.DisconnectionEvent;
import cc.unknown.event.impl.network.PacketEvent;
import cc.unknown.event.impl.other.ClickGuiEvent;
import cc.unknown.event.impl.player.TickEvent;
import cc.unknown.event.impl.render.RenderEvent;
import cc.unknown.event.impl.world.WorldEvent;
import cc.unknown.mixin.interfaces.network.packets.IC02PacketUseEntity;
import cc.unknown.mixin.interfaces.network.packets.IS14PacketEntity;
import cc.unknown.module.impl.Module;
import cc.unknown.module.impl.api.Category;
import cc.unknown.module.impl.api.Register;
import cc.unknown.module.setting.impl.BooleanValue;
import cc.unknown.module.setting.impl.SliderValue;
import cc.unknown.ui.clickgui.raven.impl.api.Theme;
import cc.unknown.utils.client.RenderUtil;
import cc.unknown.utils.network.PacketUtil;
import cc.unknown.utils.network.TimedPacket;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S40PacketDisconnect;
import net.minecraft.util.Vec3;

@Register(name = "BackTrack", category = Category.Latency)
public class BackTrack extends Module {

	
	private SliderValue latency = new SliderValue("Latency delay", 90, 1, 1000, 1);
	private SliderValue enemyDistance = new SliderValue("Enemy distance", 4.5, 3.1, 6.0, 0.01);
	private BooleanValue onlyCombat = new BooleanValue("Only during combat", true);
	private BooleanValue predictPosition = new BooleanValue("Render player position", true);
	private BooleanValue useThemeColor = new BooleanValue("Use themed colors", false);
	private SliderValue boxColor = new SliderValue("Box color [H/S/B]", 0, 0, 350, 10);
	private BooleanValue disableOnWorldChange = new BooleanValue("Disable on world change", false);
	private BooleanValue disableOnDisconnect = new BooleanValue("Disable on disconnect", false);
	private BooleanValue disableHurt = new BooleanValue("Disable when receive dmg", false);

	private Queue<TimedPacket> packetQueue = new ConcurrentLinkedQueue<>();
	private Vec3 vec3, lastVec3;
	private EntityPlayer target;
	private int attackTicks;

	public BackTrack() {
		this.registerSetting(latency, enemyDistance, onlyCombat, predictPosition, useThemeColor, boxColor,
				disableOnWorldChange, disableOnDisconnect, disableHurt);
	}

	@EventLink
	public void onGui(ClickGuiEvent e) {
		this.setSuffix("- [" + latency.getInputToInt() + " ms]");
	}

	@Override
	public void onEnable() {
		super.onEnable();
		if (mc.thePlayer == null) {
			toggle();
			return;
		}

		packetQueue.clear();
		vec3 = lastVec3 = null;
		target = null;
	}

	@Override
	public void onDisable() {
		super.onDisable();
		if (mc.thePlayer == null)
			return;

		if (mc.thePlayer != null && !packetQueue.isEmpty())
			packetQueue.forEach(timedPacket -> {
				PacketUtil.receivePacketSilent(timedPacket.getPacket());
			});
		packetQueue.clear();

	}

	@EventLink
	public void onPreUpdate(PreUpdateEvent e) {
		try {
			attackTicks++;

			if (attackTicks > 7 || vec3.distanceTo(mc.thePlayer.getPositionVector()) > enemyDistance.getInput()) {
				target = null;
				vec3 = lastVec3 = null;
			}

			lastVec3 = vec3;
		} catch (NullPointerException ignored) {

		}

	}

	@EventLink
	public void onPreTick(TickEvent.Pre e) {
		while (!packetQueue.isEmpty()) {
			if (packetQueue.peek().getCold().getCum(latency.getInputToInt())) {
				Packet<?> packet = packetQueue.poll().getPacket();
				PacketUtil.receivePacketSilent(packet);
			} else {
				break;
			}
		}

		if (packetQueue.isEmpty() && target != null) {
			vec3 = target.getPositionVector();
		}
	}

	@EventLink
	public void onRender(RenderEvent e) {
		if (e.is3D()) {
			if (target == null)
				return;

			if (predictPosition.isToggled()) {
				RenderUtil.drawBox(target, vec3, lastVec3, useThemeColor.isToggled() ? Theme.instance.getMainColor() : Color.getHSBColor((boxColor.getInputToFloat() % 360) / 360.0f, 1.0f, 1.0f));
			}
		}
	}

	@EventLink
	public void onPacket(PacketEvent e) {
		Packet<?> p = e.getPacket();
		
		if (disableHurt.isToggled() && mc.thePlayer.getHealth() < mc.thePlayer.getMaxHealth()) {
			if (mc.thePlayer.hurtTime != 0) {
				releaseAll();
				return;
			}
		}

		try {
			if (e.isReceive()) {

				if (mc.thePlayer == null || mc.thePlayer.ticksExisted < 20) {
					packetQueue.clear();
					return;
				}

				if (target == null) {
					releaseAll();
					return;
				}

				if (e.isCancelled())
					return;

				if (p instanceof S19PacketEntityStatus || p instanceof S02PacketChat)
					return;

				if (p instanceof S08PacketPlayerPosLook || p instanceof S40PacketDisconnect) {
					releaseAll();
					target = null;
					vec3 = lastVec3 = null;
					return;

				} else if (p instanceof S13PacketDestroyEntities) {
					S13PacketDestroyEntities wrapper = (S13PacketDestroyEntities) p;
					for (int id : wrapper.getEntityIDs()) {
						if (id == target.getEntityId()) {
							target = null;
							vec3 = lastVec3 = null;
							releaseAll();
							return;
						}
					}
				} else if (p instanceof S14PacketEntity) {
					S14PacketEntity wrapper = (S14PacketEntity) p;
					if (((IS14PacketEntity) wrapper).getEntityId() == target.getEntityId()) {
						vec3 = vec3.addVector(wrapper.func_149062_c() / 32.0D, wrapper.func_149061_d() / 32.0D,
								wrapper.func_149064_e() / 32.0D);
					}
				} else if (p instanceof S18PacketEntityTeleport) {
					S18PacketEntityTeleport wrapper = (S18PacketEntityTeleport) p;
					if (wrapper.getEntityId() == target.getEntityId()) {
						vec3 = new Vec3(wrapper.getX() / 32.0D, wrapper.getY() / 32.0D, wrapper.getZ() / 32.0D);
					}
				}

				packetQueue.add(new TimedPacket(p));
				e.setCancelled(true);
			}
		} catch (NullPointerException ignorethisshit) {

		}

		if (e.isSend()) {
			if (p instanceof C02PacketUseEntity) {
				C02PacketUseEntity wrapper = (C02PacketUseEntity) p;
				if (onlyCombat.isToggled() && wrapper.getAction() != C02PacketUseEntity.Action.ATTACK)
					return;
				try {
					attackTicks = 0;

					EntityPlayer entity = (EntityPlayer) wrapper.getEntityFromWorld(mc.theWorld);

					if (target != null && ((IC02PacketUseEntity) wrapper).getEntityId() == target.getEntityId())
						return;

					target = entity;
					vec3 = lastVec3 = entity.getPositionVector();
				} catch (ClassCastException fuck) {
				}
			}
		}
	}

	@EventLink
	public void disableOnWorldChange(WorldEvent e) {
		if (disableOnWorldChange.isToggled()) {
			this.disable();
		}
	}

	@EventLink
	public void onDisconnect(DisconnectionEvent e) {
		if (disableOnDisconnect.isToggled()) {
			this.disable();
		}
	}

	private void releaseAll() {
		if (!packetQueue.isEmpty()) {
			packetQueue.forEach(timedPacket -> {
				PacketUtil.receivePacketSilent(timedPacket.getPacket());
			});
			packetQueue.clear();
		}
	}

}
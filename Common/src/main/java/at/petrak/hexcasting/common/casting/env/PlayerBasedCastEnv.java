package at.petrak.hexcasting.common.casting.env;

import at.petrak.hexcasting.api.HexAPI;
import at.petrak.hexcasting.api.addldata.ADMediaHolder;
import at.petrak.hexcasting.api.advancements.HexAdvancementTriggers;
import at.petrak.hexcasting.api.casting.ParticleSpray;
import at.petrak.hexcasting.api.casting.castables.Action;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.misc.FrozenColorizer;
import at.petrak.hexcasting.api.misc.HexDamageSources;
import at.petrak.hexcasting.api.mod.HexConfig;
import at.petrak.hexcasting.api.mod.HexStatistics;
import at.petrak.hexcasting.api.utils.HexUtils;
import at.petrak.hexcasting.api.utils.MediaHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static at.petrak.hexcasting.api.HexAPI.modLoc;

public abstract class PlayerBasedCastEnv extends CastingEnvironment {
    protected final ServerPlayer caster;
    protected final InteractionHand castingHand;

    protected PlayerBasedCastEnv(ServerPlayer caster, InteractionHand castingHand) {
        super(caster.getLevel());
        this.caster = caster;
        this.castingHand = castingHand;
    }

    @Override
    public @Nullable ServerPlayer getCaster() {
        return this.caster;
    }

    @Override
    protected List<ItemStack> getUsableStacks(StackDiscoveryMode mode) {
        return switch (mode) {
            case QUERY -> {
                var out = new ArrayList<ItemStack>();

                var offhand = this.caster.getItemInHand(HexUtils.otherHand(this.castingHand));
                if (!offhand.isEmpty()) {
                    out.add(offhand);
                }

                // If we're casting from the main hand, try to pick from the slot one to the right of the selected slot
                // Otherwise, scan the hotbar left to right
                var anchorSlot = this.castingHand == InteractionHand.MAIN_HAND
                    ? (this.caster.getInventory().selected + 1) % 9
                    : 0;


                for (int delta = 0; delta < 9; delta++) {
                    var slot = (anchorSlot + delta) % 9;
                    out.add(this.caster.getInventory().getItem(slot));
                }

                yield out;
            }
            case EXTRACTION -> {
                // https://wiki.vg/Inventory is WRONG
                // slots 0-8 are the hotbar
                // for what purpose i cannot imagine
                // http://redditpublic.com/images/b/b2/Items_slot_number.png looks right
                // and offhand is 150 Inventory.java:464
                var out = new ArrayList<ItemStack>();

                // First, the inventory backwards
                Inventory inv = this.caster.getInventory();
                for (int i = inv.getContainerSize(); i >= 0; i--) {
                    if (i != inv.selected) {
                        out.add(inv.getItem(i));
                    }
                }

                // then the offhand, then the selected hand
                out.addAll(inv.offhand);
                out.add(inv.getSelected());

                yield out;
            }
        };
    }

    @Override
    public boolean isVecInRange(Vec3 vec) {
        var sentinel = HexAPI.instance().getSentinel(this.caster);
        if (sentinel != null
            && sentinel.extendsRange()
            && this.caster.getLevel().dimension() == sentinel.dimension()
            && vec.distanceToSqr(sentinel.position()) <= Action.MAX_DISTANCE_FROM_SENTINEL * Action.MAX_DISTANCE_FROM_SENTINEL
        ) {
            return true;
        }

        return vec.distanceToSqr(this.caster.position()) <= Action.MAX_DISTANCE * Action.MAX_DISTANCE;
    }

    @Override
    public ItemStack getAlternateItem() {
        var otherHand = HexUtils.otherHand(this.castingHand);
        var stack = this.caster.getItemInHand(otherHand);
        if (stack.isEmpty()) {
            return ItemStack.EMPTY.copy();
        } else {
            return stack;
        }
    }

    /**
     * Search the player's inventory for media ADs and use them.
     */
    protected long extractMediaFromInventory(long costLeft, boolean allowOvercast) {
        List<ADMediaHolder> sources = MediaHelper.scanPlayerForMediaStuff(this.caster);

        for (var source : sources) {
            var found = MediaHelper.extractMedia(source, (int) costLeft, true, false);
            costLeft -= found;
            if (costLeft <= 0) {
                break;
            }
        }

        if (costLeft > 0 && allowOvercast) {
            var mediaToHealth = HexConfig.common().mediaToHealthRate();
            var healthToRemove = Math.max(costLeft / mediaToHealth, 0.5);
            var mediaAbleToCastFromHP = this.caster.getHealth() * mediaToHealth;

            Mishap.trulyHurt(this.caster, HexDamageSources.OVERCAST, healthToRemove);

            var actuallyTaken = Mth.ceil(mediaAbleToCastFromHP - (this.caster.getHealth() * mediaToHealth));

            HexAdvancementTriggers.OVERCAST_TRIGGER.trigger(this.caster, actuallyTaken);
            this.caster.awardStat(HexStatistics.MEDIA_OVERCAST, actuallyTaken);

            costLeft -= actuallyTaken;
        }

        return costLeft;
    }

    protected boolean canOvercast() {
        var adv = this.world.getServer().getAdvancements().getAdvancement(modLoc("y_u_no_cast_angy"));
        var advs = this.caster.getAdvancements();
        return advs.getOrStartProgress(adv).isDone();
    }

    @Override
    public void produceParticles(ParticleSpray particles, FrozenColorizer colorizer) {
        particles.sprayParticles(this.world, colorizer);
    }
}

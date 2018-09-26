package ichttt.mods.firstaid.common.apiimpl;

import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;
import ichttt.mods.firstaid.FirstAid;
import ichttt.mods.firstaid.api.FirstAidRegistry;
import ichttt.mods.firstaid.api.IDamageDistribution;
import ichttt.mods.firstaid.api.damagesystem.AbstractPartHealer;
import ichttt.mods.firstaid.api.debuff.IDebuff;
import ichttt.mods.firstaid.api.debuff.builder.IDebuffBuilder;
import ichttt.mods.firstaid.api.enums.EnumDebuffSlot;
import ichttt.mods.firstaid.api.enums.EnumPlayerPart;
import ichttt.mods.firstaid.common.damagesystem.debuff.ConstantDebuff;
import ichttt.mods.firstaid.common.damagesystem.debuff.OnHitDebuff;
import ichttt.mods.firstaid.common.damagesystem.debuff.SharedDebuff;
import ichttt.mods.firstaid.common.damagesystem.distribution.RandomDamageDistribution;
import ichttt.mods.firstaid.common.damagesystem.distribution.StandardDamageDistribution;
import ichttt.mods.firstaid.common.util.CommonUtils;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

public class FirstAidRegistryImpl extends FirstAidRegistry {
    public static final FirstAidRegistryImpl INSTANCE = new FirstAidRegistryImpl();
    private final Map<String, IDamageDistribution> DISTRIBUTION_MAP = new HashMap<>();
    private final Map<Item, Pair<Function<ItemStack, AbstractPartHealer>, Integer>> HEALER_MAP = new HashMap<>();
    private final Multimap<EnumDebuffSlot, IDebuff> RAW_DEBUFF_MAP = HashMultimap.create();
    private ImmutableMap<EnumDebuffSlot, IDebuff[]> BAKED_DEBUFF_MAP;
    private boolean registrationAllowed = true;

    public static void finish() {
        FirstAidRegistry registryImpl = FirstAidRegistry.getImpl();
        if (registryImpl == null)
            throw new IllegalStateException("The apiimpl has not been set! Something went seriously wrong!");
        if (registryImpl != INSTANCE)
            throw new IllegalStateException("A mod has registered a custom apiimpl for the registry. THIS IS NOT ALLOWED!" +
            "It should be " + INSTANCE.getClass().getName() + " but it actually is " + registryImpl.getClass().getName());

        INSTANCE.buildDebuffs(true);
    }

    private void buildDebuffs(boolean finalize) {
        if (!registrationAllowed) throw new IllegalStateException("Registry is closed");

        if (finalize) {
            FirstAid.LOGGER.info("Finalizing registry");
            registrationAllowed = false;
        }
        this.BAKED_DEBUFF_MAP = ImmutableMap.<EnumDebuffSlot, IDebuff[]>builder()
                .put(EnumDebuffSlot.HEAD, RAW_DEBUFF_MAP.get(EnumDebuffSlot.HEAD).toArray(new IDebuff[0]))
                .put(EnumDebuffSlot.ARMS, RAW_DEBUFF_MAP.get(EnumDebuffSlot.ARMS).toArray(new IDebuff[0]))
                .put(EnumDebuffSlot.BODY, RAW_DEBUFF_MAP.get(EnumDebuffSlot.BODY).toArray(new IDebuff[0]))
                .put(EnumDebuffSlot.LEGS_AND_FEET, RAW_DEBUFF_MAP.get(EnumDebuffSlot.LEGS_AND_FEET).toArray(new IDebuff[0]))
                .build();

        if (finalize)
            RAW_DEBUFF_MAP.clear();
    }

    @Override
    public void bindDamageSourceStandard(@Nonnull String damageType, @Nonnull List<Pair<EntityEquipmentSlot, EnumPlayerPart[]>> priorityTable) {
        if (DISTRIBUTION_MAP.containsKey(damageType))
            FirstAid.LOGGER.info("Damage Distribution override detected for source " + damageType);
        DISTRIBUTION_MAP.put(damageType, new StandardDamageDistribution(priorityTable));
    }

    @Override
    public void bindDamageSourceRandom(@Nonnull String damageType, boolean nearestFirst, boolean tryNoKill) {
        if (nearestFirst) {
            if (!tryNoKill)
                DISTRIBUTION_MAP.remove(damageType);
            else
                DISTRIBUTION_MAP.put(damageType, RandomDamageDistribution.NEAREST_NOKILL);
        } else {
            DISTRIBUTION_MAP.put(damageType, tryNoKill ? RandomDamageDistribution.ANY_NOKILL : RandomDamageDistribution.ANY_KILL);
        }
    }

    @Override
    public void bindDamageSourceCustom(@Nonnull String damageType, @Nonnull IDamageDistribution distributionTable) {
        DISTRIBUTION_MAP.put(damageType, distributionTable);
    }

    @Override
    public void registerHealingType(@Nonnull Item item, @Nonnull Function<ItemStack, AbstractPartHealer> factory, int applyTime) {
        if (this.HEALER_MAP.containsKey(item))
            FirstAid.LOGGER.warn("Healing type override detected for item " + item);
        this.HEALER_MAP.put(item, Pair.of(factory, applyTime));
    }

    @Nullable
    @Override
    public AbstractPartHealer getPartHealer(@Nonnull ItemStack type) {
        Pair<Function<ItemStack, AbstractPartHealer>, Integer> pair = this.HEALER_MAP.get(type.getItem());
        if (pair != null)
            return pair.getLeft().apply(type);
        return null;
    }

    @Nullable
    @Override
    public Integer getPartHealingTime(@Nonnull Item item) {
        Pair<Function<ItemStack, AbstractPartHealer>, Integer> pair = this.HEALER_MAP.get(item);
        if (pair != null)
            return pair.getRight();
        return null;
    }

    @Override
    public void registerDebuff(@Nonnull EnumDebuffSlot slot, @Nonnull IDebuffBuilder abstractBuilder) {
        DebuffBuilder builder;
        try {
            builder = (DebuffBuilder) abstractBuilder;
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("Builder must an instance of the default builder received via DebuffBuilderFactory!", e);
        }
        //Build the finished debuff
        FirstAid.LOGGER.debug("Building debuff from mod {} for slot {} with potion effect {}, type = {}", CommonUtils.getActiveModidSafe(), slot, builder.potionName, builder.isOnHit ? "OnHit" : "Constant");
        BooleanSupplier isEnabled = builder.isEnabledSupplier;
        if (isEnabled == null)
            isEnabled = () -> true;

        Preconditions.checkArgument(!builder.map.isEmpty(), "Failed to register debuff with condition has set");
        IDebuff debuff;
        if (builder.isOnHit) {
            debuff = new OnHitDebuff(builder.potionName, builder.map, isEnabled, builder.sound);
        } else {
            Preconditions.checkArgument(builder.sound == null, "Tried to register constant debuff with sound effect.");
            debuff = new ConstantDebuff(builder.potionName, builder.map, isEnabled);
        }
        registerDebuff(slot, debuff);
    }

    @Override
    public void registerDebuff(@Nonnull EnumDebuffSlot slot, @Nonnull IDebuff debuff) {
        if (!registrationAllowed)
            throw new IllegalStateException("Registration must take place before FMLLoadCompleteEvent");

        if (slot.playerParts.length > 1 && !(debuff instanceof SharedDebuff))
            debuff = new SharedDebuff(debuff, slot);

        this.RAW_DEBUFF_MAP.put(slot, debuff);
    }

    @Nonnull
    @Override
    public IDamageDistribution getDamageDistribution(@Nonnull DamageSource source) {
        IDamageDistribution distribution = DISTRIBUTION_MAP.get(source.damageType);
        if (distribution == null)
            distribution = RandomDamageDistribution.NEAREST_KILL;
        return distribution;
    }

    @Nonnull
    @Override
    public IDebuff[] getDebuffs(@Nonnull EnumDebuffSlot slot) {
        if (registrationAllowed) {
            FirstAid.LOGGER.warn("getDebuffs called early - building temp list snapshot");
            buildDebuffs(false);
        }
        return BAKED_DEBUFF_MAP.get(slot);
    }
}

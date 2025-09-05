/*
 * Copyright (c) 2022-2023 Maxmani and contributors.
 * Licensed under the EUPL-1.2 or later.
 */

package net.reimaden.arcadiandream.item.custom.consumables;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsage;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;
import net.reimaden.arcadiandream.ArcadianDream;
import net.reimaden.arcadiandream.sound.ModSounds;
import net.reimaden.arcadiandream.util.DataSaver;
import net.reimaden.arcadiandream.util.IEntityDataSaver;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class HouraiElixirItem extends Item {

    public HouraiElixirItem(Settings settings) {
        super(settings);
    }

    // 物品使用触发逻辑（原功能保留，无修改）
    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        // 若配置禁用饮用，则直接返回，不触发消耗逻辑
        if (!ArcadianDream.CONFIG.houraiElixirOptions.canDrink()) {
            return TypedActionResult.pass(user.getStackInHand(hand));
        }
        // 触发物品消耗动画（饮用动作）
        return ItemUsage.consumeHeldItem(world, user, hand);
    }

    // 核心修复：物品饮用完成后的逻辑（调整服务端顺序、动态效果、数据更新）
    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        Hand hand = user.getActiveHand();

        // 1. 先判断服务端：仅服务端执行数据更新、效果添加等核心逻辑
        if (!world.isClient()) {
            // 1.1 校验是否为服务端玩家（避免非玩家实体触发错误）
            if (!(user instanceof ServerPlayerEntity player)) {
                return super.finishUsing(stack, world, user);
            }

            // 1.2 校验玩家是否实现数据存储接口（确保能读取/保存elixir次数）
            if (!(user instanceof IEntityDataSaver data)) {
                return super.finishUsing(stack, world, user);
            }

            // 1.3 读取当前饮用次数（从玩家NBT中获取，初始为0）
            byte currentElixirLevel = data.arcadiandream$getPersistentData().getByte("elixir");

            // 1.4 根据饮用次数发送对应等级提示（level_1→level_3）
            if (currentElixirLevel < 3) { // 限制最大等级为3，避免超出预期
                switch (currentElixirLevel) {
                    case 0 -> player.sendMessage(Text.translatable("item." + ArcadianDream.MOD_ID + ".hourai_elixir.level_1"));
                    case 1 -> player.sendMessage(Text.translatable("item." + ArcadianDream.MOD_ID + ".hourai_elixir.level_2"));
                    case 2 -> player.sendMessage(Text.translatable("item." + ArcadianDream.MOD_ID + ".hourai_elixir.level_3"));
                    default -> throw new IllegalArgumentException("Invalid Hourai Elixir level: " + currentElixirLevel);
                }

                // 播放饮用音效（仅服务端播放，自动同步到客户端）
                player.playSound(ModSounds.ITEM_HOURAI_ELIXIR_USE, 
                                player.getSoundCategory(), 
                                0.5f, // 音量
                                world.random.nextFloat() * 0.1f + 0.9f); // 随机音调（0.9~1.0）
            }

            // 1.5 动态负面效果：饮用次数越多，效果越强（解决“仅一阶段”问题）
            int baseEffectDuration = 100; // 基础持续时间（100游戏刻=5秒）
            int dynamicDuration = baseEffectDuration + (currentElixirLevel * 50); // 每级+50刻（2.5秒）
            // 失明：持续时间随等级增加
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, dynamicDuration));
            // 虚弱：持续时间随等级增加
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, dynamicDuration));
            // 中毒：持续时间+等级双提升（等级0→1级中毒，1→2级，2→3级）
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, dynamicDuration, currentElixirLevel));

            // 1.6 更新饮用次数（+1）并保存到玩家NBT（推进后续饮用效果）
            DataSaver.addElixir(data, (byte) 1);

            // 1.7 更新游戏统计（记录物品使用次数，原功能保留）
            player.incrementStat(Stats.USED.getOrCreateStat(this));

            // 1.8 物品失效逻辑：让物品可损坏（每次饮用减少1耐久，直至损坏）
            // 注：已删除下方的isDamageable()重写，确保此方法生效
            stack.damage(1, player, e -> e.sendToolBreakStatus(hand));
        }

        // 2. 调用父类方法：确保物品使用后逻辑完整（如消耗动画收尾）
        return super.finishUsing(stack, world, user);
    }

    // 饮用动作持续时间（32游戏刻=1.28秒，原功能保留）
    @Override
    public int getMaxUseTime(ItemStack stack) {
        return 32;
    }

    // 物品使用动作类型（饮用动作，原功能保留）
    @Override
    public UseAction getUseAction(ItemStack stack) {
        return UseAction.DRINK;
    }

    // 物品tooltip提示（原功能保留，无修改）
    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        if (!ArcadianDream.CONFIG.houraiElixirOptions.canDrink()) {
            // 配置禁用时显示“已禁用”提示
            tooltip.add(Text.translatable("item." + ArcadianDream.MOD_ID + ".hourai_elixir.tooltip_disabled"));
        } else {
            // 正常状态显示物品说明
            tooltip.add(Text.translatable("item." + ArcadianDream.MOD_ID + ".hourai_elixir.tooltip"));
        }
        super.appendTooltip(stack, world, tooltip, context);
    }

    // 物品发光效果（原功能保留，无修改）
    @Override
    public boolean hasGlint(ItemStack stack) {
        return true;
    }

    // 禁止物品附魔（原功能保留，无修改）
    @Override
    public boolean isEnchantable(ItemStack stack) {
        return false;
    }

    // 【修复点】删除“物品不可损坏”的强制限制，让stack.damage()生效
    // 原错误代码：@Override public boolean isDamageable() { return false; }
}
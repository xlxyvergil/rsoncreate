package com.xlxyvergil.rsoncreate.handler;

import com.refinedmods.refinedstorage.api.autocrafting.ICraftingPattern;
import com.refinedmods.refinedstorage.api.autocrafting.ICraftingPatternContainer;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.node.INetworkNode;
import com.refinedmods.refinedstorage.api.util.Action;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.crafter.ConnectedInputHandler.ConnectedInput;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity.Inventory;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.ItemHandlerHelper;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 处理RS合成仓向机械动力动力合成器的物品插入逻辑
 *
 * 核心功能：
 * 1. 按照RS处理配方的槽位顺序，逐个将物品插入到动力合成器网格中
 * 2. 遇到占位物品(crafter_slot_cover)时跳过对应网格槽位，不输出到动力合成器
 * 3. 所有物品插入完成后，调用动力合成器API强制启动合成
 * 4. 占位物品已被RS从内部存储提取，需放回RS网络，否则会凭空消失
 */
public class MechanicalCrafterInsertionHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 判断物品是否是占位用的crafter_slot_cover
     */
    private static boolean isPlaceholder(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return AllItems.CRAFTER_SLOT_COVER.isIn(stack);
    }

    /**
     * 处理向动力合成器插入物品的核心逻辑
     */
    public static boolean handleInsertion(
        ICraftingPatternContainer container,
        MechanicalCrafterBlockEntity mcCrafter,
        Collection<ItemStack> toInsert,
        Action action,
        ICraftingPattern pattern
    ) {
        Level level = mcCrafter.getLevel();
        if (level == null) return false;

        // 获取配方的有序输入列表（最多81个槽位，空槽为空列表）
        List<NonNullList<ItemStack>> patternInputs = pattern.getInputs();
        if (patternInputs.isEmpty()) return false;

        // 获取动力合成器网格的所有Inventory（按位置从上到下、从左到右排序）
        ConnectedInput input = mcCrafter.getInput();
        List<Inventory> inventories = input.getInventories(level, mcCrafter.getBlockPos());
        if (inventories.isEmpty()) return false;

        // 创建物品池的可变副本（用于逐个分配到对应槽位）
        List<ItemStack> availableItems = new ArrayList<>();
        for (ItemStack stack : toInsert) {
            availableItems.add(stack.copy());
        }

        // 追踪占位物品数量（用于放回RS网络）
        int placeholdersToReturn = 0;

        // 按配方槽位顺序遍历，将物品映射到动力合成器网格
        int gridIndex = 0;
        for (int patternSlot = 0; patternSlot < patternInputs.size() && gridIndex < inventories.size(); patternSlot++) {
            NonNullList<ItemStack> inputsForSlot = patternInputs.get(patternSlot);

            // 空槽位 → 跳过此网格位置
            if (inputsForSlot.isEmpty() || inputsForSlot.get(0).isEmpty()) {
                gridIndex++;
                continue;
            }

            ItemStack patternItem = inputsForSlot.get(0);

            // 占位物品(crafter_slot_cover) → 跳过此网格槽位，记录待放回数量
            if (isPlaceholder(patternItem)) {
                placeholdersToReturn += patternItem.getCount();
                gridIndex++;
                continue;
            }

            Inventory targetInventory = inventories.get(gridIndex);

            // 从物品池中查找匹配的物品
            ItemStack toPlace = findAndExtractOne(availableItems, patternItem);
            if (toPlace.isEmpty()) {
                return false;
            }

            if (action == Action.SIMULATE) {
                ItemStack remainder = targetInventory.insertItem(0, toPlace, true);
                if (!remainder.isEmpty()) return false;
            } else {
                ItemStack remainder = targetInventory.insertItem(0, toPlace, false);
                if (!remainder.isEmpty()) {
                    LOGGER.warn("RS->MC Crafter: 物品 {} 插入槽位 {} 失败", toPlace.getDescriptionId(), gridIndex);
                    return false;
                }
            }

            gridIndex++;
        }

        // 执行阶段的后续处理
        if (action == Action.PERFORM) {
            // 将占位物品放回RS网络（RS已从内部存储提取，不放回会导致物品丢失）
            if (placeholdersToReturn > 0) {
                returnPlaceholdersToNetwork(container, placeholdersToReturn);
            }

            // 调用动力合成器API强制启动合成
            mcCrafter.checkCompletedRecipe(true);
        }

        return true;
    }

    /**
     * 从物品池中查找匹配的物品并取出1个
     */
    private static ItemStack findAndExtractOne(List<ItemStack> availableItems, ItemStack target) {
        for (int i = 0; i < availableItems.size(); i++) {
            ItemStack available = availableItems.get(i);
            if (!available.isEmpty() && ItemHandlerHelper.canItemStacksStack(available, target)) {
                ItemStack extracted = available.copyWithCount(1);
                available.shrink(1);
                if (available.isEmpty()) {
                    availableItems.remove(i);
                }
                return extracted;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 将RS已提取但未使用的占位物品放回RS网络存储
     */
    private static void returnPlaceholdersToNetwork(ICraftingPatternContainer container, int count) {
        if (!(container instanceof INetworkNode networkNode)) return;

        INetwork network = networkNode.getNetwork();
        if (network == null) return;

        ItemStack placeholderStack = AllItems.CRAFTER_SLOT_COVER.asStack(count);
        if (!placeholderStack.isEmpty()) {
            ItemStack remainder = network.insertItem(placeholderStack, placeholderStack.getCount(), Action.PERFORM);
            if (!remainder.isEmpty()) {
                LOGGER.warn("RS->MC Crafter: 无法放回 {} 个占位物品到RS网络", remainder.getCount());
            }
        }
    }
}

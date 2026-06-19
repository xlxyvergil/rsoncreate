package com.xlxyvergil.rsoncreate.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.ICraftingPatternContainer;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.apiimpl.autocrafting.task.v6.node.ProcessingNode;
import com.refinedmods.refinedstorage.apiimpl.autocrafting.task.v6.node.Node;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import com.xlxyvergil.rsoncreate.handler.MechanicalCrafterInsertionHandler;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;

/**
 * 拦截RS自动合成系统向外部容器插入物品的逻辑
 * 当目标是机械动力的动力合成器时，使用自定义的顺序插入逻辑
 */
@Mixin(value = ProcessingNode.class, remap = false)
public abstract class ProcessingNodeMixin {

    /**
     * 重定向 insertItemsIntoInventory 调用
     * 当目标是动力合成器时，按模板顺序插入物品并触发合成
     */
    @Redirect(
        method = "update",
        at = @At(
            value = "INVOKE",
            target = "Lcom/refinedmods/refinedstorage/api/autocrafting/ICraftingPatternContainer;insertItemsIntoInventory(Ljava/util/Collection;Lcom/refinedmods/refinedstorage/api/util/Action;)Z"
        )
    )
    private boolean redirectInsertItems(ICraftingPatternContainer container, Collection<ItemStack> toInsert, Action action) {
        BlockEntity connectedBE = container.getConnectedBlockEntity();

        if (connectedBE instanceof MechanicalCrafterBlockEntity mcCrafter) {
            // 通过父类Node的getPattern()获取配方
            Node self = (Node) (Object) this;
            return MechanicalCrafterInsertionHandler.handleInsertion(
                container, mcCrafter, toInsert, action, self.getPattern()
            );
        }

        return container.insertItemsIntoInventory(toInsert, action);
    }
}

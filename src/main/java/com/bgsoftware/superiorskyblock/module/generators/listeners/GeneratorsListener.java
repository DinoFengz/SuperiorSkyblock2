package com.bgsoftware.superiorskyblock.module.generators.listeners;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.core.EnumHelper;
import com.bgsoftware.superiorskyblock.core.ObjectsPools;
import com.bgsoftware.superiorskyblock.module.generators.GeneratorsModule;
import com.bgsoftware.superiorskyblock.world.Dimensions;
import com.bgsoftware.superiorskyblock.world.GeneratorType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;

import java.util.Optional;

@SuppressWarnings("unused")
public class GeneratorsListener implements Listener {

    private static final Material BASALT_MATERIAL = EnumHelper.getEnum(Material.class, "BASALT");
    private static final Material SOUL_SOIL_MATERIAL = EnumHelper.getEnum(Material.class, "SOUL_SOIL");
    private static final Material LAVA_MATERIAL = EnumHelper.getEnum(Material.class, "STATIONARY_LAVA", "LAVA");
    private static final Material BLUE_ICE_MATERIAL = EnumHelper.getEnum(Material.class, "BLUE_ICE");

    private final SuperiorSkyblockPlugin plugin;
    private final GeneratorsModule module;

    public GeneratorsListener(SuperiorSkyblockPlugin plugin, GeneratorsModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockFormEvent(BlockFormEvent e) {
        if (!module.isEnabled())
            return;

        try (ObjectsPools.Wrapper<Location> wrapper = ObjectsPools.LOCATION.obtain()) {
            Location blockLocation = e.getBlock().getLocation(wrapper.getHandle());

            Island island = plugin.getGrid().getIslandAt(blockLocation);

            if (island == null)
                return;

            GeneratorType generatorType = e.getNewState().getType() == BASALT_MATERIAL ?
                    GeneratorType.BASALT : GeneratorType.NORMAL;

            if (e.getBlock().getType() != LAVA_MATERIAL || generatorType != GeneratorType.BASALT)
                return;

            Dimension dimension;
            if (module.isMatchGeneratorWorld()) {
                dimension = Dimensions.NETHER;
            } else {
                World blockWorld = blockLocation.getWorld();
                dimension = Optional.ofNullable(plugin.getProviders().getWorldsProvider().getIslandsWorldDimension(blockWorld))
                        .orElseGet(() -> Dimensions.fromEnvironment(blockWorld.getEnvironment()));
            }

            Key generatedBlock = island.generateBlock(blockLocation, dimension, true);

            if (generatedBlock != null && !generatedBlock.equals(generatorType.getDefaultBlock()))
                e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockFromToEvent(BlockFromToEvent e) {
        if (!module.isEnabled())
            return;

        Block eventSourceBlock = e.getBlock();
        Block eventToBlock = e.getToBlock();
        Material liquid = eventSourceBlock.getType();

        if (eventSourceBlock == e.getToBlock()) {
            // We are not interested in self-flow events.
            return;
        }


        if (!eventSourceBlock.isLiquid()) {
            // We are interested only in Water and Lava flow.
            return;
        }


        if (eventToBlock.getType().equals(liquid)) {
            // Not interested into flowing into the same type of liquid.
            return;
        }

        if (!eventToBlock.getType().equals(Material.AIR) &&
                !eventToBlock.getType().equals(Material.WATER) &&
                !eventToBlock.getType().equals(Material.LAVA)) {
            // We are not interested in situations when liquid flows on other blocks
            return;
        }

        try (ObjectsPools.Wrapper<Location> wrapper = ObjectsPools.LOCATION.obtain()) {
            Location blockLocation = eventToBlock.getLocation(wrapper.getHandle());
            World blockWorld = blockLocation.getWorld();

            Island island = plugin.getGrid().getIslandAt(blockLocation);

            if (island == null)
                return;

            boolean canGenerateCobble = canGenerateStone(liquid, eventToBlock) || (liquid == Material.WATER && (canWaterGenerateCobblestone(eventToBlock))) || (liquid == Material.LAVA && canLavaGenerateCobblestone(eventToBlock));
            boolean canGenerateBasalt = canLavaGenerateBasalt(eventToBlock);

            GeneratorType generatorType = GeneratorType.detectGenerator(eventToBlock);
            if (generatorType == GeneratorType.NONE)
                return;


            Dimension dimension;
            if (module.isMatchGeneratorWorld()) {
                dimension = generatorType == GeneratorType.BASALT ? Dimensions.NETHER : Dimensions.NORMAL;
            } else {
                dimension = Optional.ofNullable(plugin.getProviders().getWorldsProvider().getIslandsWorldDimension(blockWorld))
                        .orElseGet(() -> Dimensions.fromEnvironment(blockWorld.getEnvironment()));
            }

            if((dimension == Dimensions.NETHER && canGenerateBasalt) || (dimension == Dimensions.NORMAL && canGenerateCobble)) {
                Key generatedBlock = island.generateBlock(eventToBlock.getLocation(), dimension, true);

                if (generatedBlock != null && !generatedBlock.equals(generatorType.getDefaultBlock()))
                    e.setCancelled(true);
            }

        }
    }


    /**
     * This method returns if current targetBlock could generate Stone Block. (Stone Generators).
     * <p>
     * Stone can be generated only when lava flows over water. Stone will be always generated in a block where water is
     * located.
     *
     * @param liquid Liquid that flows.
     * @param targetBlock Block in which liquid will flow
     * @return true, if below targetBlock is water and liquid is lava or up is lava and liquid is water.
     */
    private boolean canGenerateStone(Material liquid, Block targetBlock)
    {
        // Adding check if water flows under lava increases generation speed, as in next
        // ticks it will detect lava flow anyway.
        // Also it improves performance a bit, as it will run 1 event less.
        return (liquid.equals(Material.LAVA) && targetBlock.getType().equals(Material.WATER)) ||
                (liquid.equals(Material.WATER) && targetBlock.getRelative(BlockFace.UP).getType().equals(Material.LAVA));
    }


    /**
     * This method returns if lava can generate cobblestone.
     * <p>
     * Lava is generating cobblestone in situations if next block where it flows (air block) is adjacent to block that
     * contains water. If that is true than airBlock will be replaced with cobblestone.
     *
     * @param airBlock Air Block that will be replaced with cobblestone
     * @return true, if lava will generate cobblestone
     */
    private boolean canLavaGenerateCobblestone(Block airBlock)
    {
        return GeneratorsListener.containsWater(airBlock.getRelative(BlockFace.DOWN)) ||
                GeneratorsListener.containsWater(airBlock.getRelative(BlockFace.EAST)) ||
                GeneratorsListener.containsWater(airBlock.getRelative(BlockFace.WEST)) ||
                GeneratorsListener.containsWater(airBlock.getRelative(BlockFace.NORTH)) ||
                GeneratorsListener.containsWater(airBlock.getRelative(BlockFace.SOUTH)) ||
                GeneratorsListener.containsWater(airBlock.getRelative(BlockFace.UP));
    }

    /**
     * This method checks if water can generate cobblestone.
     * <p>
     * Water is generating cobblestone in situations when it is directly adjacent to lava block, and this block is not a
     * source block.
     * <p>
     * Node: by default Minecraft logic, lava is replaced with cobblestone/obsidian only if the water is trying to flow
     * on lava. It allows creating situations where lava and water hit each other at max flow distance without creating
     * cobblestone. This overwrite it as it checks next blocks to air block. It is done on intentional!
     *
     * @param airBlock Air Block that will be replaced with water
     * @return Lava block that will be replaced with water or null, if there is no lava near air block.
     */
    private boolean canWaterGenerateCobblestone(Block airBlock)
    {
        return GeneratorsListener.isLava(airBlock.getRelative(BlockFace.DOWN)) ||
                GeneratorsListener.isLava(airBlock.getRelative(BlockFace.EAST)) ||
                GeneratorsListener.isLava(airBlock.getRelative(BlockFace.WEST)) ||
                GeneratorsListener.isLava(airBlock.getRelative(BlockFace.NORTH)) ||
                GeneratorsListener.isLava(airBlock.getRelative(BlockFace.SOUTH)) ||
                GeneratorsListener.isLava(airBlock.getRelative(BlockFace.UP));
    }


    /**
     * This method checks if water can generate cobblestone.
     * <p>
     * Water is generating cobblestone in situations when it is directly adjacent to lava block, and this block is not a
     * source block.
     * <p>
     * Node: by default Minecraft logic, lava is replaced with cobblestone/obsidian only if the water is trying to flow
     * on lava. It allows creating situations where lava and water hit each other at max flow distance without creating
     * cobblestone. This overwrite it as it checks next blocks to air block. It is done on intentional!
     *
     * @param airBlock Air Block that will be replaced with water
     * @return Lava block that will be replaced with water or null, if there is no lava near air block.
     */
    private boolean canLavaGenerateBasalt(Block airBlock)
    {
        if(!(airBlock.getRelative(BlockFace.DOWN).getType() == SOUL_SOIL_MATERIAL)) {
            return false;
        }

        return GeneratorsListener.isBlueIce(airBlock.getRelative(BlockFace.EAST)) ||
                GeneratorsListener.isBlueIce(airBlock.getRelative(BlockFace.WEST)) ||
                GeneratorsListener.isBlueIce(airBlock.getRelative(BlockFace.NORTH)) ||
                GeneratorsListener.isBlueIce(airBlock.getRelative(BlockFace.SOUTH)) ||
                GeneratorsListener.isBlueIce(airBlock.getRelative(BlockFace.UP));
    }

// ---------------------------------------------------------------------
// Section: Static methods
// ---------------------------------------------------------------------


    /**
     * This method returns if target block is water block or contains water.
     *
     * @param block Block that must be checked.
     * @return true if block type is water or it is waterlogged.
     */
    private static boolean containsWater(Block block)
    {
        return block.getType().equals(Material.WATER);
//                block.getBlockData().getAsString().contains("waterlogged=true");
    }


    /**
     * This method returns if given block contains lava and is not a source block.
     *
     * @param block Block that must be checked.
     * @return true if block contains lava and is not source block.
     */
    private static boolean isLava(Block block)
    {
        return block.getType().equals(Material.LAVA);
    }

    /**
     * This method returns if given block contains lava and is not a source block.
     *
     * @param block Block that must be checked.
     * @return true if block contains lava and is not source block.
     */
    private static boolean isBlueIce(Block block)
    {
        return block.getType().equals(BLUE_ICE_MATERIAL);
    }
}

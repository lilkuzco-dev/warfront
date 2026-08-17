package io.github.lilkuzcodev.warfront.civilization;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public enum CitizenProfession {
	MINER("miner", Items.IRON_PICKAXE, "minecraft:raw_iron"),
	FARMER("farmer", Items.IRON_HOE, "minecraft:wheat"),
	BUILDER("builder", Items.IRON_AXE, "minecraft:oak_planks"),
	TRADER("trader", Items.EMERALD, "warfront:trade_bundle"),
	LABORER("laborer", Items.BARREL, "warfront:haul_bundle");

	private final String id;
	private final Item tool;
	private final String abstractOutput;

	CitizenProfession(String id, Item tool, String abstractOutput) {
		this.id = id;
		this.tool = tool;
		this.abstractOutput = abstractOutput;
	}

	public String id() { return id; }
	public Item tool() { return tool; }
	public String abstractOutput() { return abstractOutput; }

	public static CitizenProfession byId(String id) {
		for (CitizenProfession profession : values()) {
			if (profession.id.equals(id)) return profession;
		}
		return LABORER;
	}
}

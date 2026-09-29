package com.takashi.dungeons.editor;

import com.takashi.dungeons.loot.ItemClass;
import com.takashi.dungeons.loot.LootRegistry;
import com.takashi.dungeons.loot.RarityWeights;
import com.takashi.dungeons.mob.Difficulty;
import com.takashi.dungeons.yaml.YamlPatch;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@code rarity:} block as the editors see it — six weights and what they turn into.
 *
 * <p>The numbers an operator types are weights; the numbers they care about are percentages, and
 * percentages <b>per difficulty</b>, because the multiplier moves weight out of common. So every
 * change is shown three ways at once, and the one trap the rule has — common running out, after
 * which medium and hard give the same table — is shown the moment it happens, the editor's version
 * of the warning {@link LootRegistry} prints at load.
 */
final class RaritySplit {

    private RaritySplit() {
    }

    static RarityWeights of(Map<ItemClass, Integer> weights) {
        return new RarityWeights(weights);
    }

    static Map<ItemClass, Integer> read(Draft draft, String prefix) {
        Map<ItemClass, Integer> weights = new EnumMap<>(ItemClass.class);
        for (ItemClass itemClass : ItemClass.values()) {
            BigDecimal value = Num.of(draft.get(prefix + itemClass.key()));
            weights.put(itemClass, value == null ? 0 : value.intValue());
        }
        return weights;
    }

    /** {@code 62.5%}, {@code 2%}, {@code 0.5%}. */
    static String percent(double share) {
        return BigDecimal.valueOf(share * 100).setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString() + "%";
    }

    /** Lore: the split at every difficulty, plus the capping warning when it applies. */
    static List<String> preview(RarityWeights weights, LootRegistry registry) {
        List<String> lore = new ArrayList<>();
        if (weights.isEmpty()) {
            lore.add("<red>Every weight is 0 - a table needs at least one.");
            return lore;
        }
        for (Difficulty difficulty : Difficulty.values()) {
            double multiplier = registry.multiplier(difficulty);
            RarityWeights scaled = weights.scaled(multiplier);
            StringBuilder line = new StringBuilder("<white>" + difficulty.key() + " <dark_gray>×"
                    + multiplier + "<gray>  ");
            for (ItemClass itemClass : ItemClass.values()) {
                if (scaled.weight(itemClass) > 0) {
                    line.append(itemClass.key()).append(' ').append(percent(scaled.share(itemClass)))
                            .append("  ");
                }
            }
            lore.add(line.toString().stripTrailing());
        }
        String capped = cappedAt(weights, registry);
        if (capped != null) {
            lore.add("");
            lore.add(capped);
        }
        lore.add("");
        lore.add("<dark_gray>Total " + weights.total() + " - the shipped file uses 1000.");
        lore.add("<dark_gray>Difficulty moves weight from common to rare+.");
        return lore;
    }

    /**
     * The warning line when common cannot pay for the highest multiplier, or {@code null}.
     * {@code common >= (highest multiplier - 1) x rare total} — {@code loot.yml}'s own formula.
     */
    static @Nullable String cappedAt(RarityWeights weights, LootRegistry registry) {
        double highest = 1.0;
        List<String> capped = new ArrayList<>();
        for (Difficulty difficulty : Difficulty.values()) {
            double multiplier = registry.multiplier(difficulty);
            highest = Math.max(highest, multiplier);
            if (multiplier > 1.0 && weights.rareTotal() > 0
                    && weights.scaled(multiplier).weight(ItemClass.COMMON) == 0) {
                capped.add(difficulty.key());
            }
        }
        if (capped.isEmpty()) {
            return null;
        }
        int needed = (int) Math.ceil((highest - 1.0) * weights.rareTotal());
        return "<red>Common runs out at " + String.join(", ", capped)
                + ": past that point every difficulty gives the SAME table. Needs common >= "
                + needed + ", has " + weights.weight(ItemClass.COMMON) + ".";
    }

    private static final Pattern LEADING_PERCENT = Pattern.compile("^(\\d+(?:\\.\\d+)?)%(.*)$");

    /**
     * Rewrites {@code # 62.5%} comments beside a block's weights so they match the new split.
     * Only a comment that STARTS with a percentage is touched; {@code # boss_chest only - …} is prose
     * and stays as written.
     */
    static void refreshComments(YamlPatch patch, String blockPath) {
        Object raw = patch.get(blockPath);
        if (!(raw instanceof Map<?, ?> map)) {
            return;
        }
        RarityWeights weights;
        try {
            weights = RarityWeights.parse(map, blockPath, RarityWeights.DEFAULT);
        } catch (IllegalArgumentException invalid) {
            return;   // validation will say what is wrong; comments are not the place
        }
        for (ItemClass itemClass : ItemClass.values()) {
            String path = blockPath + "." + itemClass.key();
            String comment = patch.comment(path);
            if (comment == null) {
                continue;
            }
            Matcher matcher = LEADING_PERCENT.matcher(comment);
            if (matcher.matches()) {
                patch.comment(path, percent(weights.share(itemClass)) + matcher.group(2));
            }
        }
    }
}

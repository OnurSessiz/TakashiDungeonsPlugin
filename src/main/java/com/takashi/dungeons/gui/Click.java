package com.takashi.dungeons.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.math.BigDecimal;

/**
 * One click on a menu button, and the conventions every editor button shares.
 *
 * <h2>Numbers are adjusted, not typed</h2>
 * Left raises, right lowers, shift makes the step ten times larger, and the drop key (Q) asks for an
 * exact value in chat. Typing is the slow path on purpose: most edits are "a bit more" or "a bit
 * less", and a stepper does that without leaving the window — the chat round trip is there for the
 * number that is far away.
 */
public record Click(Player player, ClickType type) {

    /**
     * A plain or shift left click — NOT {@code type.isLeftClick()}, which also counts
     * {@link ClickType#DOUBLE_CLICK}. A fast double click arrives as LEFT, LEFT, DOUBLE_CLICK, so
     * counting the third would make two clicks step three times.
     */
    public boolean left() {
        return type == ClickType.LEFT || type == ClickType.SHIFT_LEFT;
    }

    public boolean right() {
        return type == ClickType.RIGHT || type == ClickType.SHIFT_RIGHT;
    }

    public boolean shift() {
        return type.isShiftClick();
    }

    /** Q, or ctrl+Q — "let me type it". */
    public boolean drop() {
        return type == ClickType.DROP || type == ClickType.CONTROL_DROP;
    }

    /** The signed step this click means: ±small, ±large with shift, 0 for anything else. */
    public BigDecimal step(BigDecimal small) {
        BigDecimal size = shift() ? small.multiply(BigDecimal.TEN) : small;
        if (left()) {
            return size;
        }
        if (right()) {
            return size.negate();
        }
        return BigDecimal.ZERO;
    }

    public int step(int small) {
        return step(BigDecimal.valueOf(small)).intValue();
    }
}

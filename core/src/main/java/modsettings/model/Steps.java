package modsettings.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The -/+ stepper next to number fields: step size, clamping to the range, and which buttons are enabled.
 *
 * <p>Step size: the sidecar's {@code step}; otherwise 1 for whole numbers, and for numbers with a fraction
 * one unit of the default's last decimal (0.5 steps by 0.1, 0.25 by 0.01). Without a default, the current
 * value's precision is used the same way; a value with no fraction steps by 1.
 */
public final class Steps {
    private Steps() {}

    /** Most decimals a derived step uses (matches the number field's display). */
    public static final int MAX_DECIMALS = 6;

    public static boolean applies(Control c) {
        return c.kind() == Control.Kind.INT || c.kind() == Control.Kind.FLOAT;
    }

    public static double step(Control c) {
        if (c.step() != null && c.step() > 0) return c.step();
        if (c.kind() == Control.Kind.INT) return 1;
        Object basis = c.defaultValue() instanceof Number ? c.defaultValue() : c.value();
        int scale = basis instanceof Number n ? decimals(n.doubleValue()) : 0;
        return scale == 0 ? 1 : BigDecimal.ONE.movePointLeft(scale).doubleValue();
    }

    /** Decimals needed to write {@code d} exactly (capped at {@link #MAX_DECIMALS}). */
    static int decimals(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return 0;
        int s = new BigDecimal(Double.toString(d)).stripTrailingZeros().scale();
        return Math.max(0, Math.min(MAX_DECIMALS, s));
    }

    public static double clamp(Control c, double v) {
        if (c.min() != null && v < c.min()) v = c.min();
        if (c.max() != null && v > c.max()) v = c.max();
        return v;
    }

    /**
     * One step from {@code current} in direction {@code dir} (-1 or +1), clamped to the range and rounded to
     * the step's precision (so 0.1 + 0.2 gives 0.3). Whole-number settings stay whole.
     */
    public static double next(Control c, double current, int dir) {
        double step = step(c);
        double target = current + Integer.signum(dir) * step;
        Double snapped = offGridBound(c, current, dir, step);
        if (snapped != null) target = snapped;
        double v = clamp(c, target);
        int scale = Math.max(decimals(step), c.kind() == Control.Kind.INT ? 0 : decimals(current));
        v = new BigDecimal(Double.toString(v)).setScale(Math.min(scale, MAX_DECIMALS), RoundingMode.HALF_UP).doubleValue();
        if (c.kind() == Control.Kind.INT) v = Math.rint(v);
        return clamp(c, v);
    }

    /**
     * Leaving a bound that is not a multiple of the step (min 0.001, step 1): the first step lands on the
     * nearest multiple of the step in that direction (1), not bound + step (1.001). After that, steps are plain.
     * Returns null when the rule does not apply.
     */
    private static Double offGridBound(Control c, double current, int dir, double step) {
        boolean up = dir > 0;
        Double bound = up ? c.min() : c.max();
        if (bound == null || Math.abs(current - bound) > 1e-12 * Math.max(1.0, Math.abs(bound))) return null;
        BigDecimal ratio = new BigDecimal(Double.toString(bound)).divide(new BigDecimal(Double.toString(step)), 12, RoundingMode.HALF_UP);
        if (ratio.stripTrailingZeros().scale() <= 0) return null; // already a multiple
        BigDecimal k = ratio.setScale(0, up ? RoundingMode.FLOOR : RoundingMode.CEILING).add(up ? BigDecimal.ONE : BigDecimal.ONE.negate());
        return k.multiply(new BigDecimal(Double.toString(step))).doubleValue();
    }

    /** Whether the button for {@code dir} can move the value without leaving the range. */
    public static boolean canStep(Control c, double current, int dir) {
        if (dir < 0) return c.min() == null || current > c.min();
        return c.max() == null || current < c.max();
    }
}

package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Fps;

import java.util.Locale;

/** Converts tool arguments (seconds) to frames, validating them on the way. */
public final class Times {

    private Times() {
    }

    /** Seconds to the nearest frame. Rejects negatives and NaN. */
    public static long frames(Fps fps, String name, double seconds) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds)) {
            throw new EditException(name + " must be a number of seconds");
        }
        if (seconds < 0) {
            throw new EditException(name + "=" + fmt(seconds) + " is invalid: times are seconds >= 0");
        }
        return fps.frames(seconds);
    }

    /** Like {@link #frames} but also requires a value &gt; 0 after rounding to frames. */
    public static long positive(Fps fps, String name, double seconds) {
        long f = frames(fps, name, seconds);
        if (f <= 0) {
            throw new EditException(name + "=" + fmt(seconds) + " is shorter than one frame ("
                    + fps.sec(1) + "s at " + fps.label() + " fps)");
        }
        return f;
    }

    static String fmt(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }
}

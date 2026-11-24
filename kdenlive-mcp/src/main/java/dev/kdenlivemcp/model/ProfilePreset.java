package dev.kdenlivemcp.model;

import java.util.Arrays;
import java.util.stream.Collectors;

/** The presets offered by project_new, mapped to MLT profile names that Kdenlive ships. */
public enum ProfilePreset {
    P1080_30("1080p30", new Profile("atsc_1080p_30", "HD 1080p 30 fps", 1920, 1080, Fps.of(30), 1, 1, 16, 9, 709, true)),
    P1080_60("1080p60", new Profile("atsc_1080p_60", "HD 1080p 60 fps", 1920, 1080, Fps.of(60), 1, 1, 16, 9, 709, true)),
    VERTICAL_1080_30("vertical_1080x1920_30",
            new Profile("vertical_hd_30", "Vertical HD 30 fps", 1080, 1920, Fps.of(30), 1, 1, 9, 16, 709, true)),
    UHD_30("4k30", new Profile("uhd_2160p_30", "4K UHD 2160p 30 fps", 3840, 2160, Fps.of(30), 1, 1, 16, 9, 709, true));

    public final String key;
    public final Profile profile;

    ProfilePreset(String key, Profile profile) {
        this.key = key;
        this.profile = profile;
    }

    public static ProfilePreset byKey(String key) {
        for (ProfilePreset p : values()) {
            if (p.key.equalsIgnoreCase(key == null ? "" : key.trim())) {
                return p;
            }
        }
        throw new EditException("unknown preset '" + key + "'; use one of: " + keys());
    }

    public static String keys() {
        return Arrays.stream(values()).map(p -> p.key).collect(Collectors.joining(", "));
    }
}

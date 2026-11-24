package dev.kdenlivemcp.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data we read from a project file but do not model: extra properties and raw XML child elements
 * (effects, keyframes, ...). Written back unchanged so projects edited in Kdenlive survive a round-trip.
 *
 * @param properties ordered name -> value of unmodelled {@code <property>} elements
 * @param xml serialized XML fragments, one per unmodelled child element
 */
public record Extras(Map<String, String> properties, List<String> xml) {

    public static final Extras NONE = new Extras(Map.of(), List.of());

    public Extras {
        properties = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        xml = List.copyOf(xml);
    }

    public boolean isEmpty() {
        return properties.isEmpty() && xml.isEmpty();
    }
}

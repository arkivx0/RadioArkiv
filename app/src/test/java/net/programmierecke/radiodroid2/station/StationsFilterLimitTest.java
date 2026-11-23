package net.programmierecke.radiodroid2.station;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StationsFilterLimitTest {

    @Test
    void capsNameTagCountryAndLanguageSearches() {
        for (StationsFilter.SearchStyle style : new StationsFilter.SearchStyle[]{
                StationsFilter.SearchStyle.ByName,
                StationsFilter.SearchStyle.ByTagExact,
                StationsFilter.SearchStyle.ByCountryCodeExact,
                StationsFilter.SearchStyle.ByLanguageExact
        }) {
            Map<String, String> params = baseParams();
            StationsFilter.applyRemoteResultLimit(style, params);
            assertEquals("1000", params.get("limit"), "style " + style + " should be capped");
            assertEquals("clickcount", params.get("order"));
        }
    }

    @Test
    void doesNotOverwriteUnrelatedParamsOrAddLimitForUnknownStyle() {
        Map<String, String> params = baseParams();
        // null style is a no-op
        StationsFilter.applyRemoteResultLimit(null, params);
        assertFalse(params.containsKey("limit"));
        assertEquals("true", params.get("reverse"));
    }

    private static Map<String, String> baseParams() {
        Map<String, String> params = new HashMap<>();
        params.put("order", "clickcount");
        params.put("reverse", "true");
        params.put("hidebroken", "true");
        return params;
    }
}

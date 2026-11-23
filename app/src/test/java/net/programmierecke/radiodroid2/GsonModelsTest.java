package net.programmierecke.radiodroid2;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.programmierecke.radiodroid2.players.mpd.MPDServerData;
import net.programmierecke.radiodroid2.proxy.ProxySettings;

import org.junit.jupiter.api.Test;

import java.net.Proxy;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GsonModelsTest {

    @Test
    void countryParsesNameAndCodeFromJson() {
        Collection<CountryCodeDictionary.Country> countries = new Gson().fromJson(
                "[{\"name\":\"France\",\"code\":\"FR\"}]",
                new TypeToken<Collection<CountryCodeDictionary.Country>>(){}.getType());
        assertEquals(1, countries.size());
        CountryCodeDictionary.Country country = countries.iterator().next();
        assertEquals("FR", country.getCode());
        assertEquals("France", country.getName());
    }

    @Test
    void proxySettingsRoundTripKeepsStableFieldNames() {
        ProxySettings settings = new ProxySettings();
        settings.host = "127.0.0.1";
        settings.port = 8080;
        settings.login = "user";
        settings.password = "secret";
        settings.type = Proxy.Type.HTTP;

        String json = new Gson().toJson(settings);
        assertTrue(json.contains("\"host\""));
        assertTrue(json.contains("\"port\""));
        assertTrue(json.contains("\"login\""));
        assertTrue(json.contains("\"password\""));
        assertTrue(json.contains("\"type\""));

        ProxySettings back = new Gson().fromJson(json, ProxySettings.class);
        assertEquals("127.0.0.1", back.host);
        assertEquals(8080, back.port);
        assertEquals("user", back.login);
        assertEquals("secret", back.password);
        assertEquals(Proxy.Type.HTTP, back.type);

        ProxySettings fromLegacy = new Gson().fromJson(
                "{\"host\":\"example.org\",\"port\":3128,\"login\":null,\"password\":null,\"type\":\"SOCKS\"}",
                ProxySettings.class);
        assertEquals("example.org", fromLegacy.host);
        assertEquals(3128, fromLegacy.port);
        assertEquals(Proxy.Type.SOCKS, fromLegacy.type);
    }

    @Test
    void mpdServerDataRoundTripKeepsStableFieldNames() {
        MPDServerData server = new MPDServerData("lounge", "192.0.2.1", 6600, "pw");
        server.id = 3;

        String json = new Gson().toJson(server);
        assertTrue(json.contains("\"id\""));
        assertTrue(json.contains("\"name\""));
        assertTrue(json.contains("\"hostname\""));
        assertTrue(json.contains("\"port\""));
        assertTrue(json.contains("\"password\""));

        MPDServerData back = new Gson().fromJson(json, MPDServerData.class);
        assertEquals(3, back.id);
        assertEquals("lounge", back.name);
        assertEquals("192.0.2.1", back.hostname);
        assertEquals(6600, back.port);
        assertEquals("pw", back.password);

        MPDServerData fromLegacy = new Gson().fromJson(
                "{\"id\":1,\"name\":\"hi-fi\",\"hostname\":\"192.0.2.8\",\"port\":6600,\"password\":\"\"}",
                MPDServerData.class);
        assertEquals(1, fromLegacy.id);
        assertEquals("hi-fi", fromLegacy.name);
        assertEquals("192.0.2.8", fromLegacy.hostname);
        assertEquals(6600, fromLegacy.port);
        assertEquals("", fromLegacy.password);
    }
}

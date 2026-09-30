import java.util.prefs.Preferences;

/** Small persistent settings and MQTT snapshot store for the desktop client. */
final class DesktopPreferences {
    private final Preferences preferences=Preferences.userRoot().node("/de/codex/sonnenbank");
    String getString(String key,String fallback){return preferences.get(key,fallback);}
    void putString(String key,String value){preferences.put(key,value);}
    void remove(String key){preferences.remove(key);}
}

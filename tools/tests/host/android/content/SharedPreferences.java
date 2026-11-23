package android.content;

public interface SharedPreferences {
    boolean getBoolean(String key, boolean fallback);
    String getString(String key, String fallback);
    Editor edit();
    interface Editor {
        Editor putBoolean(String key, boolean value);
        Editor putString(String key, String value);
        Editor clear();
        boolean commit();
    }
}

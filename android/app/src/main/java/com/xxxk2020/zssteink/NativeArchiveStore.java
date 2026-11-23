package com.xxxk2020.zssteink;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Visibility markers only; archiving never changes a document or its study progress. */
final class NativeArchiveStore {
    private static final String PREFIX = "archived:";
    private final SharedPreferences preferences;

    NativeArchiveStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences("native-library-archive", Context.MODE_PRIVATE);
    }

    /** Updates the in-memory marker immediately; Android persists it asynchronously. */
    void setArchived(String id, boolean archived) {
        if (id == null || id.length() == 0) return;
        SharedPreferences.Editor editor = preferences.edit();
        if (archived) editor.putBoolean(PREFIX + id, true);
        else editor.remove(PREFIX + id);
        editor.apply();
    }

    /** Read once with the library on its IO queue, then filter the UI using this snapshot. */
    Set<String> archivedIds() {
        Set<String> result = new LinkedHashSet<String>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(PREFIX) && key.length() > PREFIX.length()
                    && Boolean.TRUE.equals(entry.getValue())) {
                result.add(key.substring(PREFIX.length()));
            }
        }
        return result;
    }
}

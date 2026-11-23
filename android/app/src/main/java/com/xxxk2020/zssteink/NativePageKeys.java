package com.xxxk2020.zssteink;

import android.view.KeyEvent;

/** Tracks only page keys claimed by the reader; holds no View or Activity. */
final class NativePageKeys {
    static final int PASS = 0, CONSUMED = 1, PREVIOUS = 2, NEXT = 3;
    private int pressed;

    int dispatch(KeyEvent event, boolean enabled) {
        int key = event.getKeyCode(), bit = bit(key);
        if (bit == 0) return PASS;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if ((pressed & bit) == 0) return PASS;
            pressed &= ~bit;
            return CONSUMED;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return PASS;
        // Consume repeats and the matching UP even if the first press started
        // an asynchronous page load. One physical press changes at most one page.
        if ((pressed & bit) != 0) return CONSUMED;
        if (!enabled || event.getRepeatCount() != 0 || event.isCanceled()) return PASS;
        pressed |= bit;
        return key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_PAGE_UP ? PREVIOUS : NEXT;
    }

    void reset() { pressed = 0; }

    private static int bit(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_VOLUME_UP: return 1;
            case KeyEvent.KEYCODE_VOLUME_DOWN: return 2;
            case KeyEvent.KEYCODE_PAGE_UP: return 4;
            case KeyEvent.KEYCODE_PAGE_DOWN: return 8;
            default: return 0;
        }
    }
}

package com.zelvari.murmur;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The typing keys, drawn on one canvas so nothing gets rebuilt while you type.
 * Letters go in the moment a finger touches down (no waiting for it to lift), every finger is
 * tracked on its own so fast two-thumb typing never drops or doubles a key, and the nearest
 * key wins even when a tap lands in a gap. Sliding across the letters draws a trail and spells
 * a word (swipe typing).
 */
public class KeyboardView extends View {

    /** What the keys do; the keyboard service decides what that means for the text. */
    public interface Listener {
        void onKeyChar(String s);

        void onKeySpace();

        void onKeyEnter();

        void onKeyDelete();

        void onKeyShift();

        void onKeyMode();

        void onKeySwitch();

        void onKeyPicker();

        /** A swipe began on the letter whose tap was just typed; take that letter back. */
        void onSwipeStart();

        /** The finished swipe, in key units: x / key width, y / letter row height from row 1. */
        void onSwipe(float[] xs, float[] ys, int n);
    }

    static final int CHAR = 0, SHIFT = 1, DEL = 2, MODE = 3, SWITCH = 4, SPACE = 5, ENTER = 6;
    static final int SHIFT_OFF = 0, SHIFT_ONCE = 1, SHIFT_LOCK = 2;

    private static final class Key {
        final int kind;
        final String out;
        final String label;
        final float weight;
        final boolean small;
        final RectF hit = new RectF();
        final RectF face = new RectF();
        int row;

        Key(int kind, String out, String label, float weight, boolean small) {
            this.kind = kind;
            this.out = out;
            this.label = label;
            this.weight = weight;
            this.small = small;
        }
    }

    private static final String NUMBERS = "1234567890";
    private static final String[] LETTERS = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    private static final String[] SYMBOLS = {"@#$%&-+()/", "*\"':;!?=_", "<>[]{}|\\"};

    private final List<Key> keys = new ArrayList<>();
    private final float density;
    private final float radius;
    private float rowNum, rowLetter, gap;
    /** Number row height when shown; rowNum is 0 while the number row is hidden. */
    private float rowNumShown;
    private boolean showNumbers = true;
    private float keyW = 1;
    private float lettersTop;
    private final float[] letterX = new float[26], letterY = new float[26];

    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final Path trail = new Path();

    private int colorKey = 0xFF262A33, colorMod = 0xFF1A1D24, colorPressed = 0xFF3A3F4B;
    private int colorText = 0xFFF2F3F5, colorDim = 0xFF9AA0AC, accent = 0xFF7C5CFF;

    private Listener listener;
    private boolean symbols;
    private int shift = SHIFT_OFF;
    private boolean swipeOn = true, hapticsOn = true, soundOn;
    private AudioManager audio;

    // One entry per finger on the keys.
    private static final class Touch {
        Key key;
        float downX, downY;
        long downAt;
        boolean longFired;
        boolean upActionDone;
    }

    private final SparseArray<Touch> touches = new SparseArray<>();
    private boolean swiping;
    private int swipePointer = -1;
    private final ArrayList<float[]> swipePts = new ArrayList<>();
    private Key preview;
    private float trailAlpha;

    private final Runnable deleteRepeat = new Runnable() {
        @Override
        public void run() {
            if (listener != null) listener.onKeyDelete();
            postDelayed(this, 50);
        }
    };

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            for (int i = 0; i < touches.size(); i++) {
                Touch t = touches.valueAt(i);
                if (t.longFired || t.upActionDone || t.key == null) continue;
                if (t.key.kind != SPACE && t.key.kind != SWITCH) continue;
                if (SystemClock.uptimeMillis() - t.downAt < LONG_MS - 20) continue;
                t.longFired = true;
                buzz();
                if (listener != null) listener.onKeyPicker();
            }
        }
    };

    private final Runnable hidePreview = () -> {
        preview = null;
        invalidate();
    };

    private final Runnable fadeTrail = new Runnable() {
        @Override
        public void run() {
            trailAlpha -= 0.15f;
            if (trailAlpha <= 0) {
                trailAlpha = 0;
                trail.reset();
            } else {
                postDelayed(this, 16);
            }
            invalidate();
        }
    };

    private static final long LONG_MS = 450;

    public KeyboardView(Context context) {
        this(context, null);
    }

    public KeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        rowNumShown = rowNum = 42 * density;
        rowLetter = 52 * density;
        gap = 2.5f * density;
        radius = 9 * density;
        textPaint.setTextAlign(Paint.Align.CENTER);
        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        trailPaint.setStrokeJoin(Paint.Join.ROUND);
        trailPaint.setStrokeWidth(6 * density);
        setSoundEffectsEnabled(false);
        setHapticFeedbackEnabled(true);
        setContentDescription("Keyboard");
        try {
            audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        } catch (RuntimeException ignored) {
            audio = null;
        }
        buildKeys();
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setColors(int key, int mod, int pressed, int text, int dim) {
        colorKey = key;
        colorMod = mod;
        colorPressed = pressed;
        colorText = text;
        colorDim = dim;
        invalidate();
    }

    void setAccent(int color) {
        accent = color;
        invalidate();
    }

    void setOptions(boolean swipe, boolean haptics, boolean sound) {
        swipeOn = swipe;
        hapticsOn = haptics;
        soundOn = sound;
    }

    void setShift(int s) {
        if (s == shift) return;
        shift = s;
        invalidate();
    }

    void setSymbols(boolean on) {
        if (on == symbols) return;
        symbols = on;
        buildKeys();
        requestLayout();
        layoutKeys();
        invalidate();
    }

    /**
     * Sizes the keys. The full-size layout (wide screens) is 42dp numbers and 52dp letters;
     * narrow screens like a foldable's cover screen pass their own scale and number row choice.
     */
    void setSizing(boolean narrow, float scale, boolean numbers, int screenHeightPx) {
        float num = (narrow ? 38 : 42) * density * scale;
        float letter = 52 * density * scale;
        float g = (narrow ? 2f : 2.5f) * density;
        if (narrow && screenHeightPx > 0) {
            // Never let the keys take more than about 40% of a tall, narrow screen.
            float total = (numbers ? num : 0) + 4 * letter;
            float max = screenHeightPx * 0.40f;
            if (total > max) {
                float f = max / total;
                num *= f;
                letter *= f;
            }
        }
        boolean numbersWas = showNumbers;
        if (num == rowNumShown && letter == rowLetter && g == gap && numbers == numbersWas) return;
        rowNumShown = num;
        rowLetter = letter;
        gap = g;
        showNumbers = numbers;
        buildKeys();
        requestLayout();
        layoutKeys();
        invalidate();
    }

    boolean symbols() {
        return symbols;
    }

    /** Letter centres in key units, for the swipe decoder. */
    float[][] letterGeometry() {
        return new float[][]{letterX.clone(), letterY.clone()};
    }

    // ---- layout -------------------------------------------------------------------

    private void buildKeys() {
        keys.clear();
        // Hiding the number row only hides it on the letters; the symbols page keeps its digits.
        rowNum = showNumbers || symbols ? rowNumShown : 0;
        if (showNumbers || symbols) for (char c : NUMBERS.toCharArray()) add(0, new Key(CHAR, String.valueOf(c), String.valueOf(c), 1, true));
        String[] rows = symbols ? SYMBOLS : LETTERS;
        for (char c : rows[0].toCharArray()) add(1, charKey(c));
        for (char c : rows[1].toCharArray()) add(2, charKey(c));
        add(3, symbols ? new Key(CHAR, "…", "…", 1.5f, true) : new Key(SHIFT, null, "⇧", 1.5f, false));
        for (char c : rows[2].toCharArray()) add(3, charKey(c));
        add(3, new Key(DEL, null, "⌫", 1.5f, false));
        add(4, new Key(MODE, null, symbols ? "ABC" : "?123", 1.4f, true));
        add(4, new Key(SWITCH, null, "🌐", 1f, true));
        add(4, new Key(CHAR, ",", ",", 1f, false));
        add(4, new Key(SPACE, " ", "space", 4f, true));
        add(4, new Key(CHAR, ".", ".", 1f, false));
        add(4, new Key(ENTER, null, "⏎", 1.5f, false));
    }

    private static Key charKey(char c) {
        String s = String.valueOf(c);
        return new Key(CHAR, s, s, 1, false);
    }

    private void add(int row, Key k) {
        k.row = row;
        keys.add(k);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = Math.round(rowNum + 4 * rowLetter);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutKeys();
    }

    private void layoutKeys() {
        float w = getWidth();
        if (w <= 0) return;
        keyW = w / 10f;
        lettersTop = rowNum;
        for (int row = 0; row < 5; row++) {
            float top = row == 0 ? 0 : rowNum + (row - 1) * rowLetter;
            float bottom = row == 0 ? rowNum : top + rowLetter;
            float total = 0;
            int count = 0;
            for (Key k : keys) {
                if (k.row != row) continue;
                total += k.weight;
                count++;
            }
            if (count == 0) continue;
            // Rows with fewer than ten keys are centred (the "asdf" row sits half a key in),
            // but the edge keys still catch taps all the way to the edge.
            float unit = row == 4 ? w / total : keyW;
            float x = (w - unit * total) / 2f;
            int i = 0;
            for (Key k : keys) {
                if (k.row != row) continue;
                float right = x + unit * k.weight;
                k.face.set(x + gap, top + gap, right - gap, bottom - gap);
                k.hit.set(i == 0 ? 0 : x, top, i == count - 1 ? w : right, bottom);
                x = right;
                i++;
            }
        }
        // Letter centres for the swipe decoder (letters layout only, any mode).
        for (int r = 0; r < 3; r++) {
            float off = r == 0 ? 0f : r == 1 ? 0.5f : 1.5f;
            for (int i = 0; i < LETTERS[r].length(); i++) {
                int c = LETTERS[r].charAt(i) - 'a';
                letterX[c] = off + i + 0.5f;
                letterY[c] = r + 0.5f;
            }
        }
    }

    // ---- drawing ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        for (Key k : keys) {
            boolean down = isDown(k);
            int fill;
            if (k.kind == ENTER) fill = accent;
            else if (k.kind == CHAR && k.row > 0 || k.kind == SPACE) fill = colorKey;
            else fill = colorMod;
            if (k.kind == SHIFT && shift != SHIFT_OFF) fill = blend(colorMod, accent, 0.45f);
            if (down) fill = k.kind == ENTER ? blend(accent, 0xFFFFFFFF, 0.25f) : colorPressed;
            keyPaint.setColor(fill);
            canvas.drawRoundRect(k.face, radius, radius, keyPaint);
            drawLabel(canvas, k);
        }
        if (trailAlpha > 0 && !trail.isEmpty()) {
            trailPaint.setColor(accent);
            trailPaint.setAlpha(Math.round(220 * trailAlpha));
            canvas.drawPath(trail, trailPaint);
        }
        if (preview != null && !swiping) drawPreview(canvas, preview);
    }

    private void drawLabel(Canvas c, Key k) {
        String label = labelOf(k);
        boolean word = label.length() > 1 && k.kind != CHAR;
        float size = k.kind == SPACE ? 13 : word ? 14 : k.small ? 17 : 22;
        textPaint.setTextSize(size * density);
        textPaint.setTypeface(k.kind == SHIFT && shift == SHIFT_LOCK ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        int color = k.kind == SPACE ? colorDim : k.kind == ENTER ? 0xFFFFFFFF : colorText;
        if (k.kind == SHIFT && shift == SHIFT_LOCK) color = 0xFFFFFFFF;
        textPaint.setColor(color);
        if (k.kind == SHIFT && shift == SHIFT_LOCK) label = "⇪";
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float y = k.face.centerY() - (fm.ascent + fm.descent) / 2f;
        c.drawText(label, k.face.centerX(), y, textPaint);
    }

    private String labelOf(Key k) {
        if (k.kind == CHAR && shift != SHIFT_OFF && Character.isLetter(k.label.charAt(0))) {
            return k.label.toUpperCase(Locale.ROOT);
        }
        return k.label;
    }

    private void drawPreview(Canvas c, Key k) {
        if (k.kind != CHAR) return;
        float w = k.face.width() * 1.25f;
        float h = rowLetter * 1.05f;
        float cx = k.face.centerX();
        float bottom = k.face.top - 2 * density;
        float top = bottom - h;
        if (top < 0) {
            // Top row: no room above, so the bubble sits over the key itself.
            top = k.face.top;
            bottom = top + h;
        }
        float left = Math.max(0, Math.min(getWidth() - w, cx - w / 2f));
        tmp.set(left, top, left + w, bottom);
        bubblePaint.setColor(blend(colorKey, 0xFFFFFFFF, 0.18f));
        bubblePaint.setShadowLayer(6 * density, 0, 2 * density, 0x66000000);
        c.drawRoundRect(tmp, radius * 1.2f, radius * 1.2f, bubblePaint);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(30 * density);
        textPaint.setColor(colorText);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        c.drawText(labelOf(k), tmp.centerX(), tmp.centerY() - (fm.ascent + fm.descent) / 2f, textPaint);
    }

    private boolean isDown(Key k) {
        for (int i = 0; i < touches.size(); i++) {
            Touch t = touches.valueAt(i);
            if (t.key == k && !(swiping && touches.keyAt(i) == swipePointer)) return true;
        }
        return false;
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    // ---- touch --------------------------------------------------------------------

    private Key keyAt(float x, float y) {
        Key best = null;
        float bestD = Float.MAX_VALUE;
        for (Key k : keys) {
            if (k.hit.contains(x, y)) return k;
            float dx = Math.max(0, Math.max(k.hit.left - x, x - k.hit.right));
            float dy = Math.max(0, Math.max(k.hit.top - y, y - k.hit.bottom));
            float d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = k;
            }
        }
        return best;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = e.getActionIndex();
                int id = e.getPointerId(idx);
                if (swiping) return true; // a second finger mid-swipe is ignored
                // Rolling presses: a new finger finishes the keys still held down.
                finishHeldKeys();
                float x = e.getX(idx), y = e.getY(idx);
                Key k = keyAt(x, y);
                if (k == null) return true;
                Touch t = new Touch();
                t.key = k;
                t.downX = x;
                t.downY = y;
                t.downAt = SystemClock.uptimeMillis();
                touches.put(id, t);
                press(k);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (swiping) {
                    int idx = e.findPointerIndex(swipePointer);
                    if (idx >= 0) {
                        for (int h = 0; h < e.getHistorySize(); h++) {
                            addSwipePoint(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h));
                        }
                        addSwipePoint(e.getX(idx), e.getY(idx));
                    }
                    return true;
                }
                if (!swipeOn || symbols || touches.size() != 1) return true;
                int id = touches.keyAt(0);
                Touch t = touches.valueAt(0);
                int idx = e.findPointerIndex(id);
                if (idx < 0 || t.key == null || !isLetter(t.key)) return true;
                float x = e.getX(idx), y = e.getY(idx);
                float dx = x - t.downX, dy = y - t.downY;
                float far = keyW * 0.6f;
                if (dx * dx + dy * dy > far * far) {
                    Key now = keyAt(x, y);
                    if (now != t.key) startSwipe(id, t, e, idx);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int idx = e.getActionIndex();
                int id = e.getPointerId(idx);
                if (swiping && id == swipePointer) {
                    addSwipePoint(e.getX(idx), e.getY(idx));
                    endSwipe();
                    touches.remove(id);
                    return true;
                }
                Touch t = touches.get(id);
                touches.remove(id);
                if (t != null) release(t);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                touches.clear();
                swiping = false;
                swipePointer = -1;
                removeCallbacks(deleteRepeat);
                removeCallbacks(longPress);
                trail.reset();
                preview = null;
                invalidate();
                return true;
            }
            default:
                return true;
        }
    }

    private static boolean isLetter(Key k) {
        return k.kind == CHAR && k.out != null && k.out.length() == 1
                && k.out.charAt(0) >= 'a' && k.out.charAt(0) <= 'z';
    }

    /** Down: letters, symbols, delete, shift and mode act right away. */
    private void press(Key k) {
        buzz();
        click(k.kind);
        switch (k.kind) {
            case CHAR:
                preview = k;
                removeCallbacks(hidePreview);
                if (listener != null) listener.onKeyChar(labelOf(k));
                break;
            case DEL:
                if (listener != null) listener.onKeyDelete();
                removeCallbacks(deleteRepeat);
                postDelayed(deleteRepeat, 380);
                break;
            case SHIFT:
                if (listener != null) listener.onKeyShift();
                break;
            case MODE:
                if (listener != null) listener.onKeyMode();
                break;
            case SPACE:
            case SWITCH:
                removeCallbacks(longPress);
                postDelayed(longPress, LONG_MS);
                break;
            default:
                break;
        }
        invalidate();
    }

    /** Up: space, enter and the keyboard switch act when the finger lifts. */
    private void release(Touch t) {
        if (t.key.kind == DEL) removeCallbacks(deleteRepeat);
        if (t.key == preview) {
            removeCallbacks(hidePreview);
            postDelayed(hidePreview, 60);
        }
        upAction(t);
    }

    private void upAction(Touch t) {
        if (t.upActionDone || t.longFired) {
            t.upActionDone = true;
            return;
        }
        t.upActionDone = true;
        if (listener == null) return;
        switch (t.key.kind) {
            case SPACE:
                listener.onKeySpace();
                break;
            case ENTER:
                listener.onKeyEnter();
                break;
            case SWITCH:
                listener.onKeySwitch();
                break;
            default:
                break;
        }
    }

    private void finishHeldKeys() {
        for (int i = 0; i < touches.size(); i++) {
            Touch t = touches.valueAt(i);
            if (t.key == null) continue;
            if (t.key.kind == SPACE || t.key.kind == ENTER) upAction(t);
            if (t.key.kind == DEL) removeCallbacks(deleteRepeat);
        }
    }

    private void startSwipe(int id, Touch t, MotionEvent e, int idx) {
        swiping = true;
        swipePointer = id;
        preview = null;
        swipePts.clear();
        trail.reset();
        trailAlpha = 1f;
        removeCallbacks(fadeTrail);
        if (listener != null) listener.onSwipeStart();
        addSwipePoint(t.downX, t.downY);
        for (int h = 0; h < e.getHistorySize(); h++) {
            addSwipePoint(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h));
        }
        addSwipePoint(e.getX(idx), e.getY(idx));
    }

    private void addSwipePoint(float x, float y) {
        if (swipePts.size() > 600) return;
        if (swipePts.isEmpty()) {
            trail.moveTo(x, y);
        } else {
            float[] last = swipePts.get(swipePts.size() - 1);
            if (Math.abs(last[0] - x) < 1 && Math.abs(last[1] - y) < 1) return;
            trail.quadTo(last[0], last[1], (last[0] + x) / 2f, (last[1] + y) / 2f);
        }
        swipePts.add(new float[]{x, y});
        invalidate();
    }

    private void endSwipe() {
        swiping = false;
        swipePointer = -1;
        int n = swipePts.size();
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = swipePts.get(i)[0] / keyW;
            ys[i] = (swipePts.get(i)[1] - lettersTop) / rowLetter;
        }
        removeCallbacks(fadeTrail);
        postDelayed(fadeTrail, 60);
        if (listener != null && n >= 2) listener.onSwipe(xs, ys, n);
        invalidate();
    }

    private void buzz() {
        if (hapticsOn) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    private void click(int kind) {
        if (!soundOn || audio == null) return;
        int fx = kind == DEL ? AudioManager.FX_KEYPRESS_DELETE
                : kind == SPACE ? AudioManager.FX_KEYPRESS_SPACEBAR
                : kind == ENTER ? AudioManager.FX_KEYPRESS_RETURN
                : AudioManager.FX_KEYPRESS_STANDARD;
        try {
            audio.playSoundEffect(fx, -1);
        } catch (RuntimeException ignored) {
            // no sound, no problem
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(deleteRepeat);
        removeCallbacks(longPress);
        removeCallbacks(fadeTrail);
        removeCallbacks(hidePreview);
        super.onDetachedFromWindow();
    }
}

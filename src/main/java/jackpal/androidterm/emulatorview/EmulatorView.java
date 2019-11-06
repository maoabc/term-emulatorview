/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jackpal.androidterm.emulatorview;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.text.util.Linkify.MatchFilter;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ActionMode;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.PopupWindow;
import android.widget.Scroller;
import android.widget.Toast;

import java.io.IOException;
import java.util.Arrays;
import java.util.Hashtable;

import jackpal.androidterm.emulatorview.compat.KeycodeConstants;
import jackpal.androidterm.emulatorview.compat.Patterns;
import mao.emulatorview.BuildConfig;
import mao.emulatorview.R;

/**
 * A view on a {@link TermSession}.  Displays the terminal emulator's screen,
 * provides access to its scrollback buffer, and passes input through to the
 * terminal emulator.
 * <p>
 */
public class EmulatorView extends View implements GestureDetector.OnGestureListener {
    private final static String TAG = "EmulatorView";
    private final static boolean LOG_KEY_EVENTS = false;
    private final static boolean LOG_IME = false;
    public static final boolean DEBUG = BuildConfig.DEBUG;

    public static final int MAX_PARCELABLE = 99 * 1024;

    private static final int ID_COPY = android.R.id.copy;
    private static final int ID_PASTE = android.R.id.paste;
    private static final int ID_SWITCH_INPUT_METHOD = android.R.id.switchInputMethod;


    private static final int MENU_ITEM_ORDER_PASTE = 0;
    private static final int MENU_ITEM_ORDER_COPY = 1;

    /**
     * We defer some initialization until we have been layed out in the view
     * hierarchy. The boolean tracks when we know what our size is.
     */
    private boolean mKnownSize;

    // Set if initialization was deferred because a TermSession wasn't attached
    private boolean mDeferInit = false;

    private int mVisibleWidth;
    private int mVisibleHeight;

    private TermSession mTermSession;

    /**
     * Top-of-screen margin
     */
    private int mTopOfScreenMargin;

    private int mLeftOfScreenMargin;

    private int mRightOfScreenMargin;
    /**
     * Used to render text
     */
    private final PaintRenderer mTextRenderer;

    /**
     * Text size. Zero means 4 x 8 font.
     */
    private int mTextSize = 10;

    private int mCursorBlink;

    /**
     * Color scheme (default foreground/background colors).
     */
    private ColorScheme mColorScheme = PaintRenderer.defaultColorScheme;

    private final Paint mForegroundPaint = new Paint();

    private final Paint mBackgroundPaint = new Paint();

    private boolean mUseCookedIme;

    /**
     * Our terminal emulator.
     */
    private TerminalEmulator mEmulator;

    /**
     * The number of rows of text to display.
     */
    private int mRows;

    /**
     * The number of columns of text to display.
     */
    private int mColumns;

    /**
     * The number of columns that are visible on the display.
     */

    private int mVisibleColumns;

    /*
     * The number of rows that are visible on the view
     */
    private int mVisibleRows;

    /**
     * The top row of text to display. Ranges from -activeTranscriptRows to 0
     */
    private int mTopRow;

    private int mLeftColumn;

    private static final int CURSOR_BLINK_PERIOD = 1000;

    private boolean mCursorVisible = true;


    private boolean mBackKeySendsCharacter = false;
    private int mControlKeyCode;
    private int mFnKeyCode;
    private boolean mIsControlKeySent = false;
    private boolean mIsFnKeySent = false;

    private boolean mMouseTracking;

    private float mDensity;

    private static final int SELECT_TEXT_OFFSET_Y = -40;
    private int mSelX1 = -1;
    private int mSelY1 = -1;
    private int mSelX2 = -1;
    private int mSelY2 = -1;

    Drawable mSelectHandleLeft;
    Drawable mSelectHandleRight;
    final int[] mTempCoords = new int[2];
    Rect mTempRect;
    private SelectionModifierCursorController mSelectionModifierCursorController;
    private boolean mIsInTextSelectionMode = false;
    ActionMode mTextActionMode;
    private ActionMode.Callback mCustomSelectionActionModeCallback;

    /**
     * Routing alt and meta keyCodes away from the IME allows Alt key processing to work on
     * the Asus Transformer TF101.
     * It doesn't seem to harm anything else, but it also doesn't seem to be
     * required on other platforms.
     * <p>
     * This test should be refined as we learn more.
     */
    private final static boolean sTrapAltAndMeta = Build.MODEL.contains("Transformer TF101");

    private Runnable mBlinkCursor = new Runnable() {
        public void run() {
            if (mCursorBlink != 0) {
                mCursorVisible = !mCursorVisible;
                mHandler.postDelayed(this, CURSOR_BLINK_PERIOD);
            } else {
                mCursorVisible = true;
            }
            // Perhaps just invalidate the character with the cursor.
            invalidate();
        }
    };

    private final GestureDetector mGestureDetector;
    private GestureDetector.OnGestureListener mExtGestureListener;
    private Scroller mScroller;
    private Runnable mFlingRunner = new Runnable() {
        public void run() {
            if (mScroller.isFinished()) {
                return;
            }
            // Check whether mouse tracking was turned on during fling.
            if (isMouseTrackingActive()) {
                return;
            }

            boolean more = mScroller.computeScrollOffset();
            int newTopRow = mScroller.getCurrY();
            if (newTopRow != mTopRow) {
                mTopRow = newTopRow;
                if (!awakenScrollBars()) invalidate();
            }

            if (more) {
                post(this);
            }

        }
    };

    /**
     * A hash table of underlying URLs to implement clickable links.
     */
    private Hashtable<Integer, URLSpan[]> mLinkLayer = new Hashtable<Integer, URLSpan[]>();

    /**
     * Accept links that start with http[s]:
     */
    private static class HttpMatchFilter implements MatchFilter {
        public boolean acceptMatch(CharSequence s, int start, int end) {
            return startsWith(s, start, end, "http:") ||
                    startsWith(s, start, end, "https:");
        }

        private boolean startsWith(CharSequence s, int start, int end,
                                   String prefix) {
            int prefixLen = prefix.length();
            int fragmentLen = end - start;
            if (prefixLen > fragmentLen) {
                return false;
            }
            for (int i = 0; i < prefixLen; i++) {
                if (s.charAt(start + i) != prefix.charAt(i)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static MatchFilter sHttpMatchFilter = new HttpMatchFilter();

    /**
     * Convert any URLs in the current row into a URLSpan,
     * and store that result in a hash table of URLSpan entries.
     *
     * @param row The number of the row to check for links
     * @return The number of lines in a multi-line-wrap set of links
     */
    private int createLinks(int row) {
        int lineCount = 1;

        TranscriptScreen transcriptScreen = mEmulator.getScreen();
        if (transcriptScreen == null) {
            return lineCount;
        }
        char[] line = transcriptScreen.getScriptLine(row);
        //Nothing to do if there's no text.
        if (line == null)
            return lineCount;

        /* If this is not a basic line, the array returned from getScriptLine()
         * could have arbitrary garbage at the end -- find the point at which
         * the line ends and only include that in the text to linkify.
         *
         * XXX: The fact that the array returned from getScriptLine() on a
         * basic line contains no garbage is an implementation detail -- the
         * documented behavior explicitly allows garbage at the end! */
        int lineLen;
        boolean textIsBasic = transcriptScreen.isBasicLine(row);
        if (textIsBasic) {
            lineLen = line.length;
        } else {
            // The end of the valid data is marked by a NUL character
            for (lineLen = 0; line[lineLen] != 0; ++lineLen) ;
        }

        SpannableStringBuilder textToLinkify = new SpannableStringBuilder(new String(line, 0, lineLen));

        boolean lineWrap = transcriptScreen.getScriptLineWrap(row);

        //While the current line has a wrap
        while (lineWrap) {
            //Get next line
            int nextRow = row + lineCount;
            line = transcriptScreen.getScriptLine(nextRow);

            //If next line is blank, don't try and append
            if (line == null)
                break;

            boolean lineIsBasic = transcriptScreen.isBasicLine(nextRow);
            if (textIsBasic && !lineIsBasic) {
                textIsBasic = lineIsBasic;
            }
            if (lineIsBasic) {
                lineLen = line.length;
            } else {
                // The end of the valid data is marked by a NUL character
                for (lineLen = 0; line[lineLen] != 0; ++lineLen) ;
            }

            textToLinkify.append(new String(line, 0, lineLen));

            //Check if line after next is wrapped
            lineWrap = transcriptScreen.getScriptLineWrap(nextRow);
            ++lineCount;
        }

        Linkify.addLinks(textToLinkify, Patterns.WEB_URL,
                null, sHttpMatchFilter, null);
        URLSpan[] urls = textToLinkify.getSpans(0, textToLinkify.length(), URLSpan.class);
        if (urls.length > 0) {
            int columns = mColumns;

            //re-index row to 0 if it is negative
            int screenRow = row - mTopRow;

            //Create and initialize set of links
            URLSpan[][] linkRows = new URLSpan[lineCount][];
            for (int i = 0; i < lineCount; ++i) {
                linkRows[i] = new URLSpan[columns];
                Arrays.fill(linkRows[i], null);
            }

            //For each URL:
            for (int urlNum = 0; urlNum < urls.length; ++urlNum) {
                URLSpan url = urls[urlNum];
                int spanStart = textToLinkify.getSpanStart(url);
                int spanEnd = textToLinkify.getSpanEnd(url);

                // Build accurate indices for links
                int startRow;
                int startCol;
                int endRow;
                int endCol;
                if (textIsBasic) {
                    /* endRow/endCol must be the last character of the link,
                     * not one after -- otherwise endRow might be too large */
                    int spanLastPos = spanEnd - 1;
                    // Basic line -- can assume one char per column
                    startRow = spanStart / mColumns;
                    startCol = spanStart % mColumns;
                    endRow = spanLastPos / mColumns;
                    endCol = spanLastPos % mColumns;
                } else {
                    /* Iterate over the line to get starting and ending columns
                     * for this span */
                    startRow = 0;
                    startCol = 0;
                    for (int i = 0; i < spanStart; ++i) {
                        char c = textToLinkify.charAt(i);
                        if (Character.isHighSurrogate(c)) {
                            ++i;
                            startCol += WcWidth.wcwidth(c, textToLinkify.charAt(i));
                        } else {
                            startCol += WcWidth.wcwidth(c);
                        }
                        if (startCol >= columns) {
                            ++startRow;
                            startCol %= columns;
                        }
                    }

                    endRow = startRow;
                    endCol = startCol;
                    for (int i = spanStart; i < spanEnd; ++i) {
                        char c = textToLinkify.charAt(i);
                        if (Character.isHighSurrogate(c)) {
                            ++i;
                            endCol += WcWidth.wcwidth(c, textToLinkify.charAt(i));
                        } else {
                            endCol += WcWidth.wcwidth(c);
                        }
                        if (endCol >= columns) {
                            ++endRow;
                            endCol %= columns;
                        }
                    }
                }

                //Fill linkRows with the URL where appropriate
                for (int i = startRow; i <= endRow; ++i) {
                    int runStart = (i == startRow) ? startCol : 0;
                    int runEnd = (i == endRow) ? endCol : mColumns - 1;

                    Arrays.fill(linkRows[i], runStart, runEnd + 1, url);
                }
            }

            //Add links into the link layer for later retrieval
            for (int i = 0; i < lineCount; ++i)
                mLinkLayer.put(screenRow + i, linkRows[i]);
        }
        return lineCount;
    }

    /**
     * Sends mouse wheel codes to terminal in response to fling.
     */
    private class MouseTrackingFlingRunner implements Runnable {
        private Scroller mScroller;
        private int mLastY;
        private MotionEvent mMotionEvent;

        public void fling(MotionEvent e, float velocityX, float velocityY) {
            float SCALE = 0.15f;
            mScroller.fling(0, 0,
                    -(int) (velocityX * SCALE), -(int) (velocityY * SCALE),
                    0, 0, -100, 100);
            mLastY = 0;
            mMotionEvent = e;
            post(this);
        }

        public void run() {
            if (mScroller.isFinished()) {
                return;
            }
            // Check whether mouse tracking was turned off during fling.
            if (!isMouseTrackingActive()) {
                return;
            }

            boolean more = mScroller.computeScrollOffset();
            int newY = mScroller.getCurrY();
            for (; mLastY < newY; mLastY++) {
                sendMouseEventCode(mMotionEvent, 65);
            }
            for (; mLastY > newY; mLastY--) {
                sendMouseEventCode(mMotionEvent, 64);
            }

            if (more) {
                post(this);
            }
        }
    }

    ;
    private MouseTrackingFlingRunner mMouseTrackingFlingRunner = new MouseTrackingFlingRunner();

    private float mScrollRemainder;
    private final TermKeyListener mKeyListener;

    private String mImeBuffer = "";

    /**
     * Our message handler class. Implements a periodic callback.
     */
    private final Handler mHandler = new Handler();

    /**
     * Called by the TermSession when the contents of the view need updating
     */
    private UpdateCallback mUpdateNotify = new UpdateCallback() {
        public void onUpdate() {
            mEmulator.clearScrollCounter();
            ensureCursorVisible();
            invalidate();
        }
    };

    /**
     * Constructor called when inflating this view from XML.
     * <p>
     * You should call {@link #attachSession attachSession} and {@link
     * #setDensity setDensity} before using an <code>EmulatorView</code> created
     * using this constructor.
     */
    public EmulatorView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /**
     * Constructor called when inflating this view from XML with a
     * default style set.
     * <p>
     * You should call {@link #attachSession attachSession} and {@link
     * #setDensity setDensity} before using an <code>EmulatorView</code> created
     * using this constructor.
     */
    public EmulatorView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.EmulatorView, defStyle, 0);
        mLeftOfScreenMargin = a.getDimensionPixelSize(R.styleable.EmulatorView_screenMarginLeft, 0);
        mRightOfScreenMargin = a.getDimensionPixelSize(R.styleable.EmulatorView_screenMarginRight, 0);
        a.recycle();

        mScroller = new Scroller(context);
        mMouseTrackingFlingRunner.mScroller = new Scroller(context);
        mKeyListener = new TermKeyListener();
        mGestureDetector = new GestureDetector(getContext(), this);
        mTextRenderer = new PaintRenderer();
    }

    /**
     * Attach a {@link TermSession} to this view.
     *
     * @param session The {@link TermSession} this view will be displaying.
     */
    public void attachSession(TermSession session) {
        if (DEBUG)
            Log.d(TAG, "attachSession: " + session);
        mTopRow = 0;
        mLeftColumn = 0;
        // mGestureDetector.setIsLongpressEnabled(false);
        setVerticalScrollBarEnabled(true);
        setFocusable(true);
        setFocusableInTouchMode(true);

        mTermSession = session;

        mKeyListener.setTermSession(session);
        session.setKeyListener(mKeyListener);
        session.setTerminalClient(new BellCallback(getContext()));

        // Do init now if it was deferred until a TermSession was attached
        if (mDeferInit) {
            mDeferInit = false;
            mKnownSize = true;
            initialize();
        }
    }

    /**
     * Update the screen density for the screen on which the view is displayed.
     *
     * @param metrics The {@link DisplayMetrics} of the screen.
     */
    public void setDensity(DisplayMetrics metrics) {
        if (mDensity == 0) {
            // First time we've known the screen density, so update font size
            mTextSize = (int) (mTextSize * metrics.density);
        }
        mDensity = metrics.density;
    }

    /**
     * Inform the view that it is now visible on screen.
     */
    public void onResume() {
        updateSize(false);
//        if (mCursorBlink != 0) {
//            mHandler.postDelayed(mBlinkCursor, CURSOR_BLINK_PERIOD);
//        }
        mKeyListener.onResume();
    }

    /**
     * Inform the view that it is no longer visible on the screen.
     */
    public void onPause() {
//        if (mCursorBlink != 0) {
//            mHandler.removeCallbacks(mBlinkCursor);
//        }
        mKeyListener.onPause();
    }

    /**
     * Set this <code>EmulatorView</code>'s color scheme.
     *
     * @param scheme The {@link ColorScheme} to use (use null for the default
     *               scheme).
     * @see TermSession#setColorScheme
     * @see ColorScheme
     */
    public void setColorScheme(ColorScheme scheme) {
        if (scheme == null) {
            mColorScheme = PaintRenderer.defaultColorScheme;
        } else {
            mColorScheme = scheme;
        }
        updateText();
    }

    public ColorScheme getColorScheme() {
        return mColorScheme;
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = mUseCookedIme ?
                EditorInfo.TYPE_CLASS_TEXT :
                EditorInfo.TYPE_NULL;
        return new BaseInputConnection(this, true) {
            /**
             * Used to handle composing text requests
             */
            private int mCursor;
            private int mComposingTextStart;
            private int mComposingTextEnd;
            private int mSelectedTextStart;
            private int mSelectedTextEnd;

            private void sendText(CharSequence text) {
                stopTextSelectionMode();
                int n = text.length();
                char c;
                try {
                    for (int i = 0; i < n; i++) {
                        c = text.charAt(i);
                        if (Character.isHighSurrogate(c)) {
                            int codePoint;
                            if (++i < n) {
                                codePoint = Character.toCodePoint(c, text.charAt(i));
                            } else {
                                // Unicode Replacement Glyph, aka white question mark in black diamond.
                                codePoint = '\ufffd';
                            }
                            mapAndSend(codePoint);
                        } else {
                            mapAndSend(c);
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "error writing ", e);
                }
            }

            private void mapAndSend(int c) throws IOException {
                if (LOG_IME) Log.d(TAG, "mapAndSend: codePoint " + Integer.toHexString(c));
                int result = mKeyListener.mapControlChar(c);
                if (result < TermKeyListener.KEYCODE_OFFSET) {
                    mTermSession.write(result);
                } else {
                    mKeyListener.handleKeyCode(result - TermKeyListener.KEYCODE_OFFSET, null, getKeypadApplicationMode());
                }
                clearSpecialKeyStatus();
            }

            public boolean beginBatchEdit() {
                if (LOG_IME) {
                    Log.w(TAG, "beginBatchEdit");
                }
                setImeBuffer("");
                mCursor = 0;
                mComposingTextStart = 0;
                mComposingTextEnd = 0;
                return true;
            }

            public boolean clearMetaKeyStates(int states) {
                if (LOG_IME) {
                    Log.w(TAG, "clearMetaKeyStates " + states);
                }
                return false;
            }

            public boolean commitCompletion(CompletionInfo text) {
                if (LOG_IME) {
                    Log.w(TAG, "commitCompletion " + text);
                }
                return false;
            }

            public boolean endBatchEdit() {
                if (LOG_IME) {
                    Log.w(TAG, "endBatchEdit");
                }
                return true;
            }

            public boolean finishComposingText() {
                if (LOG_IME) {
                    Log.w(TAG, "finishComposingText");
                }
                sendText(mImeBuffer);
                setImeBuffer("");
                mComposingTextStart = 0;
                mComposingTextEnd = 0;
                mCursor = 0;
                return true;
            }

            public int getCursorCapsMode(int reqModes) {
                if (LOG_IME) {
                    Log.w(TAG, "getCursorCapsMode(" + reqModes + ")");
                }
                int mode = 0;
                if ((reqModes & TextUtils.CAP_MODE_CHARACTERS) != 0) {
                    mode |= TextUtils.CAP_MODE_CHARACTERS;
                }
                return mode;
            }

            public ExtractedText getExtractedText(ExtractedTextRequest request,
                                                  int flags) {
                if (LOG_IME) {
                    Log.w(TAG, "getExtractedText" + request + "," + flags);
                }
                return null;
            }

            public CharSequence getTextAfterCursor(int n, int flags) {
                if (LOG_IME) {
                    Log.w(TAG, "getTextAfterCursor(" + n + "," + flags + ")");
                }
                int len = Math.min(n, mImeBuffer.length() - mCursor);
                if (len <= 0 || mCursor < 0 || mCursor >= mImeBuffer.length()) {
                    return "";
                }
                return mImeBuffer.substring(mCursor, mCursor + len);
            }

            public CharSequence getTextBeforeCursor(int n, int flags) {
                if (LOG_IME) {
                    Log.w(TAG, "getTextBeforeCursor(" + n + "," + flags + ")");
                }
                int len = Math.min(n, mCursor);
                if (len <= 0 || mCursor >= mImeBuffer.length()) {
                    return "";
                }
                return mImeBuffer.substring(mCursor - len, mCursor);
            }

            public boolean performContextMenuAction(int arg0) {
                if (LOG_IME) {
                    Log.w(TAG, "performContextMenuAction" + arg0);
                }
                return true;
            }

            public boolean performPrivateCommand(String action, Bundle data) {
                if (LOG_IME) {
                    Log.w(TAG, "performPrivateCommand" + action + "," + data);
                }
                return true;
            }

            public boolean reportFullscreenMode(boolean enabled) {
                if (LOG_IME) {
                    Log.w(TAG, "reportFullscreenMode" + enabled);
                }
                return true;
            }

            public boolean commitCorrection(CorrectionInfo info) {
                if (LOG_IME) {
                    Log.w(TAG, "commitCorrection");
                }
                return true;
            }

            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (LOG_IME) {
                    Log.w(TAG, "commitText(\"" + text + "\", " + newCursorPosition + ")");
                }
                clearComposingText();
                sendText(text);
                setImeBuffer("");
                mCursor = 0;
                return true;
            }

            private void clearComposingText() {
                int len = mImeBuffer.length();
                if (mComposingTextStart > len || mComposingTextEnd > len) {
                    mComposingTextEnd = mComposingTextStart = 0;
                    return;
                }
                setImeBuffer(mImeBuffer.substring(0, mComposingTextStart) +
                        mImeBuffer.substring(mComposingTextEnd));
                if (mCursor < mComposingTextStart) {
                    // do nothing
                } else if (mCursor < mComposingTextEnd) {
                    mCursor = mComposingTextStart;
                } else {
                    mCursor -= mComposingTextEnd - mComposingTextStart;
                }
                mComposingTextEnd = mComposingTextStart = 0;
            }

            public boolean deleteSurroundingText(int leftLength, int rightLength) {
                if (LOG_IME) {
                    Log.w(TAG, "deleteSurroundingText(" + leftLength +
                            "," + rightLength + ")");
                }
                if (leftLength > 0) {
                    for (int i = 0; i < leftLength; i++) {
                        sendKeyEvent(
                                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                    }
                } else if ((leftLength == 0) && (rightLength == 0)) {
                    // Delete key held down / repeating
                    sendKeyEvent(
                            new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                }
                // TODO: handle forward deletes.
                return true;
            }

            public boolean performEditorAction(int actionCode) {
                if (LOG_IME) {
                    Log.w(TAG, "performEditorAction(" + actionCode + ")");
                }
                if (actionCode == EditorInfo.IME_ACTION_UNSPECIFIED) {
                    // The "return" key has been pressed on the IME.
                    sendText("\r");
                }
                return true;
            }

            public boolean sendKeyEvent(KeyEvent event) {
                if (LOG_IME) {
                    Log.w(TAG, "sendKeyEvent(" + event + ")");
                }
                // Some keys are sent here rather than to commitText.
                // In particular, del and the digit keys are sent here.
                // (And I have reports that the HTC Magic also sends Return here.)
                // As a bit of defensive programming, handle every key.
                dispatchKeyEvent(event);
                return true;
            }

            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                if (LOG_IME) {
                    Log.w(TAG, "setComposingText(\"" + text + "\", " + newCursorPosition + ")");
                }
                int len = mImeBuffer.length();
                if (mComposingTextStart > len || mComposingTextEnd > len) {
                    return false;
                }
                setImeBuffer(mImeBuffer.substring(0, mComposingTextStart) +
                        text + mImeBuffer.substring(mComposingTextEnd));
                mComposingTextEnd = mComposingTextStart + text.length();
                mCursor = newCursorPosition > 0 ? mComposingTextEnd + newCursorPosition - 1
                        : mComposingTextStart - newCursorPosition;
                return true;
            }

            public boolean setSelection(int start, int end) {
                if (LOG_IME) {
                    Log.w(TAG, "setSelection" + start + "," + end);
                }
                int length = mImeBuffer.length();
                if (start == end && start > 0 && start < length) {
                    mSelectedTextStart = mSelectedTextEnd = 0;
                    mCursor = start;
                } else if (start < end && start > 0 && end < length) {
                    mSelectedTextStart = start;
                    mSelectedTextEnd = end;
                    mCursor = start;
                }
                return true;
            }

            public boolean setComposingRegion(int start, int end) {
                if (LOG_IME) {
                    Log.w(TAG, "setComposingRegion " + start + "," + end);
                }
                if (start < end && start > 0 && end < mImeBuffer.length()) {
                    clearComposingText();
                    mComposingTextStart = start;
                    mComposingTextEnd = end;
                }
                return true;
            }

            public CharSequence getSelectedText(int flags) {
                if (LOG_IME) {
                    Log.w(TAG, "getSelectedText " + flags);
                }
                int len = mImeBuffer.length();
                if (mSelectedTextEnd >= len || mSelectedTextStart > mSelectedTextEnd) {
                    return "";
                }
                return mImeBuffer.substring(mSelectedTextStart, mSelectedTextEnd + 1);
            }

        };
    }

    private void setImeBuffer(String buffer) {
        //关闭输入法缓存
//        if (!buffer.equals(mImeBuffer)) {
//            invalidate();
//        }
//        mImeBuffer = buffer;
    }

    /**
     * Get the terminal emulator's keypad application mode.
     */
    public boolean getKeypadApplicationMode() {
        return mEmulator.getKeypadApplicationMode();
    }

    /**
     * Set a {@link GestureDetector.OnGestureListener
     * GestureDetector.OnGestureListener} to receive gestures performed on this
     * view.  Can be used to implement additional
     * functionality via touch gestures or override built-in gestures.
     *
     * @param listener The {@link
     *                 GestureDetector.OnGestureListener
     *                 GestureDetector.OnGestureListener} which will receive
     *                 gestures.
     */
    public void setExtGestureListener(GestureDetector.OnGestureListener listener) {
        mExtGestureListener = listener;
    }

    /**
     * Compute the vertical range that the vertical scrollbar represents.
     */
    @Override
    protected int computeVerticalScrollRange() {
        if (mEmulator == null || mEmulator.getScreen() == null) {
            return 0;
        }
        return mEmulator.getScreen().getActiveRows();
    }

    /**
     * Compute the vertical extent of the horizontal scrollbar's thumb within
     * the vertical range. This value is used to compute the length of the thumb
     * within the scrollbar's track.
     */
    @Override
    protected int computeVerticalScrollExtent() {
        return mRows;
    }

    /**
     * Compute the vertical offset of the vertical scrollbar's thumb within the
     * horizontal range. This value is used to compute the position of the thumb
     * within the scrollbar's track.
     */
    @Override
    protected int computeVerticalScrollOffset() {
        if (mEmulator == null || mEmulator.getScreen() == null) {
            return 0;
        }
        return mTopRow + mEmulator.getScreen().getActiveRows() - mRows;
    }

    /**
     * Call this to initialize the view.
     */
    private void initialize() {

        updateText();

        mEmulator = mTermSession.getEmulator();
        mTermSession.setUpdateCallback(mUpdateNotify);

        requestFocus();
    }

    /**
     * Get the {@link TermSession} corresponding to this view.
     *
     * @return The {@link TermSession} object for this view.
     */
    public TermSession getTermSession() {
        return mTermSession;
    }

    /**
     * Get the width of the visible portion of this view.
     *
     * @return The width of the visible portion of this view, in pixels.
     */
    public int getVisibleWidth() {
        return mVisibleWidth;
    }

    /**
     * Get the height of the visible portion of this view.
     *
     * @return The height of the visible portion of this view, in pixels.
     */
    public int getVisibleHeight() {
        return mVisibleHeight;
    }


    /**
     * Page the terminal view (scroll it up or down by <code>delta</code>
     * screenfuls).
     *
     * @param delta The number of screens to scroll. Positive means scroll down,
     *              negative means scroll up.
     */
    public void page(int delta) {
        mTopRow =
                Math.min(0, Math.max(-(mEmulator.getScreen()
                        .getActiveTranscriptRows()), mTopRow + mRows * delta));
        invalidate();
    }

    /**
     * Page the terminal view horizontally.
     *
     * @param deltaColumns the number of columns to scroll. Positive scrolls to
     *                     the right.
     */
    public void pageHorizontal(int deltaColumns) {
        mLeftColumn =
                Math.max(0, Math.min(mLeftColumn + deltaColumns, mColumns
                        - mVisibleColumns));
        invalidate();
    }

    /**
     * Sets the text size, which in turn sets the number of rows and columns.
     *
     * @param fontSize the new font size, in density-independent pixels.
     */
    public void setTextSize(int fontSize) {
        mTextSize = (int) (fontSize * mDensity);
        updateText();
    }

    /**
     * Sets the IME mode ("cooked" or "raw").
     *
     * @param useCookedIME Whether the IME should be used in cooked mode.
     */
    public void setUseCookedIME(boolean useCookedIME) {
        mUseCookedIme = useCookedIME;
    }

    /**
     * Returns true if mouse events are being sent as escape sequences to the terminal.
     */
    public boolean isMouseTrackingActive() {
        return mEmulator.getMouseTrackingMode() != 0 && mMouseTracking;
    }

    /**
     * Send a single mouse event code to the terminal.
     */
    private void sendMouseEventCode(MotionEvent e, int button_code) {
        int x = (int) (e.getX() / mTextRenderer.mCharWidth) + 1;
        int y = (int) ((e.getY() - mTopOfScreenMargin) / mTextRenderer.mCharHeight) + 1;
        // Clip to screen, and clip to the limits of 8-bit data.
        boolean out_of_bounds =
                x < 1 || y < 1 ||
                        x > mColumns || y > mRows ||
                        x > 255 - 32 || y > 255 - 32;
        //Log.d(TAG, "mouse button "+x+","+y+","+button_code+",oob="+out_of_bounds);
        if (button_code < 0 || button_code > 255 - 32) {
            Log.e(TAG, "mouse button_code out of range: " + button_code);
            return;
        }
        if (!out_of_bounds) {
            byte[] data = {
                    '\033', '[', 'M',
                    (byte) (32 + button_code),
                    (byte) (32 + x),
                    (byte) (32 + y)};
            mTermSession.write(data, 0, data.length);
        }
    }

    // Begin GestureDetector.OnGestureListener methods

    public boolean onSingleTapUp(MotionEvent e) {
        stopTextSelectionMode();
        if (mExtGestureListener != null && mExtGestureListener.onSingleTapUp(e)) {
            return true;
        }


        if (isMouseTrackingActive()) {
            sendMouseEventCode(e, 0); // BTN1 press
            sendMouseEventCode(e, 3); // release
        }

        requestFocus();
        return true;
    }

    public void onLongPress(MotionEvent ev) {

        mSelX1 = getCursorX(ev.getX());
        mSelX2 = mSelX1 + 1;
        mSelY1 = mSelY2 = getCursorY(ev.getY(), ev.isFromSource(InputDevice.SOURCE_MOUSE));
        final TranscriptScreen screen = mEmulator.getScreen();
        if (!" ".equals(screen.getSelectedText(mSelX1, mSelY1, mSelX2, mSelY1))) {
            // Selecting something other than whitespace. Expand to word.
            String text;
            while (mSelX1 > 0 && !"".equals((text = screen.getSelectedText(mSelX1 - 1, mSelY1, mSelX1, mSelY1))) && !wordSplit(text)) {
                mSelX1--;
            }
            while (mSelX2 < mColumns - 1 && !"".equals((text = screen.getSelectedText(mSelX2, mSelY1, mSelX2 + 1, mSelY1))) && !wordSplit(text)) {
                mSelX2++;
            }
        }

        startTextSelectionMode();
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    private boolean wordSplit(String s) {
        if (s.length() != 1) {
            return false;
        }
        switch (s.charAt(0)) {
            case '/':
            case '.':
            case ' ':
            case '&':
            case ',':
            case ':':
                return true;
        }
        return false;
    }

    public boolean onScroll(MotionEvent e1, MotionEvent e2,
                            float distanceX, float distanceY) {
        if (mExtGestureListener != null && mExtGestureListener.onScroll(e1, e2, distanceX, distanceY)) {
            return true;
        }

        distanceY += mScrollRemainder;
        int deltaRows = (int) (distanceY / mTextRenderer.mCharHeight);
        mScrollRemainder = distanceY - deltaRows * mTextRenderer.mCharHeight;

        if (isMouseTrackingActive()) {
            // Send mouse wheel events to terminal.
            for (; deltaRows > 0; deltaRows--) {
                sendMouseEventCode(e1, 65);
            }
            for (; deltaRows < 0; deltaRows++) {
                sendMouseEventCode(e1, 64);
            }
            return true;
        }

        mTopRow =
                Math.min(0, Math.max(-(mEmulator.getScreen()
                        .getActiveTranscriptRows()), mTopRow + deltaRows));
        if (!awakenScrollBars()) invalidate();

        return true;
    }


    public boolean onJumpTapDown(MotionEvent e1, MotionEvent e2) {
        // Scroll to bottom
        mTopRow = 0;
        invalidate();
        return true;
    }

    public boolean onJumpTapUp(MotionEvent e1, MotionEvent e2) {
        // Scroll to top
        mTopRow = -mEmulator.getScreen().getActiveTranscriptRows();
        invalidate();
        return true;
    }

    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
                           float velocityY) {
        if (mExtGestureListener != null && mExtGestureListener.onFling(e1, e2, velocityX, velocityY)) {
            return true;
        }

        mScrollRemainder = 0.0f;
        if (isMouseTrackingActive()) {
            mMouseTrackingFlingRunner.fling(e1, velocityX, velocityY);
        } else {
            float SCALE = 0.25f;
            mScroller.fling(0, mTopRow,
                    -(int) (velocityX * SCALE), -(int) (velocityY * SCALE),
                    0, 0,
                    -mEmulator.getScreen().getActiveTranscriptRows(), 0);
            // onScroll(e1, e2, 0.1f * velocityX, -0.1f * velocityY);
            post(mFlingRunner);
        }
        return true;
    }

    public void onShowPress(MotionEvent e) {
        if (mExtGestureListener != null) {
            mExtGestureListener.onShowPress(e);
        }
    }

    public boolean onDown(MotionEvent e) {
        if (mExtGestureListener != null && mExtGestureListener.onDown(e)) {
            return true;
        }
        mScrollRemainder = 0.0f;
        return true;
    }

    // End GestureDetector.OnGestureListener methods

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        updateFloatingToolbarVisibility(ev);
        return mGestureDetector.onTouchEvent(ev);
    }

    private int getCursorX(float x) {
        return (int) Math.ceil((x - mLeftOfScreenMargin) / mTextRenderer.mCharWidth);
    }

    private int getCursorY(float y, boolean isMouse) {
        return (int) Math.ceil(((y + (isMouse ? 0 : SELECT_TEXT_OFFSET_Y)) / mTextRenderer.mCharHeight) + mTopRow);
    }

    private int getPointX(int cx) {
        if (cx > mColumns) {
            cx = mColumns;
        }
        return Math.round(cx * mTextRenderer.mCharWidth) + mLeftOfScreenMargin;
    }

    private int getPointY(int cy) {
        return Math.round((cy - mTopRow) * mTextRenderer.mCharHeight);
    }


    /**
     * Called when a key is pressed in the view.
     *
     * @param keyCode The keycode of the key which was pressed.
     * @param event   A {@link KeyEvent} describing the event.
     * @return Whether the event was handled.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (LOG_KEY_EVENTS) {
            Log.w(TAG, "onKeyDown " + keyCode);
        }


        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (mIsInTextSelectionMode) {
                stopTextSelectionMode();
                return true;
            }
        }

        stopTextSelectionMode();

        if (handleControlKey(keyCode, true)) {
            return true;
        } else if (handleFnKey(keyCode, true)) {
            return true;
        } else if (isSystemKey(keyCode, event)) {
            if (!isInterceptedSystemKey(keyCode)) {
                // Don't intercept the system keys
                return super.onKeyDown(keyCode, event);
            }
        }

        // Translate the keyCode into an ASCII character.

        try {
            int oldCombiningAccent = mKeyListener.getCombiningAccent();
            int oldCursorMode = mKeyListener.getCursorMode();
            mKeyListener.keyDown(keyCode, event, getKeypadApplicationMode(),
                    TermKeyListener.isEventFromToggleDevice(event));
            if (mKeyListener.getCombiningAccent() != oldCombiningAccent
                    || mKeyListener.getCursorMode() != oldCursorMode) {
                invalidate();
            }
        } catch (IOException e) {
            // Ignore I/O exceptions
        }
        return true;
    }

    /**
     * Do we want to intercept this system key?
     */
    private boolean isInterceptedSystemKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BACK && mBackKeySendsCharacter;
    }

    /**
     * Called when a key is released in the view.
     *
     * @param keyCode The keycode of the key which was released.
     * @param event   A {@link KeyEvent} describing the event.
     * @return Whether the event was handled.
     */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (LOG_KEY_EVENTS) {
            Log.w(TAG, "onKeyUp " + keyCode);
        }
        if (handleControlKey(keyCode, false)) {
            return true;
        } else if (handleFnKey(keyCode, false)) {
            return true;
        } else if (isSystemKey(keyCode, event)) {
            // Don't intercept the system keys
            if (!isInterceptedSystemKey(keyCode)) {
                return super.onKeyUp(keyCode, event);
            }
        }

        mKeyListener.keyUp(keyCode, event);
        clearSpecialKeyStatus();
        return true;
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (sTrapAltAndMeta) {
            boolean altEsc = mKeyListener.getAltSendsEsc();
            boolean altOn = (event.getMetaState() & KeyEvent.META_ALT_ON) != 0;
            boolean metaOn = (event.getMetaState() & KeyEvent.META_META_ON) != 0;
            boolean altPressed = (keyCode == KeyEvent.KEYCODE_ALT_LEFT)
                    || (keyCode == KeyEvent.KEYCODE_ALT_RIGHT);
            boolean altActive = mKeyListener.isAltActive();
            if (altEsc && (altOn || altPressed || altActive || metaOn)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    return onKeyDown(keyCode, event);
                } else {
                    return onKeyUp(keyCode, event);
                }
            }
        }

        if (handleHardwareControlKey(keyCode, event)) {
            return true;
        }

        if (mKeyListener.isCtrlActive()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                return onKeyDown(keyCode, event);
            } else {
                return onKeyUp(keyCode, event);
            }
        }

        return super.onKeyPreIme(keyCode, event);
    }


    private boolean handleControlKey(int keyCode, boolean down) {
        if (keyCode == mControlKeyCode) {
            if (LOG_KEY_EVENTS) {
                Log.w(TAG, "handleControlKey " + keyCode);
            }
            mKeyListener.handleControlKey(down);
            invalidate();
            return true;
        }
        return false;
    }

    public boolean isCtrlActive() {
        return mKeyListener.isCtrlActive();
    }

    public void resetCtrlKey() {
        mKeyListener.resetCtrl();
        invalidate();
    }


    public void sendCtrlKey() {
        mKeyListener.handleControlKey(true);
        mKeyListener.handleControlKey(false);
        invalidate();
    }

    private boolean handleHardwareControlKey(int keyCode, KeyEvent event) {
        if (keyCode == KeycodeConstants.KEYCODE_CTRL_LEFT ||
                keyCode == KeycodeConstants.KEYCODE_CTRL_RIGHT) {
            if (LOG_KEY_EVENTS) {
                Log.w(TAG, "handleHardwareControlKey " + keyCode);
            }
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            if (mKeyListener != null) mKeyListener.handleHardwareControlKey(down);
            invalidate();
            return true;
        }
        return false;
    }

    private boolean handleFnKey(int keyCode, boolean down) {
        if (keyCode == mFnKeyCode) {
            if (LOG_KEY_EVENTS) {
                Log.w(TAG, "handleFnKey " + keyCode);
            }
            mKeyListener.handleFnKey(down);
            invalidate();
            return true;
        }
        return false;
    }

    private boolean isSystemKey(int keyCode, KeyEvent event) {
        return event.isSystem();
    }

    private OnClearKeyStatusListener mKeyStatusListener;

    private void clearSpecialKeyStatus() {
        if (mIsControlKeySent) {
            mIsControlKeySent = false;
            mKeyListener.handleControlKey(false);
            invalidate();
        }
        if (mIsFnKeySent) {
            mIsFnKeySent = false;
            mKeyListener.handleFnKey(false);
            invalidate();
        }
        if (mKeyStatusListener != null) mKeyStatusListener.onKeyStatusClear();
    }

    public void setKeyStatusListener(OnClearKeyStatusListener clearKeyStatusListener) {
        this.mKeyStatusListener = clearKeyStatusListener;
    }

    private void updateText() {
        ColorScheme scheme = mColorScheme;
        mTextRenderer.updateSize(mTextSize, scheme);

        mForegroundPaint.setColor(scheme.getForeColor());
        mBackgroundPaint.setColor(scheme.getBackColor());

        updateSize(true);

//        ViewParent parent = getParent();
//        if (parent instanceof View) {
//            ((View) parent).setBackgroundColor(scheme.getBackColor());
//        }
    }

    public void setTypeface(Typeface typeface) {
        mTextRenderer.setTypeface(typeface);
    }

    /**
     * This is called during layout when the size of this view has changed. If
     * you were just added to the view hierarchy, you're called with the old
     * values of 0.
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (mTermSession == null) {
            // Not ready, defer until TermSession is attached
            mDeferInit = true;
            return;
        }

        if (!mKnownSize) {
            mKnownSize = true;
            initialize();
        } else {
            updateSize(false);
        }
    }

    private void updateSize(int w, int h) {
        mColumns = Math.max(1, (int) (((float) w - (mLeftOfScreenMargin + mRightOfScreenMargin)) / mTextRenderer.mCharWidth));
        mVisibleColumns = Math.max(1, (int) (((float) mVisibleWidth) / mTextRenderer.mCharWidth));


        mTopOfScreenMargin = mTextRenderer.getTopMargin();
        mRows = Math.max(1, (h - mTopOfScreenMargin) / mTextRenderer.mCharHeight);
        mVisibleRows = Math.max(1, (mVisibleHeight - mTopOfScreenMargin) / mTextRenderer.mCharHeight);
        mTermSession.updateSize(mColumns, mRows);

        // Reset our paging:
        mTopRow = 0;
        mLeftColumn = 0;

        invalidate();
    }

    /**
     * Update the view's idea of its size.
     *
     * @param force Whether a size adjustment should be performed even if the
     *              view's size has not changed.
     */
    public void updateSize(boolean force) {
        //Need to clear saved links on each display refresh
        mLinkLayer.clear();
        if (mKnownSize) {
            int w = getWidth();
            int h = getHeight();
            // Log.w("Term", "(" + w + ", " + h + ")");
            if (force || w != mVisibleWidth || h != mVisibleHeight) {
                mVisibleWidth = w;
                mVisibleHeight = h;
                updateSize(mVisibleWidth, mVisibleHeight);
            }
        }
    }

    /**
     * Draw the view to the provided {@link Canvas}.
     *
     * @param canvas The {@link Canvas} to draw the view to.
     */
    @Override
    protected void onDraw(Canvas canvas) {
        updateSize(false);

        if (mEmulator == null) {
            // Not ready yet
            return;
        }

        int left = mLeftOfScreenMargin;
        int right = getWidth();
        int h = getHeight();

        boolean reverseVideo = mEmulator.getReverseVideo();
        mTextRenderer.setReverseVideo(reverseVideo);

        Paint backgroundPaint =
                reverseVideo ? mForegroundPaint : mBackgroundPaint;
        canvas.drawRect(0, 0, right, h, backgroundPaint);

        final int characterHeight = mTextRenderer.mCharHeight;

        float x = left - mLeftColumn * mTextRenderer.mCharWidth;
        float y = characterHeight + mTopOfScreenMargin;
        int endLine = mTopRow + mRows;
        int cx = mEmulator.getCursorCol();
        int cy = mEmulator.getCursorRow();
        boolean cursorVisible = mCursorVisible && mEmulator.getShowCursor();
//        String effectiveImeBuffer = mImeBuffer;
//        int combiningAccent = mKeyListener.getCombiningAccent();
//        if (combiningAccent != 0) {
//            effectiveImeBuffer += String.valueOf((char) combiningAccent);
//        }
//        int cursorStyle = mKeyListener.getCursorMode();

//        int linkLinesToSkip = 0; //for multi-line links

        int selY1 = this.mSelY1;
        int selY2 = this.mSelY2;
        for (int i = mTopRow; i < endLine; i++) {
            int cursorX = -1;
            if (i == cy && cursorVisible) {
                cursorX = cx;
            }
            int selx1 = -1;
            int selx2 = -1;
            if (i >= selY1 && i <= selY2) {
                if (i == selY1) {
                    selx1 = mSelX1;
                }
                if (i == selY2) {
                    selx2 = mSelX2 - 1;
                } else {
                    selx2 = mColumns - 1;
                }
            }
            mEmulator.getScreen().drawText(i, canvas, x, y, mTextRenderer, cursorX, selx1, selx2, null, 0);
            y += characterHeight;
            //if no lines to skip, create links for the line being drawn
//            if (linkLinesToSkip == 0)
//                linkLinesToSkip = createLinks(i);

            //createLinks always returns at least 1
//            --linkLinesToSkip;
        }

        if (mSelectionModifierCursorController != null &&
                mSelectionModifierCursorController.isActive()) {
            mSelectionModifierCursorController.updatePosition();
        }
    }

    private void ensureCursorVisible() {
        mTopRow = 0;
        if (mVisibleColumns > 0) {
            int cx = mEmulator.getCursorCol();
            int visibleCursorX = mEmulator.getCursorCol() - mLeftColumn;
            if (visibleCursorX < 0) {
                mLeftColumn = cx;
            } else if (visibleCursorX >= mVisibleColumns) {
                mLeftColumn = (cx - mVisibleColumns) + 1;
            }
        }
    }


    /**
     * Get selected text.
     *
     * @return A {@link String} with the selected text.
     */
    public String getSelectedText() {
        return mEmulator.getSelectedText(mSelX1, mSelY1, mSelX2, mSelY2);
    }

    /**
     * Send a Ctrl key event to the terminal.
     */
    public void sendControlKey() {
        mIsControlKeySent = true;
        mKeyListener.handleControlKey(true);
        invalidate();
    }

    /**
     * Send an Fn key event to the terminal.  The Fn modifier key can be used to
     * generate various special characters and escape codes.
     */
    public void sendFnKey() {
        mIsFnKeySent = true;
        mKeyListener.handleFnKey(true);
        invalidate();
    }

    /**
     * Set the key code to be sent when the Back key is pressed.
     */
    public void setBackKeyCharacter(int keyCode) {
        mKeyListener.setBackKeyCharacter(keyCode);
        mBackKeySendsCharacter = (keyCode != 0);
    }

    /**
     * Set whether to prepend the ESC keycode to the character when when pressing
     * the ALT Key.
     *
     * @param flag
     */
    public void setAltSendsEsc(boolean flag) {
        mKeyListener.setAltSendsEsc(flag);
    }

    /**
     * Set the keycode corresponding to the Ctrl key.
     */
    public void setControlKeyCode(int keyCode) {
        mControlKeyCode = keyCode;
    }

    /**
     * Set the keycode corresponding to the Fn key.
     */
    public void setFnKeyCode(int keyCode) {
        mFnKeyCode = keyCode;
    }

    public void setTermType(String termType) {
        mKeyListener.setTermType(termType);
    }

    /**
     * Set whether mouse events should be sent to the terminal as escape codes.
     */
    public void setMouseTracking(boolean flag) {
        mMouseTracking = flag;
    }


    /**
     * Get the URL for the link displayed at the specified screen coordinates.
     *
     * @param x The x coordinate being queried (from 0 to screen width)
     * @param y The y coordinate being queried (from 0 to screen height)
     * @return The URL for the link at the specified screen coordinates, or
     * null if no link exists there.
     */
    public String getURLat(float x, float y) {
        float w = getWidth();
        float h = getHeight();

        //Check for division by zero
        //If width or height is zero, there are probably no links around, so return null.
        if (w == 0 || h == 0)
            return null;

        //Get fraction of total screen
        float x_pos = x / w;
        float y_pos = y / h;

        //Convert to integer row/column index
        int row = (int) Math.floor(y_pos * mRows);
        int col = (int) Math.floor(x_pos * mColumns);

        //Grab row from link layer
        URLSpan[] linkRow = mLinkLayer.get(row);
        URLSpan link;

        //If row exists, and link exists at column, return it
        if (linkRow != null && (link = linkRow[col]) != null)
            return link.getURL();
        else
            return null;
    }

    /**
     * A CursorController instance can be used to control a cursor in the text.
     * It is not used outside of {@link EmulatorView}.
     */
    private interface CursorController extends ViewTreeObserver.OnTouchModeChangeListener {
        /**
         * Makes the cursor controller visible on screen. Will be drawn by {@link #draw(Canvas)}.
         * See also {@link #hide()}.
         */
        void show();

        /**
         * Hide the cursor controller from screen.
         * See also {@link #show()}.
         */
        void hide();

        /**
         * @return true if the CursorController is currently visible
         */
        boolean isActive();

        /**
         * Update the controller's position.
         */
        void updatePosition(HandleView handle, int x, int y);

        void updatePosition();

        /**
         * This method is called by {@link #onTouchEvent(MotionEvent)} and gives the controller
         * a chance to become active and/or visible.
         *
         * @param event The touch event
         */
        boolean onTouchEvent(MotionEvent event);

        /**
         * Called when the view is detached from window. Perform house keeping task, such as
         * stopping Runnable thread that would otherwise keep a reference on the context, thus
         * preventing the activity to be recycled.
         */
        void onDetached();
    }

    private class HandleView extends View {
        private Drawable mDrawable;
        private PopupWindow mContainer;
        private int mPointX;
        private int mPointY;
        private CursorController mController;
        private boolean mIsDragging;
        private float mTouchToWindowOffsetX;
        private float mTouchToWindowOffsetY;
        private int mHotspotX;
        private int mHotspotY;
        private float mTouchOffsetY;
        private int mLastParentX;
        private int mLastParentY;

        private final int mOrigOrient;
        private int mOrientation;


        private int mHandleWidth;
        private int mHandleHeight;

//        private long mLastTime;


        public static final int LEFT = 0;
        public static final int RIGHT = 2;

        public HandleView(CursorController controller, int orientation) {
            super(EmulatorView.this.getContext());
            mController = controller;
            mContainer = new PopupWindow(EmulatorView.this.getContext(), null,
                    android.R.attr.textSelectHandleWindowStyle);
            mContainer.setSplitTouchEnabled(true);
            mContainer.setClippingEnabled(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mContainer.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL);
            }
            mContainer.setWidth(ViewGroup.LayoutParams.WRAP_CONTENT);
            mContainer.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);

            this.mOrigOrient = orientation;
            setOrientation(orientation);
        }

        public void setOrientation(int orientation) {
            mOrientation = orientation;
            int handleWidth = 0;
            switch (orientation) {
                case LEFT: {
                    if (mSelectHandleLeft == null) {

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            mSelectHandleLeft = getContext().getDrawable(
                                    R.drawable.text_select_handle_left_material);
                        } else {
                            mSelectHandleLeft = getContext().getResources().getDrawable(
                                    R.drawable.text_select_handle_left_material);

                        }
                    }
                    //
                    mDrawable = mSelectHandleLeft;
                    handleWidth = mDrawable.getIntrinsicWidth();
                    mHotspotX = (handleWidth * 3) / 4;
                    break;
                }

                case RIGHT: {
                    if (mSelectHandleRight == null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            mSelectHandleRight = getContext().getDrawable(
                                    R.drawable.text_select_handle_right_material);
                        } else {
                            mSelectHandleRight = getContext().getResources().getDrawable(
                                    R.drawable.text_select_handle_right_material);
                        }
                    }
                    mDrawable = mSelectHandleRight;
                    handleWidth = mDrawable.getIntrinsicWidth();
                    mHotspotX = handleWidth / 4;
                    break;
                }

            }

            mHandleHeight = mDrawable.getIntrinsicHeight();

            mHandleWidth = handleWidth;
            mTouchOffsetY = -mHandleHeight * 0.3f;
            mHotspotY = 0;
            invalidate();
        }

        public void changeOrientation(int orientation) {
            if (mOrientation != orientation) {
                setOrientation(orientation);
            }
        }

        @Override
        public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(mDrawable.getIntrinsicWidth(),
                    mDrawable.getIntrinsicHeight());
        }

        public void show() {
            if (!isPositionVisible()) {
                hide();
                return;
            }
//            checkChangedOrientation();
            mContainer.setContentView(this);
            final int[] coords = mTempCoords;
            EmulatorView.this.getLocationInWindow(coords);
            coords[0] += mPointX;
            coords[1] += mPointY;
            mContainer.showAtLocation(EmulatorView.this, 0, coords[0], coords[1]);

        }

        public void hide() {
            mIsDragging = false;
            mContainer.dismiss();
        }

        public boolean isShowing() {
            return mContainer.isShowing();
        }

        void checkChangedOrientation() {
            //防止handleView抖动
//            long millis = SystemClock.currentThreadTimeMillis();
//            if (millis - mLastTime < 50) {
//                return;
//            }
//            mLastTime = millis;

            final EmulatorView hostView = EmulatorView.this;
            final int left = hostView.getLeft() + hostView.getPaddingLeft();
            ;
            final int right = hostView.getRight() - hostView.getPaddingRight();


            final int[] coords = mTempCoords;
            hostView.getLocationInWindow(coords);
            final int posX = coords[0] + mPointX;

            if (posX < left) {
                changeOrientation(RIGHT);
            } else if (posX + mHandleWidth > right) {
                changeOrientation(LEFT);
            } else {
                changeOrientation(mOrigOrient);
            }
        }

        private boolean isPositionVisible() {
            // Always show a dragging handle.
            if (mIsDragging) {
                return true;
            }

            final EmulatorView hostView = EmulatorView.this;
            final int left = 0;
            final int right = hostView.getRight();
            final int top = 0;
            final int bottom = hostView.getBottom();

            if (mTempRect == null) {
                mTempRect = new Rect();
            }
            final Rect clip = mTempRect;
            clip.left = left + hostView.getPaddingLeft();
            clip.top = top + hostView.getPaddingTop();
            clip.right = right - hostView.getPaddingRight();
            clip.bottom = bottom - hostView.getPaddingBottom();

            final ViewParent parent = hostView.getParent();
            if (parent == null || !parent.getChildVisibleRect(hostView, clip, null)) {
                return false;
            }

            final int[] coords = mTempCoords;
            hostView.getLocationInWindow(coords);
            final int posX = coords[0] + mPointX + (int) mHotspotX;
            final int posY = coords[1] + mPointY + (int) mHotspotY;

            return posX >= clip.left && posX <= clip.right &&
                    posY >= clip.top && posY <= clip.bottom;
        }

        private void moveTo(int x, int y) {
            mPointX = x;
            mPointY = y;
            if (DEBUG) Log.d(TAG, "moveTo: " + x + "   " + y);
            if (isPositionVisible()) {
                int[] coords = null;
                if (mContainer.isShowing()) {
//                    if (mIsDragging) {
//                        checkChangedOrientation();
//                    }
                    coords = mTempCoords;
                    EmulatorView.this.getLocationInWindow(coords);
                    int x1 = coords[0] + mPointX;
                    int y1 = coords[1] + mPointY;
                    mContainer.update(x1, y1,
                            getWidth(), getHeight());
                } else {
                    show();
                }

                if (mIsDragging) {
                    if (coords == null) {
                        coords = mTempCoords;
                        EmulatorView.this.getLocationInWindow(coords);
                    }
                    if (coords[0] != mLastParentX || coords[1] != mLastParentY) {
                        mTouchToWindowOffsetX += coords[0] - mLastParentX;
                        mTouchToWindowOffsetY += coords[1] - mLastParentY;
                        mLastParentX = coords[0];
                        mLastParentY = coords[1];
                    }
                }
            } else {
                if (isShowing()) {
                    hide();
                }
            }
        }

        @Override
        public void onDraw(Canvas c) {
            mDrawable.setBounds(0, 0, mHandleWidth, mHandleHeight);
            mDrawable.draw(c);

        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            updateFloatingToolbarVisibility(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    final float rawX = ev.getRawX();
                    final float rawY = ev.getRawY();
                    mTouchToWindowOffsetX = rawX - mPointX;
                    mTouchToWindowOffsetY = rawY - mPointY;
                    final int[] coords = mTempCoords;
                    EmulatorView.this.getLocationInWindow(coords);
                    mLastParentX = coords[0];
                    mLastParentY = coords[1];
                    mIsDragging = true;
                    break;
                }

                case MotionEvent.ACTION_MOVE: {
                    final float rawX = ev.getRawX();
                    final float rawY = ev.getRawY();

                    final float newPosX = rawX - mTouchToWindowOffsetX + mHotspotX;
                    final float newPosY = rawY - mTouchToWindowOffsetY + mHotspotY + mTouchOffsetY;

                    mController.updatePosition(this, Math.round(newPosX), Math.round(newPosY));


                    break;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mIsDragging = false;
            }
            return true;
        }


        public boolean isDragging() {
            return mIsDragging;
        }

        void positionAtCursor(final int cx, final int cy) {
            int left = (int) (getPointX(cx) - mHotspotX);
            int bottom = getPointY(cy + 1);
            moveTo(left, bottom);
        }
    }

    private class SelectionModifierCursorController implements CursorController {
        // The cursor controller images
        private HandleView mStartHandle, mEndHandle;
        // Whether selection anchors are active
        private boolean mIsShowing;
        private final int mHandleHeight;

        SelectionModifierCursorController() {
            mStartHandle = new HandleView(this, HandleView.LEFT);
            mEndHandle = new HandleView(this, HandleView.RIGHT);
            mHandleHeight = Math.max(mStartHandle.mHandleHeight, mEndHandle.mHandleHeight);
        }

        public void show() {
            mIsShowing = true;
            updatePosition();
            mStartHandle.show();
            mEndHandle.show();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                final TextActionModeCallback callback = new TextActionModeCallback();
                mTextActionMode = startActionMode(new ActionMode.Callback2() {
                    @Override
                    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                        return callback.onCreateActionMode(mode, menu);
                    }

                    @Override
                    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                        return callback.onPrepareActionMode(mode, menu);
                    }

                    @Override
                    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                        return callback.onActionItemClicked(mode, item);
                    }

                    @Override
                    public void onDestroyActionMode(ActionMode mode) {
                        callback.onDestroyActionMode(mode);
                    }

                    @Override
                    public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
                        int x1 = getPointX(mSelX1);
                        int x2 = getPointX(mSelX2);
                        int y1 = getPointY(mSelY1);
                        int y2 = getPointY(mSelY2 + 1);
                        if (x1 > x2) {
                            int tmp = x1;
                            x1 = x2;
                            x2 = tmp;
                        }
                        outRect.set(x1, y1, x2, y2 + mHandleHeight);
                    }
                }, ActionMode.TYPE_FLOATING);
            } else {
                mTextActionMode = startActionMode(new TextActionModeCallback());
            }
        }

        public void hide() {
            mStartHandle.hide();
            mEndHandle.hide();
            mIsShowing = false;
            stopTextActionMode();

        }

        public boolean isActive() {
            return mIsShowing;
        }

        public void updatePosition(HandleView handle, int x, int y) {
            if (DEBUG)
                Log.d(TAG, "updatePosition: " + x + "  " + y + "   " + mTopRow + "   "
                        + mTextRenderer.mCharHeight + "   " + EmulatorView.this.getHeight());
            final TranscriptScreen screen = mEmulator.getScreen();
            final int scrollRows = screen.getActiveRows() - mRows;
            if (handle == mStartHandle) {
                mSelX1 = getCursorX(x);
                mSelY1 = getCursorY(y, false);
                if (mSelX1 < 0) {
                    mSelX1 = 0;
                }

                if (mSelY1 < -scrollRows) {
                    mSelY1 = -scrollRows;

                } else if (mSelY1 > mRows - 1) {
                    mSelY1 = mRows - 1;

                }


                if (mSelY1 > mSelY2) {
                    mSelY1 = mSelY2;
                }
                if (mSelY1 == mSelY2 && mSelX1 > mSelX2) {
                    mSelX1 = mSelX2;
                }

                if (mSelY1 <= mTopRow) {
                    mTopRow--;
                    if (mTopRow < -scrollRows) {
                        mTopRow = -scrollRows;
                    }
                } else if (mSelY1 >= mTopRow + mRows) {
                    mTopRow++;
                    if (mTopRow > 0) {
                        mTopRow = 0;
                    }
                }


                mSelX1 = getValidCurX(screen, mSelY1, mSelX1);

                if (DEBUG)
                    Log.d(TAG, "updatePosition: left " + mSelX1 + "   " + mSelY1);
            } else {
                mSelX2 = getCursorX(x);
                mSelY2 = getCursorY(y, false);
                if (mSelX2 < 0) {
                    mSelX2 = 0;
                }


                if (mSelY2 < -scrollRows) {
                    mSelY2 = -scrollRows;
                } else if (mSelY2 > mRows - 1) {
                    mSelY2 = mRows - 1;
                }

                if (mSelY1 > mSelY2) {
                    mSelY2 = mSelY1;
                }
                if (mSelY1 == mSelY2 && mSelX1 > mSelX2) {
                    mSelX2 = mSelX1;
                }

                if (mSelY2 <= mTopRow) {//终端滑动
                    mTopRow--;
                    if (mTopRow < -scrollRows) {
                        mTopRow = -scrollRows;
                    }
                } else if (mSelY2 >= mTopRow + mRows) {
                    mTopRow++;
                    if (mTopRow > 0) {
                        mTopRow = 0;
                    }
                }

                mSelX2 = getValidCurX(screen, mSelY2, mSelX2);
            }
            if (DEBUG)
                Log.d(TAG, "updatePosition: selx1= " + mSelX1 + "  selx2 = " + mSelX2);

            invalidate();
        }

        //得到有效的字符间隙
        private int getValidCurX(TranscriptScreen screen, int cy, int cx) {
            char[] line = screen.getScriptLine(cy);
            if (line != null) {
                int col = 0;
                for (int i = 0, len = line.length; i < len; i++) {
                    char ch1 = line[i];
                    if (ch1 == 0) {
                        break;
                    }


                    int wc;
                    if (Character.isHighSurrogate(ch1) && i + 1 < len) {
                        char ch2 = line[++i];
                        wc = WcWidth.wcwidth(ch1, ch2);
                    } else {
                        wc = WcWidth.wcwidth(ch1);
                    }

                    final int cend = col + wc;
                    if (cx > col && cx < cend) {
                        return cend;
                    }
                    col = cend;
                }
            }
            return cx;
        }

        public void updatePosition() {
            if (!isActive()) {
                return;
            }

            mStartHandle.positionAtCursor(mSelX1, mSelY1);

            mEndHandle.positionAtCursor(mSelX2, mSelY2);

            if (mTextActionMode != null) {
                mTextActionMode.invalidate();
            }

        }

        public boolean onTouchEvent(MotionEvent event) {

            return false;
        }


        /**
         * @return true iff this controller is currently used to move the selection start.
         */
        public boolean isSelectionStartDragged() {
            return mStartHandle.isDragging();
        }

        public boolean isSelectionEndDragged() {
            return mEndHandle.isDragging();
        }

        public void onTouchModeChanged(boolean isInTouchMode) {
            if (!isInTouchMode) {
                hide();
            }
        }

        @Override
        public void onDetached() {
        }
    }


    SelectionModifierCursorController getSelectionController() {


        if (mSelectionModifierCursorController == null) {
            mSelectionModifierCursorController = new SelectionModifierCursorController();

            final ViewTreeObserver observer = getViewTreeObserver();
            if (observer != null) {
                observer.addOnTouchModeChangeListener(mSelectionModifierCursorController);
            }
        }

        return mSelectionModifierCursorController;
    }

    private void hideSelectionModifierCursorController() {
        if (mSelectionModifierCursorController != null && mSelectionModifierCursorController.isActive()) {
            mSelectionModifierCursorController.hide();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (mSelectionModifierCursorController != null) {
            getViewTreeObserver().addOnTouchModeChangeListener(mSelectionModifierCursorController);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (mSelectionModifierCursorController != null) {
            getViewTreeObserver().removeOnTouchModeChangeListener(mSelectionModifierCursorController);
            mSelectionModifierCursorController.onDetached();
        }
    }

    private void startTextSelectionMode() {
        if (!requestFocus()) {
            return;
        }

        getSelectionController().show();

        mIsInTextSelectionMode = true;
        invalidate();
    }

    private void stopTextSelectionMode() {
        if (mIsInTextSelectionMode) {
            hideSelectionModifierCursorController();
            mSelX1 = mSelY1 = mSelX2 = mSelY2 = -1;
            mIsInTextSelectionMode = false;
            invalidate();
        }
    }

    protected void stopTextActionMode() {
        if (mTextActionMode != null) {
            // This will hide the mSelectionModifierCursorController
            mTextActionMode.finish();
        }
    }

    private boolean canPaste() {
        return ((ClipboardManager) getContext()
                .getSystemService(Context.CLIPBOARD_SERVICE))
                .hasPrimaryClip();
    }

    private class TextActionModeCallback implements ActionMode.Callback {

        TextActionModeCallback() {
        }

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {

            mode.setTitle(null);
            mode.setSubtitle(null);
            mode.setTitleOptionalHint(true);

            menu.add(Menu.NONE, ID_COPY, MENU_ITEM_ORDER_COPY,
                    R.string.copy)
                    .setAlphabeticShortcut('c')
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

            if (canPaste()) {
                menu.add(Menu.NONE, ID_PASTE, MENU_ITEM_ORDER_PASTE,
                        R.string.paste)
                        .setAlphabeticShortcut('v')
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            }

            ActionMode.Callback customCallback = getCustomCallback();
            if (customCallback != null) {
                if (!customCallback.onCreateActionMode(mode, menu)) {
                    stopTextSelectionMode();
                    return false;
                }
            }

            return true;
        }

        private ActionMode.Callback getCustomCallback() {
            return mCustomSelectionActionModeCallback;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {

            ActionMode.Callback customCallback = getCustomCallback();
            if (customCallback != null) {
                return customCallback.onPrepareActionMode(mode, menu);
            }
            return true;
        }


        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {

            ActionMode.Callback customCallback = getCustomCallback();
            if (customCallback != null && customCallback.onActionItemClicked(mode, item)) {
                return true;
            }

            return onTextContextMenuItem(item.getItemId());
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            mTextActionMode = null;
        }

    }

    /**
     * If provided, this ActionMode.Callback will be used to create the ActionMode when text
     * selection is initiated in this View.
     *
     * <p>The standard implementation populates the menu with a subset of Select All, Cut, Copy,
     * Paste, Replace and Share actions, depending on what this View supports.
     *
     * <p>A custom implementation can add new entries in the default menu in its
     * {@link android.view.ActionMode.Callback#onPrepareActionMode(ActionMode, android.view.Menu)}
     * method. The default actions can also be removed from the menu using
     * {@link android.view.Menu#removeItem(int)} and passing {@link android.R.id#selectAll},
     * {@link android.R.id#cut}, {@link android.R.id#copy}, {@link android.R.id#paste},
     * {@link android.R.id#replaceText} or {@link android.R.id#shareText} ids as parameters.
     *
     * <p>Returning false from
     * {@link android.view.ActionMode.Callback#onCreateActionMode(ActionMode, android.view.Menu)}
     * will prevent the action mode from being started.
     *
     * <p>Action click events should be handled by the custom implementation of
     * {@link android.view.ActionMode.Callback#onActionItemClicked(ActionMode,
     * android.view.MenuItem)}.
     *
     * <p>Note that text selection mode is not started when a TextView receives focus and the
     * {@link android.R.attr#selectAllOnFocus} flag has been set. The content is highlighted in
     * that case, to allow for quick replacement.
     */
    public void setCustomSelectionActionModeCallback(ActionMode.Callback actionModeCallback) {
        mCustomSelectionActionModeCallback = actionModeCallback;
    }

    public boolean onTextContextMenuItem(int id) {

        if (id == ID_COPY) {
            String selectedText = getSelectedText();
            if (selectedText.length() > MAX_PARCELABLE) {
                Toast.makeText(getContext(), R.string.toast_overflow_of_limit, Toast.LENGTH_LONG).show();
            } else {
                ClipData plainText = ClipData.newPlainText("text", selectedText);
                ClipboardManager clipboard =
                        (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                try {
                    clipboard.setPrimaryClip(plainText);
                } catch (Throwable ignored) {
                }
                stopTextSelectionMode();
                Toast.makeText(getContext(), R.string.copied, Toast.LENGTH_LONG).show();
            }
            return true;
        } else if (id == ID_PASTE) {
            ClipboardManager clipboard =
                    (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clipData = clipboard.getPrimaryClip();
            if (clipData != null && clipData.getItemCount() > 0) {
                ClipData.Item item = clipData.getItemAt(0);

                CharSequence text = item.getText();
                mTermSession.write(text.toString());

                stopTextSelectionMode();
            }
            return true;
        } else if (id == ID_SWITCH_INPUT_METHOD) {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showInputMethodPicker();
            }
            return true;
        }

        return false;
    }

    private final Runnable mShowFloatingToolbar = new Runnable() {
        @Override
        public void run() {
            if (mTextActionMode != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    mTextActionMode.hide(0);  // hide off.
                }
            }
        }
    };

    void hideFloatingToolbar(int duration) {
        if (mTextActionMode != null) {
            removeCallbacks(mShowFloatingToolbar);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mTextActionMode.hide(duration);
            }
        }
    }

    private void showFloatingToolbar() {
        if (mTextActionMode != null) {
            int delay = ViewConfiguration.getDoubleTapTimeout();
            postDelayed(mShowFloatingToolbar, delay);
        }
    }

    private void updateFloatingToolbarVisibility(MotionEvent event) {
        if (mTextActionMode != null) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    hideFloatingToolbar(-1);
                    break;
                case MotionEvent.ACTION_UP:  // fall through
                case MotionEvent.ACTION_CANCEL:
                    showFloatingToolbar();
            }
        }
    }

    private static class BellCallback implements TerminalClient {

        private final Vibrator vibrator;

        BellCallback(Context context) {
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }

        @Override
        public void onBell() {
            if (vibrator == null) {
                return;
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                VibrationEffect oneShot = VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE);
                vibrator.vibrate(oneShot);
            } else {
                vibrator.vibrate(60);
            }
        }
    }

    public interface OnClearKeyStatusListener {
        void onKeyStatusClear();
    }
}

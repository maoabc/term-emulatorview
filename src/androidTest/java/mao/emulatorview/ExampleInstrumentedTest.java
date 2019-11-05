package mao.emulatorview;

import android.content.Context;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.UnsupportedEncodingException;

import jackpal.androidterm.emulatorview.WcWidth;

import static org.junit.Assert.assertEquals;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class ExampleInstrumentedTest {
    @Test
    public void useAppContext() {
        // Context of the app under test.
        Context appContext = InstrumentationRegistry.getTargetContext();

        assertEquals("mao.emulatorview.test", appContext.getPackageName());
    }

    @Test
    public void testWcwidth() throws UnsupportedEncodingException {
        int wcwidth = WcWidth.wcwidth(0x1F600);
        System.out.println("widths " + wcwidth);

        byte[] bytes = "中".getBytes("UTF-8");
        System.out.println("widths 3 " + Integer.toHexString(bytes[0])+"  "+Integer.toHexString(bytes[1])+"  "+Integer.toHexString(bytes[2]));

    }
}

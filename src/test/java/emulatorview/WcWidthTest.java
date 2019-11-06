package emulatorview;

import org.junit.Test;

import java.io.UnsupportedEncodingException;

import jackpal.androidterm.emulatorview.WcWidth;

public class WcWidthTest {

    @Test
    public void testWcwidth1() {
        String s = "❤❤";
        char[] chars = s.toCharArray();
        boolean surrogate = Character.isHighSurrogate(chars[0]);
        int wcwidth = WcWidth.wcwidth(chars, 0);


    }

    @Test
    public void testWcwidth2() throws UnsupportedEncodingException {
        int wcwidth = WcWidth.wcwidth(0x1F600);
        System.out.println("widths " + wcwidth);

        byte[] bytes = "中".getBytes("UTF-8");
        System.out.println("widths 3 " + Integer.toHexString(bytes[0]) + "  " + Integer.toHexString(bytes[1]) + "  " + Integer.toHexString(bytes[2]));

    }
}
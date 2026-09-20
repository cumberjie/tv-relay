package com.skyeward.tvrelay;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TinyHttpTest {

    @Test
    public void parsesPutUpload() {
        assertArrayEquals(new String[] { "PUT", "/upload" },
                TinyHttp.parseRequestLine("PUT /upload HTTP/1.1"));
    }

    @Test
    public void parsesGetRoot() {
        assertArrayEquals(new String[] { "GET", "/" },
                TinyHttp.parseRequestLine("GET / HTTP/1.1"));
    }

    @Test
    public void rejectsGarbage() {
        assertNull(TinyHttp.parseRequestLine("bogus"));
    }
}

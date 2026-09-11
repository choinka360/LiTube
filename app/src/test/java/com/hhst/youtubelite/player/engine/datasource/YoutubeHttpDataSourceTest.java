package com.hhst.youtubelite.player.engine.datasource;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSpec;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

public class YoutubeHttpDataSourceTest {
    @Test
    public void resumedResponseDoesNotCountSkippedBytesAgainstRemainingLength() throws Exception {
        assertResumedRead(3, 7, "3456789");
    }

    @Test
    public void resumePastHalfwayDoesNotProduceNegativeReadLength() throws Exception {
        assertResumedRead(7, 3, "789");
    }

    @Test
    public void boundedResumeReturnsEntireRequestedRange() throws Exception {
        assertResumedRead(4, 2, "45");
    }

    @Test
    public void unboundedResumeReadsUntilEndOfStream() throws Exception {
        assertResumedRead(6, C.LENGTH_UNSET, "6789");
    }

    private void assertResumedRead(long position, long length, String expected) throws Exception {
        YoutubeHttpDataSource source = new YoutubeHttpDataSource.Factory("test").createDataSource();
        // Model an HTTP 200 response ignoring Range. open() has already computed
        // bytesToRead for the requested range, then skips the response prefix.
        setField(source, "inputStream", new ByteArrayInputStream("0123456789".getBytes(StandardCharsets.UTF_8)));
        setField(source, "bytesToRead", length);
        Method skip = YoutubeHttpDataSource.class.getDeclaredMethod("skipFully", long.class, DataSpec.class);
        skip.setAccessible(true);
        skip.invoke(source, position, null);

        byte[] buffer = new byte[expected.length()];
        int total = 0;
        while (total < buffer.length) {
            int read = source.read(buffer, total, buffer.length - total);
            if (read == C.RESULT_END_OF_INPUT) break;
            total += read;
        }
        assertEquals(expected.length(), total);
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), buffer);
        assertEquals(C.RESULT_END_OF_INPUT, source.read(new byte[1], 0, 1));
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}

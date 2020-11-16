package com.welie.blessed;

import com.welie.blessed.internal.Handler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
public class HandlerTest {

    @Test
    void When_executing_three_runnables_they_are_executed_in_the_order_they_are_started() throws InterruptedException {
        Handler handler = new Handler("test");
        List<String> output = new ArrayList<>();

        handler.post(() -> output.add("first"));
        handler.post(() -> output.add("second"));
        handler.post(() -> output.add("third"));

        Thread.sleep(100);
        assertEquals("first", output.get(0));
        assertEquals("second", output.get(1));
        assertEquals("third", output.get(2));
    }

    @Test
    void When_executing_three_runnables_some_with_delay_they_are_executed_in_the_right_order() throws InterruptedException {
        Handler handler = new Handler("test");
        List<String> output = new ArrayList<>();

        handler.postDelayed(() -> output.add("first"), 1000);
        handler.post(() -> output.add("second"));
        handler.post(() -> output.add("third"));

        Thread.sleep(1200);

        assertEquals("first", output.get(2));
        assertEquals("second", output.get(0));
        assertEquals("third", output.get(1));
    }
}

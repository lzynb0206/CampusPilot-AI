package com.example.demo.service.wechat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerUserTaskQueueTests {
    @Test
    void keepsSameUserInOrderButAllowsDifferentUsersToRunConcurrently() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            PerUserTaskQueue queue = new PerUserTaskQueue(executor);
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch otherUserCompleted = new CountDownLatch(1);
            List<String> order = new CopyOnWriteArrayList<>();

            var first = queue.submit("user-a", () -> {
                order.add("a1-start");
                firstStarted.countDown();
                await(releaseFirst);
                order.add("a1-end");
            });
            var second = queue.submit("user-a", () -> order.add("a2"));
            var other = queue.submit("user-b", () -> {
                order.add("b1");
                otherUserCompleted.countDown();
            });

            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            assertTrue(otherUserCompleted.await(2, TimeUnit.SECONDS),
                    "另一个用户不应被长任务阻塞");
            assertFalse(second.isDone(), "同一用户的第二条消息必须排队");

            releaseFirst.countDown();
            first.join();
            second.join();
            other.join();

            assertTrue(order.indexOf("a1-start") < order.indexOf("a1-end"));
            assertTrue(order.indexOf("a1-end") < order.indexOf("a2"));
            assertTrue(order.indexOf("b1") < order.indexOf("a2"));
            assertEquals(0, queue.activeUserCount());
        }
    }

    @Test
    void failedTaskDoesNotBlockNextMessageFromSameUser() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            PerUserTaskQueue queue = new PerUserTaskQueue(executor);

            var failed = queue.submit("user-a", () -> {
                throw new IllegalStateException("测试失败");
            });
            var next = queue.submit("user-a", () -> {
            });

            assertThrows(java.util.concurrent.CompletionException.class, failed::join);
            next.join();
            assertEquals(0, queue.activeUserCount());
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("等待测试信号超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}

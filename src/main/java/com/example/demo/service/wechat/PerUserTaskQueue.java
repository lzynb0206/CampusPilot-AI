package com.example.demo.service.wechat;

import com.example.demo.config.ConcurrencyConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Component
public class PerUserTaskQueue {
    private final Executor taskExecutor;
    private final ConcurrentHashMap<String, UserQueue> userQueues = new ConcurrentHashMap<>();

    public PerUserTaskQueue(
            @Qualifier(ConcurrencyConfig.APPLICATION_TASK_EXECUTOR) Executor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    public CompletableFuture<Void> submit(String userId, Runnable task) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("消息队列缺少用户ID");
        }
        if (task == null) {
            throw new IllegalArgumentException("消息队列任务不能为空");
        }

        QueuedTask queuedTask = new QueuedTask(task, new CompletableFuture<>());
        userQueues.compute(userId, (key, existing) -> {
            UserQueue queue = existing == null ? new UserQueue() : existing;
            queue.tasks.add(queuedTask);
            if (!queue.running) {
                queue.running = true;
                schedule(key, queue, queue.tasks.remove());
            }
            return queue;
        });
        return queuedTask.completion;
    }

    int activeUserCount() {
        return userQueues.size();
    }

    private void schedule(String userId, UserQueue queue, QueuedTask queuedTask) {
        taskExecutor.execute(() -> {
            try {
                queuedTask.task.run();
                queuedTask.completion.complete(null);
            } catch (Throwable throwable) {
                queuedTask.completion.completeExceptionally(throwable);
            } finally {
                advance(userId, queue);
            }
        });
    }

    private void advance(String userId, UserQueue expectedQueue) {
        userQueues.computeIfPresent(userId, (key, currentQueue) -> {
            if (currentQueue != expectedQueue) {
                return currentQueue;
            }
            QueuedTask next = currentQueue.tasks.poll();
            if (next == null) {
                currentQueue.running = false;
                return null;
            }
            schedule(key, currentQueue, next);
            return currentQueue;
        });
    }

    private static class UserQueue {
        private final Queue<QueuedTask> tasks = new ArrayDeque<>();
        private boolean running;
    }

    private record QueuedTask(Runnable task, CompletableFuture<Void> completion) {
    }
}

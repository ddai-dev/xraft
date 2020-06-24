package in.xnnyygn.xraft.core.support;

import com.google.common.util.concurrent.FutureCallback;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * Task executor. 是一个抽象化的任务执行器. 可以在实际运行中使用异步单线程实现.
 * 但是在测试时直接执行.
 *  TaskExecutor 通过把核心组件的处理模式分离出来, 方便之后的快速修改, 比如改成多线程
 * 使用方式
 * context.taskExecutor().submit(()->{
 *     // 处理
 * })
 */
public interface TaskExecutor {

    /**
     * Submit task. 提交任务
     *
     * @param task task
     * @return future
     */
    @Nonnull
    Future<?> submit(@Nonnull Runnable task);

    /**
     * Submit callable task. 提交任务, 任务有返回值
     *
     * @param task task
     * @param <V>  result type
     * @return future
     */
    @Nonnull
    <V> Future<V> submit(@Nonnull Callable<V> task);

    /**
     * Submit task with callback.
     *
     * @param task     task
     * @param callback callback
     */
    void submit(@Nonnull Runnable task, @Nonnull FutureCallback<Object> callback);

    /**
     * Submit task with callbacks.
     *
     * @param task task
     * @param callbacks callbacks, should not be empty
     */
    void submit(@Nonnull Runnable task, @Nonnull Collection<FutureCallback<Object>> callbacks);

    /**
     * Shutdown. 关闭执行器
     *
     * @throws InterruptedException if interrupted
     */
    void shutdown() throws InterruptedException;

}

package bes.injector;/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A pool of worker threads that are shared between all Executors created with it. Each executor is treated as a distinct
 * unit, with its own concurrency and task queue limits, but the threads that service the tasks on each executor are
 * free to hop between all other executors at will.
 *
 * To keep producers from incurring unnecessary delays, once an executor is "spun up" (i.e. is processing tasks at a steady
 * rate), adding tasks to the executor often involves only placing the task on the work queue and updating the
 * task permits (which imposes our max queue length constraints). Only when it cannot be guaranteed the task will be serviced
 * promptly does the producer have to signal a thread itself to perform the work.
 *
 * We do this by scheduling only if there are no 'spinning' workers on this Injector, or if the task queue is full
 * (since we have to block in this case anyway)
 *
 * The worker threads schedule themselves as far as possible: when they are assigned a task, they will attempt to spawn
 * a partner worker to service any other work outstanding on the queue (if any); once they have finished the task they
 * will either take another (if any remaining) and repeat this, or they will attempt to assign themselves to another executor
 * that does have tasks remaining. If both fail, it will enter a non-busy-spinning phase, where it will sleep for a short
 * random interval (based upon the number of threads in this mode, so that the total amount of non-sleeping time remains
 * approximately fixed regardless of the number of spinning threads), and upon waking up will again try to assign themselves
 * an executor with outstanding tasks to perform.
 */
public class Injector
{

    // the name assigned to workers in the injector, and the id suffix
    final AtomicLong workerId = new AtomicLong();
    final AtomicInteger workerCount = new AtomicInteger();

    // the collection of executors serviced by this injector
    final List<InjectionExecutor> executors = new CopyOnWriteArrayList<>();

    // the number of workers currently in a spinning state
    final AtomicInteger spinningCount = new AtomicInteger();
    // see Worker.maybeStop() - used to self coordinate stopping of threads
    final AtomicLong stopCheck = new AtomicLong();
    // the collection of threads that are (most likely) in a spinning state - new workers are scheduled from here first
    final ConcurrentSkipListMap<Long, Worker> spinning = new ConcurrentSkipListMap<Long, Worker>();
    // the collection of threads that have been asked to stop/deschedule - new workers are scheduled from here last
    final ConcurrentSkipListMap<Long, Worker> descheduled = new ConcurrentSkipListMap<Long, Worker>();

    final ThreadFactory threadFactory;

    public Injector(final String poolName)
    {
        threadFactory = new ThreadFactory()
        {
            public Thread newThread(Runnable r)
            {
                Thread thread = new Thread(r, poolName + "-Worker-" + ((Worker) r).workerId);
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    public Injector(ThreadFactory threadFactory)
    {
        this.threadFactory = threadFactory;
    }

    void schedule(Work work, boolean internal)
    {
        // we try to hand-off our work to the spinning queue before the descheduled queue, even though we expect it to be empty
        // all we're doing here is hoping to find a worker without work to do, but it doesn't matter too much what we find;
        // we atomically set the task so even if this were a collection of all workers it would be safe, and if they are both
        // empty we schedule a new thread
        Map.Entry<Long, Worker> e;
        while (null != (e = spinning.pollFirstEntry()) || null != (e = descheduled.pollFirstEntry()))
            if (e.getValue().assign(work, false))
                return;

        if (!work.isStop())
        {
            try
            {
                new Worker(workerId.incrementAndGet(), work, this, threadFactory);
                workerCount.incrementAndGet();
            }
            catch (Throwable t)
            {
                // the only safe thing to do is to return our permits and
                // let the running workers pick up the work when they get a chance

                if (work.assigned != null)
                {
                    work.assigned.returnWorkPermit();
                    work.assigned.returnTaskPermit();
                }

                if (!internal)
                    throw t;

                Thread thread = Thread.currentThread();
                Thread.UncaughtExceptionHandler handler = thread.getUncaughtExceptionHandler();
                if (handler != null)
                    handler.uncaughtException(thread, t);
            }
        }
    }

    void maybeStartSpinningWorker(boolean internal)
    {
        // in general the workers manage spinningCount directly; however if it is zero, we increment it atomically
        // ourselves to avoid starting a worker unless we have to
        int current = spinningCount.get();
        if (current == 0 && spinningCount.compareAndSet(0, 1))
            schedule(Work.SPINNING, internal);
    }

    protected <E extends InjectionExecutor> E addExecutor(E executor)
    {
        if (executor.injector != null)
            throw new IllegalArgumentException("An InjectionExecutor can only be associated with one Injector!");
        executor.injector = this;
        executors.add(executor);
        return executor;
    }

    public InjectionExecutor newExecutor(int maxWorkers, int maxTasksQueued)
    {
        InjectionExecutor executor = new InjectionExecutor(maxWorkers, maxTasksQueued);
        addExecutor(executor);
        return executor;
    }
}
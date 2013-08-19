/*
 * Copyright 2013 Rackspace
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.rackspacecloud.blueflood.service;

import com.rackspacecloud.blueflood.tools.jmx.JmxBooleanGauge;
import com.rackspacecloud.blueflood.utils.TimeValue;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Gauge;
import com.yammer.metrics.core.Timer;
import com.yammer.metrics.util.JmxGauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Base class for pushing/pulling shard state. Todo: just bring the two child classes inside this one.
 */
abstract class ShardStateWorker implements Runnable, ShardStateWorkerMBean {
    private static final Logger log = LoggerFactory.getLogger(ShardStateWorker.class);
    
    protected final Collection<Integer> allShards;
    protected final ShardStateManager shardStateManager;
    protected final Timer timer = Metrics.newTimer(getClass(), "Stats", TimeUnit.MILLISECONDS, TimeUnit.MINUTES);
    
    private long lastOp = 0L;
    private boolean active = true;
    private Object activePollBarrier = new Object();

    private long periodMs = 1000L;
    
    private final Counter errors;
    private Gauge activeGauge;
    private Gauge periodGauge;
    ;

    ShardStateWorker(Collection<Integer> allShards, ShardStateManager shardStateManager, TimeValue period) {
        this.shardStateManager = shardStateManager;
        this.allShards = Collections.unmodifiableCollection(allShards);
        this.periodMs = period.toMillis();
        
        try {
            final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            String name = String.format("com.rackspacecloud.blueflood.service:type=%s", getClass().getSimpleName());
            final ObjectName nameObj = new ObjectName(name);
            mbs.registerMBean(this, nameObj);
            activeGauge = Metrics.newGauge(getClass(), "Active",
                    new JmxBooleanGauge(nameObj, "Active"));
            periodGauge = Metrics.newGauge(RollupService.class, "Period",
                    new JmxGauge(nameObj, "Period"));
        } catch (Exception exc) {
            // not critical (as in tests), but we want it logged.
            log.error("Unable to register mbean for " + getClass().getSimpleName());
            log.debug(exc.getMessage(), exc);
        }
        
        errors = Metrics.newCounter(getClass(), "Poll Errors");;
    }    
    
    final public void run() {
        while (true) {
            try {
                if (active) {
                    // push.
                    long now = System.currentTimeMillis();
                    if ((now - lastOp) > periodMs) {
                        performOperation();
                        lastOp = now;
                    } else {
                        try { Thread.currentThread().sleep(100); } catch (Exception ex) {};
                    }
                } else {
                    try {
                        synchronized (activePollBarrier) {
                            activePollBarrier.wait();
                        }
                    } catch (InterruptedException ex) {
                        log.debug("Shard state worker woken up.");
                    }
                }
            } catch (Throwable th) {
                log.error(th.getMessage(), th);
                errors.inc();
            }
        }
    }
    
    abstract void performOperation();
   
    //
    // JMX methods
    //

    public synchronized void force() {
        try {
            performOperation();
        }
        catch (Exception ex) {
            log.error(ex.getMessage(), ex);
        }
    }

    public synchronized void setActive(boolean b) { 
        active = b;
        if (active) {
            synchronized (activePollBarrier) {
                activePollBarrier.notify();
            }
        }
    }
    public synchronized boolean getActive() { return this.active; }

    public synchronized void setPeriod(long period) { this.periodMs = period; }
    public synchronized long getPeriod() { return this.periodMs; }
}

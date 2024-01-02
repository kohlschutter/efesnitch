/*
 * efesnitch
 *
 * Copyright 2024 Christian Kohlschütter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.kohlschutter.efesnitch.core;

import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kohlschutter.annotations.compiletime.SuppressFBWarnings;

final class PathEventBatcher {
  private static final Logger LOG = LoggerFactory.getLogger(PathEventBatcher.class);

  private final PathWatcherImpl modificationsWatcher;
  private static final long TYPICAL_FLUSH_INTERVAL = 1000;
  private static final long MAX_FLUSH_INTERVAL = 10000;
  private final AtomicLong lastFlush = new AtomicLong();
  private final AtomicBoolean newAdds = new AtomicBoolean();
  private final Map<WatchKey, Collection<Path>> keys = new HashMap<>();

  PathEventBatcher(PathWatcherImpl modificationsWatcher) {
    this.modificationsWatcher = modificationsWatcher;
    CompletableFuture.runAsync(this::watch);
  }

  private void watch() {
    lastFlush.set(System.currentTimeMillis());

    while (!Thread.interrupted()) {
      if (this.modificationsWatcher.isClosed()) {
        return;
      }
      Map<WatchKey, Collection<Path>> keys2 = new HashMap<>();
      synchronized (keys) {

        long timeNow = waitUntilWeGotSomethingToDo();

        boolean hasNewAdds = newAdds.compareAndSet(true, false);
        boolean overdue = (timeNow - lastFlush.get()) > MAX_FLUSH_INTERVAL;

        if (!hasNewAdds || overdue) {
          lastFlush.set(timeNow);

          for (Map.Entry<WatchKey, Collection<Path>> en : keys.entrySet()) {
            keys2.computeIfAbsent(en.getKey(), (k) -> new HashSet<>()).addAll(en.getValue());
          }

          keys.clear();
        }
      }
      LOG.debug("New keys: {}", keys2);
      if (!keys2.isEmpty()) {
        this.modificationsWatcher.onEventKeys(keys2);
      }
    }
    if (!this.modificationsWatcher.isClosed()) {
      LOG.warn("Interrupted");
    }
  }

  @SuppressFBWarnings("WA_NOT_IN_LOOP")
  private long waitUntilWeGotSomethingToDo() {
    synchronized (keys) {
      long timeNow = System.currentTimeMillis();
      long waitUntil = timeNow + TYPICAL_FLUSH_INTERVAL;

      if (keys.isEmpty()) {
        try {
          keys.wait();
        } catch (InterruptedException e) {
          LOG.warn("Interrupted", e);
        }
      } else {
        try {
          long interval;
          do {
            interval = waitUntil - timeNow;
            if (interval > 0) {
              keys.wait(interval);
            }
            timeNow = System.currentTimeMillis();
          } while (interval > 0);
        } catch (InterruptedException e) {
          LOG.warn("Interrupted", e);
          timeNow = System.currentTimeMillis();
        }
      }

      return timeNow;
    }
  }

  void wakeup() {
    synchronized (keys) {
      keys.notifyAll();
    }
  }

  void addWatchKey(WatchKey key, Collection<Path> paths) {
    LOG.debug("addWatchKey {}: {}", key, paths);
    synchronized (keys) {
      keys.computeIfAbsent(key, (k) -> new HashSet<>()).addAll(paths);
      newAdds.set(true);
    }
  }
}
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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kohlschutter.efesnitch.PathRegistration;
import com.kohlschutter.util.PathUtil;

final class PathRegistrationImpl implements PathRegistration {
  private static final Logger LOG = LoggerFactory.getLogger(PathRegistrationImpl.class);

  final Set<WatchKey> keys = new HashSet<>();

  private final PathWatcherImpl modificationsWatcher;

  private final Consumer<Path> onEventConsumer;

  private final Path registeredPath;
  private Path realPath = null;

  private final AtomicInteger references = new AtomicInteger(0);
  private final AtomicBoolean canceled = new AtomicBoolean(false);

  PathRegistrationImpl(PathWatcherImpl modificationsWatcher, Path registeredPath,
      Consumer<Path> onEvent) {
    this.modificationsWatcher = modificationsWatcher;
    this.registeredPath = registeredPath;
    this.onEventConsumer = onEvent;
  }

  @Override
  public void cancel() {
    if (references.decrementAndGet() > 0) {
      return;
    }

    if (!canceled.compareAndSet(false, true)) {
      return; // already canceled
    }
    synchronized (this.modificationsWatcher.watchKeys) {
      for (WatchKey key : keys) {
        Map<PathRegistrationImpl, Boolean> map = this.modificationsWatcher.watchKeys.get(
            key);
        if (map == null) {
          continue;
        }
        map.remove(this);
      }
      keys.clear();
    }
  }

  void notifyConsumer() {
    CompletableFuture.runAsync(() -> onEventConsumer.accept(registeredPath));
  }

  public boolean covers(Path p) {
    if (p.startsWith(registeredPath)) {
      return true;
    }
    if (realPath == null) {
      realPath = PathUtil.partialRealpath(registeredPath);
    }
    if (p.startsWith(realPath)) {
      LOG.debug("Covered by {}: {}", registeredPath, p);
      return true;
    } else {
      LOG.debug("Not covered by {}: {}", registeredPath, p);
      return false;
    }
  }

  int incRef() {
    return references.incrementAndGet();
  }

  @Override
  public boolean isFresh() {
    return true;
  }
}
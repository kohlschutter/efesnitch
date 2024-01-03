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

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kohlschutter.annotations.compiletime.SuppressFBWarnings;
import com.kohlschutter.efesnitch.PathRegistration;
import com.kohlschutter.efesnitch.PathWatcher;
import com.kohlschutter.util.PathUtil;

/**
 * The default implementation of {@link PathWatcher}.
 *
 * @author Christian Kohlschütter
 */
public class PathWatcherImpl implements PathWatcher {
  private static final Logger LOG = LoggerFactory.getLogger(PathWatcherImpl.class);
  private static final Consumer<Path> PATH_CONSUMER_NO_OP = (p) -> {
  };
  private static final FileSystem DEFAULT_FILESYSTEM = FileSystems.getDefault();

  private final WatchService watchService;

  private final AtomicBoolean shutdown = new AtomicBoolean(false); // NOPMD.AvoidFieldNameMatchingMethodName
  private final CompletableFuture<Void> watchJob;

  final Map<WatchKey, Map<PathRegistrationImpl, Boolean>> watchKeys = new WeakHashMap<>();
  private final Map<Path, PathRegistrationImpl> registeredPaths = new WeakHashMap<>();

  private final PathEventBatcher eventBatcher = new PathEventBatcher(this);

  /**
   * {@link ServiceLoader} constructor.
   */
  @SuppressFBWarnings("CT_CONSTRUCTOR_THROW")
  public PathWatcherImpl() {
    try {
      watchService = DEFAULT_FILESYSTEM.newWatchService();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    watchJob = CompletableFuture.runAsync(() -> {
      while (!Thread.interrupted()) {
        if (shutdown.get()) {
          return;
        }
        try {
          WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
          if (key != null) {
            onModification(key);
          }
        } catch (ClosedWatchServiceException e) {
          return;
        } catch (InterruptedException e) {
          LOG.warn("Interrupted", e);
          return;
        }
      }
      if (!shutdown.get()) {
        LOG.warn("Interrupted");
      }
    });
  }

  private void onModification(WatchKey key) {
    LOG.debug("On modification {}", key);
    Set<Path> paths = new HashSet<>();
    try {
      for (WatchEvent<?> event : key.pollEvents()) {
        WatchEvent.Kind<?> kind = event.kind();
        if (kind == StandardWatchEventKinds.OVERFLOW) { // NOPMD.CompareObjectsWithEquals
          LOG.warn("Key event overflow: {}", key);
          continue;
        }
        Path refPath = (Path) key.watchable();
        Path path = (Path) event.context();

        Path resolved = refPath.resolve(path);
        LOG.debug("Modification detected {} for {}", kind, resolved);

        paths.add(resolved);

        if (kind == StandardWatchEventKinds.ENTRY_CREATE // NOPMD.CompareObjectsWithEquals
            && Files.isDirectory(resolved)) {
          try {
            // Update registration for subdirectories
            updateKeys(key, register0(resolved, paths::add));
          } catch (Exception e) {
            LOG.warn("Error upon register", e);
          }
        }
      }
    } finally {
      if (!key.reset()) {
        onInvalidWatchKey(key);
        paths.add((Path) key.watchable());
      }
      eventBatcher.addWatchKey(key, paths);
      eventBatcher.wakeup();
    }
  }

  private void updateKeys(WatchKey oldKey, Collection<WatchKey> newKeys) {
    LOG.debug("Updated keys: {} for {}", newKeys, oldKey);

    synchronized (watchKeys) {
      Set<PathRegistrationImpl> regs = new HashSet<>();

      Map<PathRegistrationImpl, Boolean> map = watchKeys.get(oldKey);
      if (map != null) {
        LOG.debug("Got map {} for key {}", map, oldKey);
        for (PathRegistrationImpl ri : map.keySet()) {
          LOG.debug("Registering new keys {} for {}", newKeys, ri);
          regs.add(ri);
          ri.keys.addAll(newKeys);
        }
      } else {
        LOG.error("Did not get map for existing key: {}", oldKey);
      }

      for (WatchKey k : newKeys) {
        Map<PathRegistrationImpl, Boolean> newMap = watchKeys.computeIfAbsent(k, (
            k1) -> new WeakHashMap<>());
        for (PathRegistrationImpl ri : regs) {
          newMap.put(ri, true);
        }
      }
    }
  }

  /**
   * Key is no longer valid (probably because the directory was deleted). Register again, which may
   * register the parent directory instead
   *
   * @param key The key.
   */
  private void onInvalidWatchKey(WatchKey key) {
    synchronized (watchKeys) {
      LOG.debug("Invalidated: {}", key);
      Path path = (Path) key.watchable();
      try {
        updateKeys(key, register0(path, PATH_CONSUMER_NO_OP));
      } catch (Exception e) {
        LOG.error("Error upon register", e);
      }
    }
  }

  /**
   * Waits until this instance is shut down.
   */
  public void awaitTermination() {
    if (isShutdown()) {
      return;
    }
    try {
      watchJob.get();
    } catch (CancellationException | InterruptedException | ExecutionException ignore) {
      // ignore
    }
  }

  /**
   * Closes this instance.
   * 
   * @throws IOException on error.
   */
  public void shutdown() throws IOException {
    shutdown.set(true);
    watchJob.cancel(true);
    watchService.close();
    awaitTermination();
  }

  @Override
  public boolean mayRegister(Path path) {
    if (path == null) {
      return false;
    }
    return DEFAULT_FILESYSTEM.equals(path.getFileSystem());
  }

  @Override
  public PathRegistration register(Path path, Consumer<Path> onEvent) throws IOException {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(onEvent, "onEvent");

    PathRegistrationImpl registration = registeredPaths.computeIfAbsent(path, (
        k) -> new PathRegistrationImpl(this, path, onEvent));
    if (registration.incRef() == 1) {
      synchronized (watchKeys) {
        Collection<WatchKey> keys = register0(path, PATH_CONSUMER_NO_OP);
        registration.keys.addAll(keys);
        for (WatchKey key : keys) {
          LOG.debug("Register {} for {}", key, registration);
          watchKeys.computeIfAbsent(key, (k) -> new WeakHashMap<>()).put(registration, true);
        }
      }
      return registration;
    } else {
      return new PathRegistration() {

        @Override
        public boolean isFresh() {
          return false;
        }

        @Override
        public void cancel() {
          registration.cancel();
        }
      };
    }
  }

  private Collection<WatchKey> register0(Path path, Consumer<Path> seenSubPaths)
      throws IOException {
    Collection<WatchKey> coll = register0(path, seenSubPaths, false, 0);
    coll.addAll(//
        register0(path, seenSubPaths, true, 0));
    return coll;
  }

  private Collection<WatchKey> register0(Path path, Consumer<Path> seenSubPaths, boolean realPath,
      int attempt) throws IOException {
    if (attempt > 0) {
      LOG.debug("register0, attempt {} for {}", attempt, path);
    }
    while (!Files.isDirectory(path)) {
      path = path.getParent();
      if (path == null) {
        path = Path.of("/");
        break;
      }
    }

    if (realPath) {
      Path origPath = path;
      path = PathUtil.partialRealpath(path);
      if (origPath.equals(path)) {
        // already covered
        return Collections.emptySet();
      }
    }
    try {
      return register1(path, seenSubPaths);
    } catch (IOException e) {
      if (attempt == 0) {
        // try again immediately
        return register0(path, seenSubPaths, realPath, 1);
      } else if (attempt >= 8) {
        LOG.error("Failed to register path after {} attempts: {}", attempt, path, e);
        throw e;
      } else {
        // try again later after sleeping for a while
        try {
          Thread.sleep((long) (100 * Math.pow(2, attempt)));
        } catch (InterruptedException e1) {
          LOG.warn("Interrupted", e1);
        }
        return register0(path, seenSubPaths, realPath, ++attempt);
      }
    }
  }

  private Collection<WatchKey> register1(Path path, Consumer<Path> subPathConsumer)
      throws IOException {
    Set<WatchKey> keys = new HashSet<>();
    Files.walkFileTree(path, new FileVisitor<Path>() {

      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
          throws IOException {
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        subPathConsumer.accept(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
        subPathConsumer.accept(dir);
        keys.add(register2(dir));

        return FileVisitResult.CONTINUE;
      }
    });
    return keys;
  }

  private WatchKey register2(Path path) throws IOException {
    WatchKey wk = path.register(this.watchService, StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
    LOG.debug("Registered path {}, got key: {}", path, wk);
    return wk;
  }

  /**
   * Called from {@link PathEventBatcher}.
   *
   * @param keys The keys.
   */
  @SuppressWarnings("PMD.CognitiveComplexity")
  void onEventKeys(Map<WatchKey, Collection<Path>> keys) {
    Set<PathRegistrationImpl> list = new HashSet<>();
    synchronized (watchKeys) {
      for (Map.Entry<WatchKey, Collection<Path>> en : keys.entrySet()) {
        WatchKey key = en.getKey();

        Map<PathRegistrationImpl, Boolean> map = watchKeys.get(key);
        if (map == null) {
          LOG.error("Key no longer registered: {}", key);
          continue;
        } else if (map.isEmpty()) {
          LOG.warn("No registrations for key {}", key);
        }

        Collection<Path> paths = en.getValue();

        boolean invalid = !key.isValid();
        LOG.debug("onEvent: key={} invalid={}", key, invalid);

        for (PathRegistrationImpl impl : map.keySet()) {
          if (impl == null) {
            continue;
          }

          boolean covered = false;
          for (Path p : paths) {
            if (impl.covers(p)) {
              covered = true;
              break;
            }
          }

          if (covered) {
            list.add(impl);
          }

          if (invalid) {
            impl.keys.remove(key);
          }
        }

        if (invalid) {
          watchKeys.remove(key);
        }
      }
    }
    for (PathRegistrationImpl impl : list) {
      impl.notifyConsumer();
    }
  }

  /**
   * Checks if this instance is shut down.
   * 
   * @return if shut down.
   */
  public boolean isShutdown() {
    return shutdown.get();
  }
}

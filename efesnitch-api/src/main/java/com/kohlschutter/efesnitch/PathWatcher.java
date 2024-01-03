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
package com.kohlschutter.efesnitch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

/**
 * A listener for file system modifications.
 * <p>
 * The listener supports listening for initially missing paths, for individual files, as well as
 * deletions of entire folder structures, and tries to batch modifications such that not all minor
 * events trigger immediately.
 *
 * @author Christian Kohlschütter
 */
public interface PathWatcher {
  /**
   * Checks if this instance may register the given path.
   *
   * @param path The path to check.
   * @return {@code true} unless a call to {@link #register(Path, Consumer)} is prohibited.
   */
  boolean mayRegister(Path path);

  /**
   * Registers a notification listener for the given path.
   *
   * @param path The path to get notified on.
   * @param onEvent The notification listener.
   * @return The registration handle, which can be used to cancel the registration.
   * @throws IOException on error.
   */
  PathRegistration register(Path path, Consumer<Path> onEvent) throws IOException;

  /**
   * Returns the default {@link PathWatcher} instance, which is defined via SPI.
   *
   * @return The default instance.
   * @throws NoSuchElementException if no default instance is known.
   */
  static PathWatcher getDefaultInstance() {
    return DefaultInstanceHelper.getDefaultPathWatcher();
  }
}

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

import java.util.Optional;
import java.util.ServiceLoader;

final class DefaultInstanceHelper {
  @SuppressWarnings("null")
  private static final Optional<PathWatcher> PATH_WATCHER_INSTANCE = ServiceLoader.load(
      PathWatcher.class).findFirst();

  static PathWatcher getDefaultPathWatcher() {
    return PATH_WATCHER_INSTANCE.get();
  }
}

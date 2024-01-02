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

/**
 * A registration ticket for a path registered to be watched via {@link PathWatcher}.
 * 
 * @author Christian Kohlschütter
 */
public interface PathRegistration {
  /**
   * Cancels this registration for the corresponding path.
   */
  void cancel();

  /**
   * Returns {@code true} if there was no other registration for the corresponding path.
   *
   * @return {@code true} if fresh.
   */
  boolean isFresh();
}

// Copyright 2013 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.adaptor.sharepoint;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/**
 * A simple ExecutorService that executes all tasks in the calling thread. It
 * does not implement termination semantics properly; it is immediately
 * "terminated" after being shutdown, even if execute is still running.
 */
class CallerRunsExecutor extends AbstractExecutorService {
  private volatile boolean isShutdown;

  @Override
  public void execute(Runnable command) {
    if (isShutdown) {
      throw new RejectedExecutionException("Executor already shutdown");
    }
    command.run();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) {
    return isShutdown;
  }

  @Override
  public boolean isShutdown() {
    return isShutdown;
  }

  @Override
  public boolean isTerminated() {
    return isShutdown;
  }

  @Override
  public void shutdown() {
    isShutdown = true;
  }

  @Override
  public List<Runnable> shutdownNow() {
    shutdown();
    return Collections.emptyList();
  }
}

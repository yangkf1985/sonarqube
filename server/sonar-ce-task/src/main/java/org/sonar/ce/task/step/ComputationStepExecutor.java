/*
 * SonarQube
 * Copyright (C) 2009-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.ce.task.step;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.core.util.logs.Profiler;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public final class ComputationStepExecutor {
  private static final Logger LOGGER = Loggers.get(ComputationStepExecutor.class);

  private final ComputationSteps steps;
  @CheckForNull
  private final Listener listener;

  /**
   * Used when no {@link ComputationStepExecutor.Listener} is available in pico
   * container.
   */
  public ComputationStepExecutor(ComputationSteps steps) {
    this(steps, null);
  }

  public ComputationStepExecutor(ComputationSteps steps, @Nullable Listener listener) {
    this.steps = steps;
    this.listener = listener;
  }

  public void execute() {
    Profiler stepProfiler = Profiler.create(LOGGER).logTimeLast(true);
    boolean allStepsExecuted = false;
    try {
      executeSteps(stepProfiler);
      allStepsExecuted = true;
    } finally {
      if (listener != null) {
        executeListener(allStepsExecuted);
      }
    }
  }

  private void executeSteps(Profiler stepProfiler) {
    StepStatisticsImpl statistics = new StepStatisticsImpl(stepProfiler);
    ComputationStep.Context context = new StepContextImpl(statistics);
    for (ComputationStep step : steps.instances()) {
      stepProfiler.start();
      step.execute(context);
      stepProfiler.stopInfo(step.getDescription());
    }
  }

  private void executeListener(boolean allStepsExecuted) {
    try {
      listener.finished(allStepsExecuted);
    } catch (Throwable e) {
      // any Throwable throws by the listener going up the stack might hide an Exception/Error thrown by the step and
      // cause it be swallowed. We don't wan't that => we catch Throwable
      LOGGER.error("Execution of listener failed", e);
    }
  }

  @FunctionalInterface
  public interface Listener {
    void finished(boolean allStepsExecuted);
  }

  private static class StepStatisticsImpl implements ComputationStep.Statistics {
    private final Profiler profiler;

    private StepStatisticsImpl(Profiler profiler) {
      this.profiler = profiler;
    }

    @Override
    public void add(String key, Object value) {
      requireNonNull(key, "Statistic has null key");
      requireNonNull(value, () -> String.format("Statistic with key [%s] has null value", key));
      checkArgument(!key.equalsIgnoreCase("time"), "Statistic with key [time] is not accepted");
      checkArgument(!profiler.hasContext(key), "Statistic with key [%s] is already present", key);
      profiler.addContext(key, value);
    }
  }

  private static class StepContextImpl implements ComputationStep.Context {
    private final ComputationStep.Statistics statistics;

    private StepContextImpl(ComputationStep.Statistics statistics) {
      this.statistics = statistics;
    }

    @Override
    public ComputationStep.Statistics getStatistics() {
      return statistics;
    }
  }
}

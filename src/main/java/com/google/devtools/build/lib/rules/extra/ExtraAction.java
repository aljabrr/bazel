// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.extra;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CompositeRunfilesSupplier;
import com.google.devtools.build.lib.actions.DelegateSpawn;
import com.google.devtools.build.lib.actions.RunfilesSupplier;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnActionContext;
import com.google.devtools.build.lib.analysis.actions.CommandLine;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * Action used by extra_action rules to create an action that shadows an existing action. Runs a
 * command-line using {@link SpawnActionContext} for executions.
 */
public final class ExtraAction extends SpawnAction {
  private final Action shadowedAction;
  private final boolean createDummyOutput;
  private final RunfilesSupplier runfilesSupplier;
  private final ImmutableSet<Artifact> extraActionInputs;
  private final Iterable<Artifact> originalShadowedActionInputs;

  // This can be read/written from multiple threads, and so accesses should be synchronized.
  @GuardedBy("this")
  private boolean inputsKnown;

  /**
   * A long way to say (ExtraAction xa) -> xa.getShadowedAction().
   */
  public static final Function<ExtraAction, Action> GET_SHADOWED_ACTION =
      new Function<ExtraAction, Action>() {
        @Nullable
        @Override
        public Action apply(@Nullable ExtraAction extraAction) {
          return extraAction != null ? extraAction.getShadowedAction() : null;
        }
      };

  ExtraAction(
      ImmutableSet<Artifact> extraActionInputs,
      RunfilesSupplier runfilesSupplier,
      Collection<Artifact> outputs,
      Action shadowedAction,
      boolean createDummyOutput,
      CommandLine argv,
      Map<String, String> environment,
      Set<String> clientEnvironmentVariables,
      Map<String, String> executionInfo,
      String progressMessage,
      String mnemonic) {
    super(
        shadowedAction.getOwner(),
        ImmutableList.<Artifact>of(),
        createInputs(shadowedAction.getInputs(), extraActionInputs, runfilesSupplier),
        outputs,
        AbstractAction.DEFAULT_RESOURCE_SET,
        argv,
        ImmutableMap.copyOf(environment),
        ImmutableSet.copyOf(clientEnvironmentVariables),
        ImmutableMap.copyOf(executionInfo),
        progressMessage,
        // TODO(michajlo): Do we need the runfiles manifest as an input / should this be composite?
        shadowedAction.getRunfilesSupplier(),
        mnemonic,
        false,
        null);
    this.originalShadowedActionInputs = shadowedAction.getInputs();
    this.shadowedAction = shadowedAction;
    this.runfilesSupplier = runfilesSupplier;
    this.createDummyOutput = createDummyOutput;

    this.extraActionInputs = extraActionInputs;
    inputsKnown = shadowedAction.inputsKnown();
    if (createDummyOutput) {
      // Expecting just a single dummy file in the outputs.
      Preconditions.checkArgument(outputs.size() == 1, outputs);
    }
  }

  @Override
  public boolean discoversInputs() {
    return shadowedAction.discoversInputs();
  }

  @Nullable
  @Override
  public Iterable<Artifact> discoverInputs(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    Preconditions.checkState(discoversInputs(), this);
    // We depend on the outputs of actions doing input discovery and they should know their inputs
    // after having been executed
    Preconditions.checkState(shadowedAction.inputsKnown());

    // We need to update our inputs to take account of any additional
    // inputs the shadowed action may need to do its work.
    updateInputs(createInputs(shadowedAction.getInputs(), extraActionInputs, runfilesSupplier));
    return Sets.difference(ImmutableSet.copyOf(shadowedAction.getInputs()),
        ImmutableSet.copyOf(originalShadowedActionInputs));
  }

  @Override
  public synchronized boolean inputsKnown() {
    return inputsKnown;
  }

  private static NestedSet<Artifact> createInputs(
      Iterable<Artifact> shadowedActionInputs,
      ImmutableSet<Artifact> extraActionInputs,
      RunfilesSupplier extraActionRunfilesSupplier) {
    NestedSetBuilder<Artifact> result = new NestedSetBuilder<>(Order.STABLE_ORDER);
    if (shadowedActionInputs instanceof NestedSet) {
      result.addTransitive((NestedSet<Artifact>) shadowedActionInputs);
    } else {
      result.addAll(shadowedActionInputs);
    }
    result.addAll(extraActionRunfilesSupplier.getArtifacts());
    return result.addAll(extraActionInputs).build();
  }

  @Override
  public synchronized void updateInputs(Iterable<Artifact> discoveredInputs) {
    setInputs(discoveredInputs);
    inputsKnown = true;
  }

  @Override
  public Iterable<Artifact> getAllowedDerivedInputs() {
    return shadowedAction.getAllowedDerivedInputs();
  }

  /**
   * @InheritDoc
   *
   * This method calls in to {@link AbstractAction#getInputFilesForExtraAction} and
   * {@link Action#getExtraActionInfo} of the action being shadowed from the thread executing this
   * ExtraAction. It assumes these methods are safe to call from a different thread than the thread
   * responsible for the execution of the action being shadowed.
   */
  @Override
  public void execute(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    // PHASE 2: execution of extra_action.

    super.execute(actionExecutionContext);

    // PHASE 3: create dummy output.
    // If the user didn't specify output, we need to create dummy output
    // to make blaze schedule this action.
    if (createDummyOutput) {
      for (Artifact output : getOutputs()) {
        try {
          FileSystemUtils.touchFile(output.getPath());
        } catch (IOException e) {
          throw new ActionExecutionException(e.getMessage(), e, this, false);
        }
      }
    }
    synchronized (this) {
      inputsKnown = true;
    }
  }

  /**
   * The spawn command for ExtraAction needs to be slightly modified from
   * regular SpawnActions:
   * -the extraActionInfo file needs to be added to the list of inputs.
   * -the extraActionInfo file that is an output file of this task is created
   * before the SpawnAction so should not be listed as one of its outputs.
   */
  // TODO(bazel-team): Add more tests that execute this code path!
  @Override
  public Spawn getSpawn(Map<String, String> clientEnv) {
    final Spawn base = super.getSpawn(clientEnv);
    return new DelegateSpawn(base) {
      @Override
      public RunfilesSupplier getRunfilesSupplier() {
        return new CompositeRunfilesSupplier(super.getRunfilesSupplier(), runfilesSupplier);
      }

      @Override public String getMnemonic() { return ExtraAction.this.getMnemonic(); }
    };
  }

  /**
   * Returns the action this extra action is 'shadowing'.
   */
  public Action getShadowedAction() {
    return shadowedAction;
  }
}

/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package com.uber.cadence.samples.hello;

import static com.uber.cadence.samples.common.SampleConstants.DOMAIN;

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.worker.Worker;
import com.uber.cadence.workflow.*;
import java.time.Duration;

/** Demonstrates a child workflow. Requires a local instance of the Cadence server to be running. */
public class HelloChild {

  static final String TASK_LIST = "HelloChild";

  /** The parent workflow interface. */
  public interface GreetingWorkflow {
    /** @return greeting string */
    @WorkflowMethod(executionStartToCloseTimeoutSeconds = 60, taskList = TASK_LIST)
    String getGreeting(String name);
  }

  /** The child workflow interface. */
  public interface GreetingChild {
    @WorkflowMethod
    String composeGreeting(String greeting, String name);
  }

  /** GreetingWorkflow implementation that calls GreetingsActivities#printIt. */
  public static class GreetingWorkflowImpl implements GreetingWorkflow {

    @Override
    public String getGreeting(String name) {
      // Workflows are stateful. So a new stub must be created for each new child.
      RetryOptions retryOptions =
          new RetryOptions.Builder()
              .setExpiration(Duration.ofMinutes(1))
              .setInitialInterval(Duration.ofSeconds(10))
              .setMaximumAttempts(3)
              .setBackoffCoefficient(1.0)
              .build();

      ChildWorkflowOptions childWorkflowOptions =
          new ChildWorkflowOptions.Builder().setRetryOptions(retryOptions).build();

      GreetingChild child =
          Workflow.newChildWorkflowStub(GreetingChild.class, childWorkflowOptions);

      // This is a non blocking call that returns immediately.
      // Use child.composeGreeting("Hello", name) to call synchronously.
      Promise<String> greeting = Async.function(child::composeGreeting, "Hello", name);
      // Do something else here.
      return greeting.get(); // blocks waiting for the child to complete.
    }

    // This example shows how parent workflow return right after starting a child workflow,
    // and let the child run itself.
    private String demoAsyncChildRun(String name) {
      GreetingChild child = Workflow.newChildWorkflowStub(GreetingChild.class);
      // non blocking call that initiated child workflow
      Async.function(child::composeGreeting, "Hello", name);
      // instead of using greeting.get() to block till child complete,
      // sometimes we just want to return parent immediately and keep child running
      Promise<WorkflowExecution> childPromise = Workflow.getWorkflowExecution(child);
      childPromise.get(); // block until child started,
      // otherwise child may not start because parent complete first.
      return "let child run, parent just return";
    }
  }

  /**
   * The child workflow implementation. A workflow implementation must always be public for the
   * Cadence library to be able to create instances.
   */
  public static class GreetingChildImpl implements GreetingChild {
    @Override
    public String composeGreeting(String greeting, String name) {
      if (name.equals("World")) {
        throw new RuntimeException("nope");
      }
      return greeting + " " + name + "!";
    }
  }

  public static void main(String[] args) {
    // Start a worker that hosts both parent and child workflow implementations.
    Worker.Factory factory = new Worker.Factory(DOMAIN);
    Worker worker = factory.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(GreetingWorkflowImpl.class, GreetingChildImpl.class);
    // Start listening to the workflow task list.
    factory.start();

    // Start a workflow execution. Usually this is done from another program.
    WorkflowClient workflowClient = WorkflowClient.newInstance(DOMAIN);
    // Get a workflow stub using the same task list the worker uses.
    GreetingWorkflow workflow = workflowClient.newWorkflowStub(GreetingWorkflow.class);
    // Execute a workflow waiting for it to complete.
    String greeting = workflow.getGreeting("World");
    System.out.println(greeting);
    System.exit(0);
  }
}

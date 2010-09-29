/*
 * Licensed to Think Big Analytics, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Think Big Analytics, Inc. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * Copyright 2010 Think Big Analytics. All Rights Reserved.
 */
package colossal.pipe;

import java.util.*;
import java.util.concurrent.*;

import org.apache.hadoop.mapred.JobConf;

import colossal.util.Alerter;
import colossal.util.EmailAlerter;

public class ColPipe {
    private List<ColFile> writes;
    private int parallelTasks = 1;
    private JobConf baseConf = new JobConf();
    private Alerter alerter = new EmailAlerter();
    private String name = "";
    private boolean forceRebuild = false;    

    public ColPipe() {        
    }
    
    public ColPipe(Class<?> jarClass) {
        baseConf.setJarByClass(jarClass);
    }

    public ColPipe produces(List<ColFile> outputs) {
        if (writes == null) {
            writes = new ArrayList<ColFile>(outputs);
        }
        else {
            writes.addAll(outputs);
        }
        return this;
    }

    public ColPipe produces(ColFile... outputs) {
        return produces(Arrays.asList(outputs));
    }
    
    public ColPipe named(String name) {
        this.name = name;
        return this;
    }

    public ColPipe forceRebuild() {
        this.forceRebuild = true;
        return this;        
    }

    public void execute() throws InfeasiblePlanException {
        List<PhaseError> result = new ArrayList<PhaseError>();
        PipePlan plan = generatePlan(result);
        if (result.isEmpty()) {
            plan = optimize(plan, result);
            if (result.isEmpty()) {
                execute(plan, result);
            }
        }
        respond(result);
    }

    private void respond(List<PhaseError> result) {
        if (!result.isEmpty()) {
            alerter.alert(result);
        }
        else {
            alerter.pipeCompletion(getName(), "Execution completed successfully.");
        }
    }

    private List<PhaseError> execute(final PipePlan plan, List<PhaseError> errors) {
        ExecutorService execution = Executors.newFixedThreadPool(parallelTasks);
        submit(plan, execution, Collections.synchronizedList(errors));
        
        try {
            synchronized(plan) {
                while (!plan.isComplete() && errors.isEmpty()) {
                    plan.wait();
                }
            }
            execution.shutdown();
            execution.awaitTermination(20L, TimeUnit.DAYS);
        }
        catch (InterruptedException e) {
            System.err.println("interrupted job");
            alerter.alert("pipe execution interrupted");
        }
        return errors;
    }

    private List<PhaseError> submit(final PipePlan plan, final ExecutorService execution, final List<PhaseError> errors) {
        plan.plan();
        Set<ColPhase> next = plan.getNextProcesses();
        if (next != null) {        
            for (final ColPhase process : next) {
                execution.submit(new Runnable() {
                    @Override
                    public void run() {
                        plan.executing(process);
                        try {
                            PhaseError error = process.submit();
                            if (error != null) {
                                errors.add(error);
                                // alert immediately
                                System.err.println("Phase failed: " + error.getMessage());
                                plan.failed(process);
                            }
                            else {
                                plan.updated(process);
                                submit(plan, execution, Collections.synchronizedList(errors));                            
                            }
                        } finally {
                            synchronized(plan) {
                                plan.notify();
                            }
                        }
                    }
    
                });
            }
        }
        return errors;
    }

    private PipePlan optimize(PipePlan plan, List<PhaseError> result) {
        // not yet
        return plan;
    }

    private PipePlan generatePlan(List<PhaseError> errors) {
        Set<ColFile> toGenerate = new HashSet<ColFile>(writes);
        Set<ColFile> generated = new HashSet<ColFile>();        
        // Map<DistFile,Collection<DistFile>> dependencies;
        PipePlan plan = new PipePlan();
        while (!toGenerate.isEmpty()) {
            ColFile file = toGenerate.iterator().next();
            toGenerate.remove(file);
            boolean exists = file.exists(baseConf);
            if (exists && !file.isObsolete() && (!forceRebuild || file.getProducer() == null)) {                
                if (!generated.contains(file)) {
                    System.out.println("File: "+file.getPath()+" exists and is up to date.");
                    // ok already
                    generated.add(file);
                    plan.fileCreateWith(file, null);
                }
            }
            else {
                ColPhase process = file.getProducer();
                plan.fileCreateWith(file, process);
                if (process == null) {
                    errors.add(new PhaseError("Don't know how to generate " + file.getPath()));
                } else {
                    System.out.println("File: "+file.getPath()+(exists ? " is obsolete" : " is missing")+" and will be generated from "+process.getSummary());
                    List<ColFile> inputs = process.getInputs();
                    if (inputs != null) {
                        for (ColFile input : inputs) {
                            toGenerate.add(input);
                            plan.processReads(process, input);
                        }
                    }
                    process.plan(this);
                }
            }
        }
        return plan;
    }

    public void setParallelTasks(int parallelTasks) {
        this.parallelTasks = parallelTasks;
    }

    public int getParallelTasks() {
        return parallelTasks;
    }

    public String getName() {
        return name ;
    }

    public JobConf getConf() {
        return baseConf;
    }

}

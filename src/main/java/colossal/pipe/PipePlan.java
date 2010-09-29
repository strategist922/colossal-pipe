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

class PipePlan {

    private Map<ColFile, ColPhase> fileDeps = new LinkedHashMap<ColFile, ColPhase>();
    private Map<ColPhase, Set<ColFile>> processDeps = new LinkedHashMap<ColPhase, Set<ColFile>>();
    private Set<ColFile> prebuilt = new HashSet<ColFile>();
    private Set<ColPhase> failed = new HashSet<ColPhase>();
    private Set<ColPhase> nextProcesses;
    private Set<ColPhase> executing = new HashSet<ColPhase>();

    public synchronized void fileCreateWith(ColFile file, ColPhase process) {
        if (process == null) {
            prebuilt.add(file);
        } else {
            fileDeps.put(file, process);
        }
    }

    public synchronized void processReads(ColPhase process, ColFile file) {
        Set<ColFile> procDep = processDeps.get(process);
        if (procDep == null) {
            procDep = new HashSet<ColFile>();
            processDeps.put(process, procDep);
        }
        procDep.add(file);
    }
    
    public synchronized void failed(ColPhase process) {
        failed.add(process);
        executing.remove(process);
    }
    
    public synchronized void updated(ColPhase process) {
        executing.remove(process);
        // remove dependence ON this process from files that it generates - fileDeps
        for (ColFile file : process.getOutputs()) {
            prebuilt.add(file);
            fileDeps.remove(file);
        }
        processDeps.remove(process);
        // could incrementally update plan, but for now we just do it in batch
    }
    
    public synchronized void plan() {        
        Set<ColFile> toPlan = new HashSet<ColFile>(fileDeps.keySet());        
        nextProcesses = null;
        if (!failed.isEmpty())
            return; // don't run anything else in a failed job

        // pull out all the leaf nodes (i.e., those with no unplanned dependencies) as another parallel wave that can execute
        // this doesn't specify exact scheduling as processes finish but provides more parallelism than pure serial operation 
        while (!toPlan.isEmpty()) {
            Set<ColFile> wave = new HashSet<ColFile>();
            for (ColFile file : toPlan) {
                ColPhase process = fileDeps.get(file);
                if (!executing.contains(process)) {
                    boolean canRemove = true;
                    for (ColFile dependency : processDeps.get(process)) {
                        if (toPlan.contains(dependency)) {
                            canRemove = false;
                            break;
                        }
                    }
                    if (canRemove) {
                        wave.add(file);
                    }
                }
            }
            if (wave.isEmpty() && executing.isEmpty()) {
                StringBuilder cycle = new StringBuilder();
                for (ColFile file : toPlan) {
                    cycle.append(' ').append(file.getPath());
                }
                throw new IllegalStateException("Cyclic dependency among files: "+cycle);
            }
            for (ColFile file : wave)
                toPlan.remove(file);
            if (nextProcesses == null) {
                nextProcesses = new HashSet<ColPhase>();
                // all that really matters is the initial wave - after that execute tasks when they aren't dependent
                // that can be done by re-planning...
                for (ColFile file : wave) {
                    // we should really score these and order them by some kind of priority
                    // for now we submit everything that can be done in parallel
                    nextProcesses.add(file.getProducer());
                }
            }
        }
    }
    
    /**
     * Must call AFTER calling plan.
     */
    public synchronized Set<ColPhase> getNextProcesses() {
        return nextProcesses==null ? null : Collections.unmodifiableSet(nextProcesses);
    }

    public synchronized void executing(ColPhase process) {
        executing.add(process);        
    }

    public synchronized boolean isComplete() {
        return executing.isEmpty() && (!failed.isEmpty() || fileDeps.isEmpty());
    }
}

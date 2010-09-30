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

import java.io.IOException;

import org.apache.avro.mapred.AvroOutputFormat;
import org.apache.avro.mapred.AvroWrapper;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.TextOutputFormat;

public class ColFile<T> {
    private ColPhase producer;
    private String path;
    private T prototype;
    private Formats format = Formats.AVRO_FORMAT;

    public static enum Formats {
        STRING_FORMAT {
            @Override
            public void setupOutput(JobConf conf) {
                conf.setOutputFormat(TextOutputFormat.class);
                conf.setOutputKeyClass(String.class);
            }
        },
        JSON_FORMAT {
            @Override
            public void setupOutput(JobConf conf) {
                conf.setOutputFormat(TextOutputFormat.class);                
                conf.setOutputKeyClass(String.class);
            }
        },
        AVRO_FORMAT {
            @Override
            public void setupOutput(JobConf conf) {
                conf.setOutputFormat(AvroOutputFormat.class);
                conf.setOutputKeyClass(AvroWrapper.class);
            }
        };

        public abstract void setupOutput(JobConf conf);
    }

    @Deprecated
    public ColFile(T prototype) {
        this.prototype = prototype;
    }

    public ColFile(String path) {
        this.path = path;
    }

    public boolean exists(Configuration conf) {
        Path dfsPath = new Path(path);
        try {
            FileSystem fs = dfsPath.getFileSystem(conf);
            return fs.exists(dfsPath);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isObsolete() {
        // this needs to be smart - we should encode in the file metadata the dependents and their dates used
        // so we can verify that any existing antecedent is not newer and declare victory...
        // TODO Auto-generated method stub
        return false; // needs more work!
    }

    public ColPhase getProducer() {
        return producer;
    }

    public void setProducer(ColPhase producer) {
        this.producer = producer;
    }

    public String getPath() {
        return path;
    }

    public ColFile<T> at(String path) {
        this.path = path;
        return this;
    }

    @Override
    public String toString() {
        return path + ":" + super.toString();
    }

    public void clearAndPrepareOutput(Configuration conf) {
        try {
            Path dfsPath = new Path(path);
            FileSystem fs = dfsPath.getFileSystem(conf);
            fs.mkdirs(dfsPath);
            ContentSummary cs = fs.getContentSummary(dfsPath);
            if (cs.getDirectoryCount() > 1) {
                throw new IllegalArgumentException("Trying to overwrite directory with child directories: " + path);
            }
            fs.delete(dfsPath, true);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> ColFile<T> of(Class<? extends T> ofClass) {
        try {
            return new ColFile<T>(ofClass.newInstance());
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> ColFile<T> of(T prototype) {
        return new ColFile<T>(prototype);
    }

    public T getPrototype() {
        return prototype;
    }

    public void delete(JobConf conf) {
        clearAndPrepareOutput(conf);
    }

    public ColFile stringFormat() {
        this.format = Formats.STRING_FORMAT;
        return this;
    }

    public ColFile jsonFormat() {
        this.format = Formats.JSON_FORMAT;
        return this;
    }

    public ColFile avroFormat() {
        this.format = Formats.AVRO_FORMAT;
        return this;
    }

    public void setupOutput(JobConf conf) {
        format.setupOutput(conf);
    }

    public long getTimestamp(JobConf conf) {
        try {
            Path dfsPath = new Path(path);
            FileSystem fs = dfsPath.getFileSystem(conf);
            return fs.getFileStatus(dfsPath).getModificationTime();            
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
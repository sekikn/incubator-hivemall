/*
 * Hivemall: Hive scalable Machine Learning Library
 *
 * Copyright (C) 2013
 *   National Institute of Advanced Industrial Science and Technology (AIST)
 *   Registration Number: H25PRO-1520
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package hivemall.utils.hadoop;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.util.Iterator;
import java.util.Map.Entry;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.exec.MapredContext;
import org.apache.hadoop.hive.ql.exec.MapredContextAccessor;
import org.apache.hadoop.io.compress.CodecPool;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionCodecFactory;
import org.apache.hadoop.io.compress.CompressionInputStream;
import org.apache.hadoop.io.compress.Decompressor;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobID;
import org.apache.hadoop.mapred.TaskID;

public final class HadoopUtils {

    private HadoopUtils() {}

    public static BufferedReader getBufferedReader(File file) throws IOException {
        MapredContext context = MapredContextAccessor.get();
        return getBufferedReader(file, context);
    }

    public static BufferedReader getBufferedReader(File file, MapredContext context)
            throws IOException {
        URI fileuri = file.toURI();
        Path path = new Path(fileuri);

        Configuration conf = context.getJobConf();
        CompressionCodecFactory ccf = new CompressionCodecFactory(conf);
        CompressionCodec codec = ccf.getCodec(path);

        if(codec == null) {
            return new BufferedReader(new FileReader(file));
        } else {
            Decompressor decompressor = CodecPool.getDecompressor(codec);
            FileInputStream fis = new FileInputStream(file);
            CompressionInputStream cis = codec.createInputStream(fis, decompressor);
            BufferedReader br = new BufferedReaderExt(new InputStreamReader(cis), decompressor);
            return br;
        }
    }

    private static final class BufferedReaderExt extends BufferedReader {

        private Decompressor decompressor;

        BufferedReaderExt(Reader in, Decompressor decompressor) {
            super(in);
            this.decompressor = decompressor;
        }

        @Override
        public void close() throws IOException {
            super.close();
            if(decompressor != null) {
                CodecPool.returnDecompressor(decompressor);
                this.decompressor = null;
            }
        }

    }

    @Nonnull
    public static String getJobId() {
        MapredContext ctx = MapredContextAccessor.get();
        if(ctx == null) {
            throw new IllegalStateException("MapredContext is not set");
        }
        JobConf conf = ctx.getJobConf();
        if(conf == null) {
            throw new IllegalStateException("JobConf is not set");
        }
        String jobId = conf.get("mapred.job.id");
        if(jobId == null) {
            jobId = conf.get("mapreduce.job.id");
            if(jobId == null) {
                String queryId = conf.get("hive.query.id");
                if(queryId != null) {
                    return queryId;
                }
                String taskidStr = conf.get("mapred.task.id");
                if(taskidStr == null) {
                    throw new IllegalStateException("Cannot resolve jobId: " + toString(conf));
                }
                jobId = getJobIdFromTaskId(taskidStr);
            }
        }
        return jobId;
    }

    @Nonnull
    public static String getJobIdFromTaskId(@Nonnull String taskidStr) {
        if(!taskidStr.startsWith("task_")) {// workaround for Tez
            taskidStr = taskidStr.replace("task", "task_");
            taskidStr = taskidStr.substring(0, taskidStr.lastIndexOf('_'));
        }
        TaskID taskId = TaskID.forName(taskidStr);
        JobID jobId = taskId.getJobID();
        return jobId.toString();
    }

    public static int getTaskId() {
        MapredContext ctx = MapredContextAccessor.get();
        if(ctx == null) {
            throw new IllegalStateException("MapredContext is not set");
        }
        JobConf jobconf = ctx.getJobConf();
        if(jobconf == null) {
            throw new IllegalStateException("JobConf is not set");
        }
        int taskid = jobconf.getInt("mapred.task.partition", -1);
        if(taskid == -1) {
            taskid = jobconf.getInt("mapreduce.task.partition", -1);
            if(taskid == -1) {
                throw new IllegalStateException("Both mapred.task.partition and mapreduce.task.partition are not set: "
                        + toString(jobconf));
            }
        }
        return taskid;
    }

    @Nonnull
    public static String toString(@Nonnull JobConf jobconf) {
        return toString(jobconf, null);
    }

    @Nonnull
    public static String toString(@Nonnull JobConf jobconf, @Nullable String regexKey) {
        final Iterator<Entry<String, String>> itor = jobconf.iterator();
        boolean hasNext = itor.hasNext();
        if(!hasNext) {
            return "";
        }
        final StringBuilder buf = new StringBuilder(1024);
        do {
            Entry<String, String> e = itor.next();
            hasNext = itor.hasNext();
            String k = e.getKey();
            if(k == null) {
                continue;
            }
            if(regexKey == null || k.matches(regexKey)) {
                String v = e.getValue();
                buf.append(k).append('=').append(v);
                if(hasNext) {
                    buf.append(',');
                }
            }
        } while(hasNext);
        return buf.toString();
    }
}
/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2010 Oracle and/or its affiliates. All rights reserved.
 *
 * Oracle and Java are registered trademarks of Oracle and/or its affiliates.
 * Other names may be trademarks of their respective owners.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common
 * Development and Distribution License("CDDL") (collectively, the
 * "License"). You may not use this file except in compliance with the
 * License. You can obtain a copy of the License at
 * http://www.netbeans.org/cddl-gplv2.html
 * or nbbuild/licenses/CDDL-GPL-2-CP. See the License for the
 * specific language governing permissions and limitations under the
 * License.  When distributing the software, include this License Header
 * Notice in each file and include the License file at
 * nbbuild/licenses/CDDL-GPL-2-CP.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the GPL Version 2 section of the License file that
 * accompanied this code. If applicable, add the following below the
 * License Header, with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * Contributor(s):
 * The Original Software is NetBeans. The Initial Developer of the Original
 * Software is Sun Microsystems, Inc. Portions Copyright 1997-2006 Sun
 * Microsystems, Inc. All Rights Reserved.
 *
 * If you wish your version of this file to be governed by only the CDDL
 * or only the GPL Version 2, indicate your decision by adding
 * "[Contributor] elects to include this software in this distribution
 * under the [CDDL or GPL Version 2] license." If you do not indicate a
 * single choice of license, a recipient has the option to distribute
 * your version of this file under either the CDDL, the GPL Version 2 or
 * to extend the choice of license to its licensees as provided above.
 * However, if you add GPL Version 2 code and therefore, elected the GPL
 * Version 2 license, then the option applies only if the new code is
 * made subject to such option by the copyright holder.
 */

package org.netbeans.modules.profiler;

import java.io.IOException;
import java.lang.management.LockInfo;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import org.netbeans.api.progress.ProgressHandle;
import org.netbeans.api.progress.ProgressHandleFactory;
import org.netbeans.lib.profiler.common.ProfilingSettingsPresets;
import org.netbeans.lib.profiler.results.cpu.CPUResultsSnapshot;
import org.netbeans.lib.profiler.results.cpu.CPUResultsSnapshot.NoDataAvailableException;
import org.netbeans.lib.profiler.results.cpu.StackTraceSnapshotBuilder;
import org.netbeans.modules.profiler.LoadedSnapshot.SamplesInputStream;
import org.netbeans.modules.profiler.LoadedSnapshot.ThreadsSample;
import org.openide.filesystems.FileObject;

/** SampledCPUSnapshot provides access to NPSS file
 *
 * @author Tomas Hurka
 */
public final class SampledCPUSnapshot {
    public static final String OPEN_THREADS_URL = "file:/stackframe/";     // NOI18N

    private FileObject npssFile;
    private SamplesInputStream samplesStream;
    private long lastTimestamp;
    private int samples;
    private int currentIndex;
    private ThreadsSample sample;
    private StackTraceSnapshotBuilder builder;
    private long startTime;

    public SampledCPUSnapshot(FileObject file) throws IOException {
        samplesStream = new SamplesInputStream(file.getInputStream());
        npssFile = file;
        samples = samplesStream.getSamples();
        lastTimestamp = samplesStream.getLastTimestamp();
        if (samples == 0) {
            initSamples();
        }
        currentIndex = -1;
    }

    public int getSamplesCount() {
        return samples;
    }

    public long getStartTime() {
        return startTime;
    }
    
    public long getTimestamp(int sampleIndex) throws IOException {
        long timestamp;

        if (sampleIndex == getSamplesCount()-1) {
            return lastTimestamp;
        }
        getSample(sampleIndex);
        timestamp = sample.getTime();
        if (startTime == 0) {
            startTime = timestamp;
            builder = new StackTraceSnapshotBuilder();
        }
        builder.addStacktrace(sample.getTinfos(),timestamp);
        return timestamp;
    }

    public long getValue(int sampleIndex, int valIndex) throws IOException {
        getSample(sampleIndex);
        long ret = 0;
        for (ThreadInfo info : sample.getTinfos()) {
            if (info.getThreadState().equals(Thread.State.RUNNABLE)) {
                ret += info.getStackTrace().length;
            }
        }
        return ret;
    }

    public String getThreadDump(int sampleIndex) throws IOException {
        StringBuilder sb = new StringBuilder(4096);
        SamplesInputStream stream = seek(sampleIndex);
        ThreadsSample _sample = stream.readSample();
        ThreadInfo[] threads = _sample.getTinfos();

        stream.close();
        stream = null;
        printThreads(sb, threads);
        return sb.toString();
    }

    public LoadedSnapshot getCPUSnapshot(int startIndex, int endIndex) throws IOException {
        LoadedSnapshot snapshot;

        if (builder != null && samplesStream == null &&
            startIndex == 0 && endIndex == getSamplesCount()-1) { // full snapshot prepared in advance
            snapshot = createSnapshot(startTime,builder);
            builder = null;
        } else {
            SamplesInputStream stream = seek(startIndex);
            StackTraceSnapshotBuilder _builder = new StackTraceSnapshotBuilder();
            long _startTime = 0;

            for (int i = startIndex; i <= endIndex; i++) {
                LoadedSnapshot.ThreadsSample _sample = stream.readSample();
                if (_startTime == 0) {
                    _startTime = _sample.getTime();
                }
                _builder.addStacktrace(_sample.getTinfos(),_sample.getTime());
            }
            stream.close();
            stream = null;
            snapshot = createSnapshot(_startTime, _builder);
        }
        return snapshot;
    }

    private LoadedSnapshot createSnapshot(final long startTime, final StackTraceSnapshotBuilder builder) throws IOException {
        CPUResultsSnapshot snapshot;
        try {
            snapshot = builder.createSnapshot(startTime);
        } catch (NoDataAvailableException ex) {
            throw new IOException(ex);
        }
        return new LoadedSnapshot(snapshot, ProfilingSettingsPresets.createCPUPreset(), null, null);
    }

    private SamplesInputStream seek(final int sampleIndex) throws IOException {
        SamplesInputStream stream = new SamplesInputStream(npssFile.getInputStream());
//        ThreadsSample sample;

        for (int i = 0; i < sampleIndex; i++) {
            stream.readSample();
        }
        return stream;
    }

    private void getSample(final int sampleIndex) throws IllegalArgumentException, IOException {
        if (currentIndex > sampleIndex || currentIndex+1 < sampleIndex ) {
            throw new IllegalArgumentException("current sample "+currentIndex+" requestd sample "+sampleIndex);
        }
        if (currentIndex+1 == sampleIndex) {
            currentIndex++;
            sample = samplesStream.readSample();
            if (sampleIndex == getSamplesCount()-1) {
                samplesStream.close();
                samplesStream = null;
            }
        }
    }

    private void printThreads(final StringBuilder sb, ThreadInfo[] threads) {
        sb.append("<pre>"); // NOI18N
        for (ThreadInfo thread : threads) {
            if (thread != null) {
                print16Thread(sb, thread);
            }
        }
        sb.append("</pre>"); // NOI18N
    }

    private void print16Thread(final StringBuilder sb, final ThreadInfo thread) {
        MonitorInfo[] monitors = thread.getLockedMonitors();
        sb.append("&nbsp;<b>");   // NOI18N
        sb.append("\"" + thread.getThreadName() + // NOI18N
                "\" - Thread t@" + thread.getThreadId() + "<br>");    // NOI18N
        sb.append("    java.lang.Thread.State: " + thread.getThreadState()); // NOI18N
        sb.append("</b><br>");   // NOI18N
        int index = 0;
        for (StackTraceElement st : thread.getStackTrace()) {
            LockInfo lock = thread.getLockInfo();
            String stackElementText = htmlize(st.toString());
            String lockOwner = thread.getLockOwnerName();
            String className = st.getClassName();
            String method = st.getMethodName();
            int lineNo = st.getLineNumber();
            String stackUrl = OPEN_THREADS_URL+className+"|"+method+"|"+lineNo; // NOI18N
            String stackElHref = "<a href=\""+stackUrl+"\">"+stackElementText+"</a>";    // NOI18N

            sb.append("\tat " + stackElHref + "<br>");    // NOI18N
            if (index == 0) {
                if ("java.lang.Object".equals(st.getClassName()) &&     // NOI18N
                        "wait".equals(st.getMethodName())) {                // NOI18N
                    if (lock != null) {
                        sb.append("\t- waiting on ");    // NOI18N
                        printLock(sb,lock);
                        sb.append("<br>");    // NOI18N
                    }
                } else if (lock != null) {
                    if (lockOwner == null) {
                        sb.append("\t- parking to wait for ");      // NOI18N
                        printLock(sb,lock);
                        sb.append("<br>");            // NOI18N
                    } else {
                        sb.append("\t- waiting to lock ");      // NOI18N
                        printLock(sb,lock);
                        sb.append(" owned by \""+lockOwner+"\" t@"+thread.getLockOwnerId()+"<br>");   // NOI18N
                    }
                }
            }
            printMonitors(sb, monitors, index);
            index++;
        }
        StringBuilder jnisb = new StringBuilder();
        printMonitors(jnisb, monitors, -1);
        if (jnisb.length() > 0) {
            sb.append("   JNI locked monitors:<br>");
            sb.append(jnisb);
        }
        LockInfo[] synchronizers = thread.getLockedSynchronizers();
        if (synchronizers != null) {
            sb.append("<br>   Locked ownable synchronizers:");    // NOI18N
            if (synchronizers.length == 0) {
                sb.append("<br>\t- None\n");  // NOI18N
            } else {
                for (LockInfo li : synchronizers) {
                    sb.append("<br>\t- locked ");         // NOI18N
                    printLock(sb,li);
                    sb.append("<br>");  // NOI18N
                }
            }
        }
        sb.append("<br>");
    }

    private void printMonitors(final StringBuilder sb, final MonitorInfo[] monitors, final int index) {
        if (monitors != null) {
            for (MonitorInfo mi : monitors) {
                if (mi.getLockedStackDepth() == index) {
                    sb.append("\t- locked ");   // NOI18N
                    printLock(sb,mi);
                    sb.append("<br>");    // NOI18N
                }
            }
        }
    }

    private void printLock(StringBuilder sb,LockInfo lock) {
        String id = Integer.toHexString(lock.getIdentityHashCode());
        String className = lock.getClassName();

        sb.append("&lt;"+id+"&gt; (a "+className+")");       // NOI18N
    }
    
    private static String htmlize(String value) {
            return value.replace(">", "&gt;").replace("<", "&lt;");     // NOI18N
    }

    private void initSamples() throws IOException {
        SamplesInputStream stream = new SamplesInputStream(npssFile.getInputStream());
        int samplesGuess = (int)(npssFile.getSize()/130);
        ProgressHandle ph = ProgressHandleFactory.createSystemHandle("Computing snapshot samples");
        ph.start(samplesGuess);
        
        for(ThreadsSample s = stream.readSample(); s != null; s = stream.readSample()) {
            samples++;
            lastTimestamp = s.getTime();
            if (samples < samplesGuess) {
                ph.progress(samples);
            }
        }
        ph.finish();
    }
}

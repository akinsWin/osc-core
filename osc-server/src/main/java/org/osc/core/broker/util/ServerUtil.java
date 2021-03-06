/*******************************************************************************
 * Copyright (c) Intel Corporation
 * Copyright (c) 2017
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.osc.core.broker.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.time.DurationFormatUtils;
import org.osc.core.broker.rest.client.RestBaseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerUtil {
    private static final Logger log = LoggerFactory.getLogger(ServerUtil.class);

    private static final Long ISC_DISC_SPACE_THRESHOLD = 5L;
    private static String serverIP = "";

    public static long getUptime() {
        RuntimeMXBean rb = ManagementFactory.getRuntimeMXBean();
        return rb.getUptime();
    }

    public static String uptimeToString() {
        return uptimeToString(getUptime());
    }

    public static String uptimeToString(long uptime) {
        return DurationFormatUtils.formatDuration(uptime, "d 'Days' H 'Hours' m 'Minutes' s 'Seconds'\n");
    }

    /**
     * @return returns available disc space in GB
     * @throws IOException
     *
     */
    public static Long getUsableDiscSpaceInGB() throws IOException {
        Path path = Paths.get(System.getProperty("user.dir"));

        //Retrieve the mounted file system on which vmidc files are stored
        FileStore store = Files.getFileStore(path);

        return store.getUsableSpace() / 1024 / 1024 / 1024;
    }

    public static boolean isEnoughSpace() throws IOException {
        return getUsableDiscSpaceInGB() >= ISC_DISC_SPACE_THRESHOLD;
    }

    public static String getCurrentPid() {
        return ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
    }

    public static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os.contains("Windows");
    }

    public static void stopServerProcess(String pidFile) {

        log.info("Server process stop requested.");

        try {
            Integer pid = ServerUtil.readPIDFromFile(pidFile);
            terminateProcessByPid(pid);
        } catch (CorruptedPidException e) {
            System.out.println("Couldn't find PID file (" + pidFile + ") or Loaded PID is corrupted. Server process is not running");
        }

    }

    public static void terminateProcessByPid(Integer pid) {
        System.out.println("Terminating process (pid:" + pid + ")...");
        log.info("Kill process PID=" + pid);
        execWithLog("kill " + pid);
    }

    public static void writePIDToFile(String filename) {
        writePIDToFile(filename, getPid());
    }

    public static void writePIDToFile(String filename, String pid) {
        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new FileWriter(filename));
            out.write(pid);
        } catch (IOException e) {
            log.error("Fail to write process id to file", e);
        } finally {
            IOUtils.closeQuietly(out);
        }
    }

    static Integer readPIDFromFile(String filename) throws CorruptedPidException {
        if (!new File(filename).exists()) {
            return null;
        }

        String PID = null;
        try (BufferedReader in = new BufferedReader(new FileReader(filename))){
            PID = in.readLine();
        } catch (IOException e) {
            log.debug("Fail to read process id from file", e);
        }
        return (PID != null) ? filterPidNumber(PID) : null;
    }

    private static Integer filterPidNumber(String pid) throws CorruptedPidException {
        Integer pidNumber;
        try {
            pidNumber = Integer.valueOf(pid);
            if(pidNumber < Integer.MAX_VALUE && pidNumber > Integer.MIN_VALUE){
                return pidNumber;
            } else {
                throw new CorruptedPidException();
            }
        } catch (NumberFormatException e) {
            throw new CorruptedPidException(e);
        }
    }

    private static String getPid() {
        String nameOfRunningVM = ManagementFactory.getRuntimeMXBean().getName();
        int p = nameOfRunningVM.indexOf('@');
        return nameOfRunningVM.substring(0, p);
    }

    public static String getPidByProcessName(String processName) {
        try {
            String psArg = "";
            if (isWindows()) {
                psArg += "-W";
            }

            Process p = java.lang.Runtime.getRuntime().exec("ps " + psArg);
            int exitVal = p.waitFor();
            log.info("ps process terminated with exit code " + exitVal);

            // Examine ps output
            try(InputStream inputStream = p.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))){
                String s;
                while ((s = reader.readLine()) != null) {
                    s = s.trim();
                    log.debug(s);
                    if (s.endsWith(processName)) {
                        String pid = s.split(" +", -3)[0];
                        log.info("Found PID for " + processName + ": " + pid);
                        return pid;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Fail to find process PID for '" + processName + "'", e);
        }

        return null;
    }

    /**
     * Delete the PID file if the pid file is owned by the current process. If the pid file does not belong
     * to currrent process, we should not delete it.
     *
     * @param filename
     */
    public static void deletePidFileIfOwned(String filename) {
        Integer filePid;

        try {
            filePid = readPIDFromFile(filename);
        } catch (CorruptedPidException e) {
            log.error("PID number is corrupted", e);
            return;
        }

        if (filePid != null) {
            String PID = String.valueOf(filePid);
            if (PID.equals(getCurrentPid())) {
                log.info("Pid from File " + filename + " Matches current pid. Deleting the PID.");
                new File(filename).delete();
            } else {
                log.info("Pid: " + filePid + " Does not match current Pid: " + getCurrentPid()
                + " Assuming upgrade, Not deleting pid file:" + filename);
            }
        }
    }

    public interface ServerServiceChecker {
        boolean isRunning();
    }

    public static boolean startAgentProcess() {
        return startAgentProcess(0, null);
    }

    public static boolean startAgentProcess(int waitTimeout, ServerServiceChecker serverServiceChecker) {
        return startAgentProcess(new String[] { "-server", "-Xmx2048m" }, "vmiDCAgent.jar", waitTimeout,
                serverServiceChecker);
    }

    private static boolean startAgentProcess(String[] jvmArgs, String jarFile, int waitTimeout,
            ServerServiceChecker serverServiceChecker) {
        String javaHome = System.getProperty("java.home");
        File javaExecutable = new File(javaHome);
        javaExecutable = new File(javaExecutable, "bin");
        javaExecutable = new File(javaExecutable, "java");
        String javaExecutablePath = javaExecutable.getAbsolutePath();

        if (isWindows()) {
            javaExecutablePath = "\"" + javaExecutablePath + "\"";
        }

        ProcessBuilder builder = new ProcessBuilder(javaExecutablePath);
        List<String> cmd = builder.command();

        for (String arg : jvmArgs) {
            cmd.add(arg);
        }
        cmd.add("-Djava.security.egd=file:/dev/./urandom");
        cmd.add("-jar");
        cmd.add(jarFile);
        return ServerUtil.startServerProcess(builder, waitTimeout, serverServiceChecker, false);
    }

    public static boolean startServerProcess() {
        return startServerProcess(0, null);
    }

    public static boolean startServerProcess(int waitTimeout, ServerServiceChecker serverServiceChecker) {
        return ServerUtil.startServerProcess(waitTimeout, serverServiceChecker, false);
    }

    public static boolean startServerProcess(int waitTimeout, ServerServiceChecker serverServiceChecker,
            boolean isRebootAfterFailure) {
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "vmidc.sh", "--console");
        return ServerUtil.startServerProcess(builder, waitTimeout, serverServiceChecker, isRebootAfterFailure);
    }

    private static boolean startServerProcess(ProcessBuilder builder, int waitTimeout,
            ServerServiceChecker serverServiceChecker, boolean isRebootAfterFailure) {
        boolean successStarted = false;
        Process process = null;

        try {
            log.info("Launching " + builder.command() + ".");
            process = builder.start();

            if (serverServiceChecker != null) {
                // Test new process communication connectivity
                for (int retry = waitTimeout / 500; retry > 0; --retry) {
                    Thread.sleep(500);
                    if (serverServiceChecker.isRunning()) {
                        successStarted = true;
                        break;
                    }
                }
            } else {
                try {
                    Thread.sleep(waitTimeout);
                    process.exitValue();
                } catch (IllegalThreadStateException e) {
                    // process still running
                    successStarted = true;
                }
            }

            if (!successStarted) {
                log.error("Process fail to start successfully. Exit code: " + process.exitValue());
            }
        } catch (Exception ex) {
            log.error("Fail to start process.", ex);
        }

        if (successStarted) {
            log.info(" started successfully.");
        } else {
            if (process != null) {
                process.destroy();
            }
            log.info(" failed to start. Timeout exceeded.");

            // reboot if needed
            if (isRebootAfterFailure) {
                log.info(" reboot server");
                ServerUtil.execWithLog("/sbin/reboot");
            }
        }

        return successStarted;
    }

    public static int execWithLog(String cmd) {
        return execWithLog(new String[] { cmd });
    }

    public static int execWithLog(String[] cmd) {
        return execWithLog(cmd, true);
    }

    public static int execWithLog(String cmd, List<String> lines) {
        return execWithLog(new String[] { cmd }, lines);
    }

    public static int execWithLog(String[] cmd, List<String> lines) {
        return execWithLog(cmd, lines, true);
    }

    public static int execWithLog(String[] cmd, boolean logCommandLine) {
        List<String> lines = new ArrayList<String>();
        return execWithLog(cmd, lines, logCommandLine);
    }

    public static int execWithLog(String[] cmd, List<String> lines, boolean logCommandLine) {
        int rc = execWithLines(cmd, lines, logCommandLine);
        for (String line : lines) {
            log.info(line);
        }
        return rc;
    }

    public static int execWithLines(String cmd, List<String> outLines) {
        return execWithLines(new String[] { cmd }, outLines, true);
    }

    public static int execWithLines(String[] cmd, List<String> outLines, boolean logCommand) {
        if (logCommand) {
            StringBuilder sb = new StringBuilder();
            if (cmd.length == 1) {
                sb.append(cmd[0]);
            } else {
                for (String str : cmd) {
                    sb.append("\n").append(str);
                }
            }

            log.info("Execute cmd: " + sb.toString());
        }

        Process process = null;
        BufferedReader kbdInput = null;
        try {
            if (cmd.length == 1) {
                process = Runtime.getRuntime().exec(cmd[0]);
            } else {
                process = Runtime.getRuntime().exec(cmd);
            }
            kbdInput = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = kbdInput.readLine()) != null) {
                if (outLines != null) {
                    outLines.add(line);
                }
            }

            process.waitFor();
            int exitCode = process.exitValue();
            if (logCommand) {
                log.info("Execute cmd '" + cmd[0] + "' completed with exit code " + exitCode);
            } else {
                log.info("Execution completed with exit code " + exitCode);
            }

            return exitCode;

        } catch (Exception ex) {
            if (process != null) {
                process.destroy();
            }
            if (logCommand) {
                log.error("Error executing cmd: " + cmd[0], ex);
            } else {
                log.error("Error executing cmd.", ex);
            }
        } finally {
            IOUtils.closeQuietly(kbdInput);
        }

        return -1;
    }

    public static String getServerIP() {
        return serverIP.isEmpty() ? RestBaseClient.getLocalHostIp() : serverIP;
    }

    public static void setServerIP(String serverIP) {
        if (serverIP != null) {
            ServerUtil.serverIP = serverIP;
        } else {
            // if null or empty then set it to empty string
            ServerUtil.serverIP = "";
        }
    }

    public interface TimeChangeCommand {
        void execute(long timeDifference);
    }

    /**
     * Returns a thread which executes the provided command if the system clock moves forward or backwards by the
     * threshold specified.
     *
     * @param timeChangeCommand
     *            the command to run when system clock is changed
     * @param timeChangeThreshold
     *            the time difference in millisecs that the caller can adjust for, if the time changes by
     *            more than this amount, the time change command will be executed.
     * @param sleepInterval
     *            the amount of time the thread sleeps before it checks to see if system clock was changed
     *
     * @return the thread which the caller can decide to start
     */
    public static Thread getTimeMonitorThread(final TimeChangeCommand timeChangeCommand, final long timeChangeThreshold,
            final long sleepInterval) {
        Runnable timeMonitorThread = new Runnable() {

            private long previousTime = new Date().getTime();

            @Override
            public void run() {
                while (true) {
                    long currentTime = new Date().getTime();
                    long timeDifference = currentTime - (this.previousTime + sleepInterval);
                    if (timeDifference >= timeChangeThreshold || timeDifference <= -timeChangeThreshold) {
                        String timeDifferenceString = DurationFormatUtils.formatDuration(Math.abs(timeDifference),
                                "d 'Days' H 'Hours' m 'Minutes' s 'Seconds'");
                        log.warn(String.format(
                                "System Time change detected. Time shift: %s Current Time: %s  Previous Time: %s",
                                timeDifferenceString, new Date(currentTime), new Date(this.previousTime)));
                        timeChangeCommand.execute(timeDifference);
                    }
                    this.previousTime = currentTime;

                    try {
                        Thread.sleep(sleepInterval);
                    } catch (InterruptedException e) {
                        log.info("Time monitor thread interrupted. Ignoring...");
                    }
                }
            }
        };

        return new Thread(timeMonitorThread, "Time-Monitor-Thread");
    }

    /**
     * Method cleans old processes either graceful or forceful
     * @param oldPid
     * @throws InterruptedException
     */
    static void killProcess(String oldPid) throws InterruptedException {
        // Making sure that old process also went away.
        int retries = 10; // 10 x 500ms = 5 seconds.
        boolean pidFound = false;
        while (retries > 0) {
            List<String> lines = new ArrayList<>();
            ServerUtil.execWithLines("ps " + oldPid, lines);
            pidFound = false;
            for (String line : lines) {
                if (line.contains(oldPid)) {
                    pidFound = true;
                    break;
                }
            }
            if (!pidFound) {
                break;
            }
            log.info("Old process (" + oldPid + ") is still running. Retry (" + retries
                    + ") wait for graceful termination.");
            retries--;
            Thread.sleep(500);
        }
        // If process is till there, we'll force termination.
        if (pidFound) {
            log.warn("Old process (" + oldPid + ") is still running. Triggering forceful termination.");
            ServerUtil.execWithLog("kill -9 " + oldPid);
        }
    }

}

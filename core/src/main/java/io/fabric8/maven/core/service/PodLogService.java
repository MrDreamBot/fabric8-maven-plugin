package io.fabric8.maven.core.service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import io.fabric8.kubernetes.api.KubernetesHelper;
import io.fabric8.kubernetes.api.PodStatusType;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.DoneablePod;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.maven.core.util.KubernetesClientUtil;
import io.fabric8.maven.core.util.KubernetesResourceUtil;
import io.fabric8.maven.docker.util.Logger;
import io.fabric8.utils.Strings;

import static io.fabric8.kubernetes.api.KubernetesHelper.getName;
import static io.fabric8.kubernetes.api.KubernetesHelper.getPodStatus;
import static io.fabric8.kubernetes.api.KubernetesHelper.isPodRunning;
import static io.fabric8.maven.core.util.KubernetesClientUtil.deleteEntities;
import static io.fabric8.maven.core.util.KubernetesClientUtil.getPodStatusDescription;
import static io.fabric8.maven.core.util.KubernetesClientUtil.getPodStatusMessagePostfix;
import static io.fabric8.maven.core.util.KubernetesClientUtil.resizeApp;
import static io.fabric8.maven.core.util.KubernetesClientUtil.withSelector;
import static io.fabric8.maven.core.util.KubernetesResourceUtil.getPodLabelSelector;

/**
 * Prints to the console the output of the pods.
 */
public class PodLogService {

    public static final String OPERATION_UNDEPLOY = "undeploy";
    public static final String OPERATION_STOP = "stop";


    private PodLogServiceContext context;
    private Logger log;

    private Watch podWatcher;
    private LogWatch logWatcher;
    private Map<String, Pod> addedPods = new ConcurrentHashMap<>();
    private CountDownLatch terminateLatch = new CountDownLatch(1);
    private String watchingPodName;
    private String newestPodName;
    private CountDownLatch logWatchTerminateLatch;

    public PodLogService(PodLogServiceContext context) {
        this.context = context;
        this.log = context.getLog();
    }

    public void tailAppPodsLogs(final KubernetesClient kubernetes, final String namespace, final Set<HasMetadata> entities,
                                boolean watchAddedPodsOnly, String onExitOperation, boolean followLog,
                                Date ignorePodsOlderThan, boolean waitInCurrentThread) {
        LabelSelector selector = null;
        for (HasMetadata entity : entities) {
            selector = getPodLabelSelector(entity);
            if (selector != null) {
                break;
            }
        }

        if (selector != null) {
            String ctrlCMessage = "stop tailing the log";
            if (Strings.isNotBlank(onExitOperation)) {
                final String onExitOperationLower = onExitOperation.toLowerCase().trim();
                if (onExitOperationLower.equals(OPERATION_UNDEPLOY)) {
                    ctrlCMessage = "undeploy the app";
                } else if (onExitOperationLower.equals(OPERATION_STOP)) {
                    ctrlCMessage = "scale down the app and stop tailing the log";
                } else {
                    log.warn("Unknown on-exit command: `%s`", onExitOperationLower);
                }
                resizeApp(kubernetes, namespace, entities, 1, log);
                Runtime.getRuntime().addShutdownHook(new Thread("pod log service shutdown hook") {
                    @Override
                    public void run() {
                        if (onExitOperationLower.equals(OPERATION_UNDEPLOY)) {
                            log.info("Undeploying the app:");
                            deleteEntities(kubernetes, namespace, entities, context.getS2iBuildNameSuffix(), log);
                        } else if (onExitOperationLower.equals(OPERATION_STOP)) {
                            log.info("Stopping the app:");
                            resizeApp(kubernetes, namespace, entities, 0, log);
                        }
                        if (podWatcher != null) {
                            podWatcher.close();
                        }
                        closeLogWatcher();
                    }
                });
            }
            waitAndLogPods(kubernetes, namespace, selector, watchAddedPodsOnly, ctrlCMessage, followLog, ignorePodsOlderThan, waitInCurrentThread);
        } else {
            log.warn("No selector in deployment so cannot watch pods!");
        }
    }

    private void waitAndLogPods(final KubernetesClient kubernetes, final String namespace, LabelSelector selector, final boolean watchAddedPodsOnly, final String ctrlCMessage, final boolean
            followLog, Date ignorePodsOlderThan, boolean waitInCurrentThread) {
        FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> pods = withSelector(kubernetes.pods().inNamespace(namespace), selector, log);
        if (context.getPodName() != null) {
            log.info("Watching pod with selector %s, and name %s waiting for a running pod...", selector, context.getPodName());
            pods = pods.withField("metadata.name", context.getPodName());
        } else {
            log.info("Watching pods with selector %s waiting for a running pod...", selector);
        }
        Pod latestPod = null;
        boolean runningPod = false;
        PodList list = pods.list();
        if (list != null) {
            List<Pod> items = list.getItems();
            if (items != null) {
                for (Pod pod : items) {
                    PodStatusType status = getPodStatus(pod);
                    switch (status) {
                    case WAIT:
                    case OK:
                        if (latestPod == null || KubernetesResourceUtil.isNewerResource(pod, latestPod)) {
                            if (ignorePodsOlderThan != null) {
                                Date podCreateTime = KubernetesResourceUtil.getCreationTimestamp(pod);
                                if (podCreateTime != null && podCreateTime.compareTo(ignorePodsOlderThan) > 0) {
                                    latestPod = pod;
                                }
                            } else {
                                latestPod = pod;
                            }
                        }
                        runningPod = true;
                        break;

                    case ERROR:
                    default:
                        continue;
                    }
                }
            }
        }
        // we may have missed the ADDED event so lets simulate one
        if (latestPod != null) {
            onPod(Watcher.Action.ADDED, latestPod, kubernetes, namespace, ctrlCMessage, followLog);
        }
        if (!watchAddedPodsOnly) {
            // lets watch the current pods then watch for changes
            if (!runningPod) {
                log.warn("No pod is running yet. Are you sure you deployed your app via `fabric8:deploy`?");
                log.warn("Or did you stop it via `fabric8:stop`? If so try running the `fabric8:start` goal");
            }
        }
        podWatcher = pods.watch(new Watcher<Pod>() {
            @Override
            public void eventReceived(Action action, Pod pod) {
                onPod(action, pod, kubernetes, namespace, ctrlCMessage, followLog);
            }

            @Override
            public void onClose(KubernetesClientException e) {
                // ignore

            }
        });

        if (waitInCurrentThread) {
            while (terminateLatch.getCount() > 0) {
                try {
                    terminateLatch.await();
                } catch (InterruptedException e) {
                    // ignore
                }
            }
        }
    }

    private void onPod(Watcher.Action action, Pod pod, KubernetesClient kubernetes, String namespace, String ctrlCMessage, boolean followLog) {
        String name = getName(pod);
        if (action.equals(Watcher.Action.DELETED)) {
            addedPods.remove(name);
            if (Objects.equals(watchingPodName, name)) {
                watchingPodName = null;
                addedPods.remove(name);
            }
        } else {
            if (action.equals(Watcher.Action.ADDED) || action.equals(Watcher.Action.MODIFIED)) {
                addedPods.put(name, pod);
            }
        }

        Pod watchPod = KubernetesResourceUtil.getNewestPod(addedPods.values());
        newestPodName = getName(watchPod);

        Logger statusLog = Objects.equals(name, newestPodName) ? context.getNewPodLog() : context.getOldPodLog();
        if (!action.equals(Watcher.Action.MODIFIED) || watchingPodName == null || !watchingPodName.equals(name)) {
            statusLog.info("%s status: %s%s", name, getPodStatusDescription(pod), getPodStatusMessagePostfix(action));
        }

        if (watchPod != null && isPodRunning(watchPod)) {
            watchLogOfPodName(kubernetes, namespace, ctrlCMessage, followLog, watchPod, getName(watchPod));
        }
    }

    private void watchLogOfPodName(KubernetesClient kubernetes, String namespace, String ctrlCMessage, boolean followLog, Pod pod, String name) {
        if (watchingPodName == null || !watchingPodName.equals(name)) {
            if (logWatcher != null) {
                log.info("Closing log watcher for %s as now watching %s", watchingPodName, name);
                closeLogWatcher();
            }
            PodResource<Pod, DoneablePod> podResource = kubernetes.pods().inNamespace(namespace).withName(name);
            List<Container> containers = KubernetesHelper.getContainers(pod);
            String containerName = null;
            if (followLog) {
                watchingPodName = name;
                logWatchTerminateLatch = new CountDownLatch(1);
                if (containers.size() < 2) {
                    logWatcher = podResource.watchLog();
                } else {
                    containerName = getLogContainerName(containers);
                    logWatcher = podResource.inContainer(containerName).watchLog();
                }
                watchLog(logWatcher, name, "Failed to read log of pod " + name + ".", ctrlCMessage, containerName);
            } else {
                String logText;
                if (containers.size() < 2) {
                    logText = podResource.getLog();
                } else {
                    containerName = getLogContainerName(containers);
                    logText = podResource.inContainer(containerName).getLog();
                }
                if (logText != null) {
                    String[] lines = logText.split("\n");
                    log.info("Log of pod: %s%s", name, containerNameMessage(containerName));
                    log.info("");
                    for (String line : lines) {
                        log.info("[[s]]%s", line);
                    }
                }
                terminateLatch.countDown();
            }
        }
    }

    private String getLogContainerName(List<Container> containers) {
        if (Strings.isNotBlank(context.getLogContainerName())) {
            for (Container container : containers) {
                if (Objects.equals(context.getLogContainerName(), container.getName())) {
                    return context.getLogContainerName();
                }
            }
            log.error("log container name %s does not exist in pod!! Did you set the correct value for property 'fabric8.log.container'", context.getLogContainerName());
        }
        return containers.get(0).getName();
    }

    private void closeLogWatcher() {
        if (logWatcher != null) {
            logWatcher.close();
            logWatcher = null;
        }
        if (logWatchTerminateLatch != null) {
            logWatchTerminateLatch.countDown();
        }
    }

    private void watchLog(final LogWatch logWatcher, String podName, final String failureMessage, String ctrlCMessage, String containerName) {
        context.getNewPodLog().info("Tailing log of pod: " + podName + containerNameMessage(containerName));
        context.getNewPodLog().info("Press Ctrl-C to " + ctrlCMessage);
        context.getNewPodLog().info("");

        KubernetesClientUtil.printLogsAsync(logWatcher, failureMessage, this.logWatchTerminateLatch, log);
    }

    private String containerNameMessage(String containerName) {
        if (Strings.isNotBlank(containerName)) {
            return " container: " + containerName;
        }
        return "";
    }

    // =======================================

    public static class PodLogServiceContext {

        private Logger log;
        private Logger newPodLog;
        private Logger oldPodLog;

        private String logContainerName;
        private String podName;

        private String s2iBuildNameSuffix = "-s2i";

        public PodLogServiceContext() {
        }

        public Logger getLog() {
            return log;
        }

        public Logger getNewPodLog() {
            return newPodLog;
        }

        public Logger getOldPodLog() {
            return oldPodLog;
        }

        public String getLogContainerName() {
            return logContainerName;
        }

        public String getPodName() {
            return podName;
        }

        public String getS2iBuildNameSuffix() {
            return s2iBuildNameSuffix;
        }

        public static class Builder {

            private PodLogServiceContext context;

            public Builder() {
                this.context = new PodLogServiceContext();
            }

            public Builder(PodLogServiceContext context) {
                this.context = context;
            }

            public Builder log(Logger log) {
                context.log = log;
                return this;
            }

            public Builder newPodLog(Logger newPodLog) {
                context.newPodLog = newPodLog;
                return this;
            }

            public Builder oldPodLog(Logger oldPodLog) {
                context.oldPodLog = oldPodLog;
                return this;
            }

            public Builder logContainerName(String logContainerName) {
                context.logContainerName = logContainerName;
                return this;
            }

            public Builder podName(String podName) {
                context.podName = podName;
                return this;
            }

            public Builder s2iBuildNameSuffix(String s2iBuildNameSuffix) {
                context.s2iBuildNameSuffix = s2iBuildNameSuffix;
                return this;
            }

            public PodLogServiceContext build() {
                return context;
            }

        }

    }

}

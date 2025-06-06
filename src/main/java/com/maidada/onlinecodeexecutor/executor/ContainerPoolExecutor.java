package com.maidada.onlinecodeexecutor.executor;

import cn.hutool.core.io.FileUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * @author wulinxuan
 * @date 2025-06-06 11:11
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "codesandbox.pool")
public class ContainerPoolExecutor {

    private Integer corePoolSize = Runtime.getRuntime().availableProcessors() * 10;

    private Integer maxPoolSize = Runtime.getRuntime().availableProcessors() * 20;

    private Integer waitQueueSize = 200;

    private Integer keepAliveTime = 5;

    private TimeUnit timeUnit = TimeUnit.SECONDS;

    @Resource
    private DockerDao dockerDao;

    /**
     * 容器池
     */
    private BlockingQueue<ContainerInfo> containerPool;

    /**
     * 排队数量
     */
    private AtomicInteger blockingThreadCount;

    /**
     * 可扩展数量
     */
    private AtomicInteger expandCount;

    @PostConstruct
    private void init() {
        // 初始化容器池
        this.containerPool = new LinkedBlockingDeque<>(maxPoolSize);
        this.blockingThreadCount = new AtomicInteger(0);
        this.expandCount = new AtomicInteger(maxPoolSize - corePoolSize);

        // 初始化池中数据
        for (int i = 0; i < corePoolSize; i++) {
            createNewPool();
        }

        // 定制清理过期容器
        ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1);
        scheduleExpirationCleanup(scheduledExecutorService);
    }


    private void createNewPool() {
        // 创建临时目录
        String usrDir = System.getProperty("sys.dir");
        String codePathName = usrDir + File.separator + "tempCode";

        // 新的目录
        UUID uuid = UUID.randomUUID();
        codePathName += File.separator + uuid;

        File codePath = new File(codePathName);
        if (!codePath.exists()) {
            boolean mkdirs = codePath.mkdirs();
            if (!mkdirs) {
                log.error("创建临时目录失败");
            }
        }

        ContainerInfo containerInfo = dockerDao.startContainer(codePathName);
        boolean result = containerPool.offer(containerInfo);
        if (!result) {
            log.error("初始化容器失败，超出容器池数量: {}", containerPool.size());
        }
    }

    private boolean expandPool() {
        log.info("触发扩容");
        if (expandCount.decrementAndGet() < 0) {
            log.error("无法扩容");
            return false;
        }
        log.info("扩容了");
        createNewPool();
        return true;
    }

    public ContainerInfo getContainer() throws InterruptedException {
        if (containerPool.isEmpty()) {
            try {
                if (blockingThreadCount.incrementAndGet() >= waitQueueSize && !expandPool()) {
                    log.error("扩容失败");
                    return null;
                }
                log.info("没有数据，等待数据，当前等待长度：{}", blockingThreadCount.get());
                // 阻塞等待可用的数据
                return containerPool.take();
            } finally {
                blockingThreadCount.decrementAndGet();
            }
        }
        return containerPool.take();
    }

    private void recordError(ContainerInfo containerInfo) {
        if (containerInfo != null) {
            containerInfo.setErrorCount(containerInfo.getErrorCount() + 1);
        }
    }

    private void cleanExpiredContainers() {
        long currentTime = System.currentTimeMillis();
        int needCleanCount = containerPool.size() - corePoolSize;
        if (needCleanCount <= 0) {
            return;
        }

        // 处理过期容器
        containerPool.stream().filter(containerInfo -> {
            long lastActivityTime = containerInfo.getLastActivityTime();
            lastActivityTime += timeUnit.toMillis(keepAliveTime);
            return lastActivityTime < currentTime;
        }).forEach(containerInfo -> {
            boolean remove = containerPool.remove(containerInfo);
            if (remove) {
                String containerId = containerInfo.getContainerId();
                expandCount.incrementAndGet();
                if (StringUtils.isNotBlank(containerId)) {
                    dockerDao.cleanContainer(containerId);
                }
            }
        });
        log.info("当前容器大小: " + containerPool.size());
    }

    private void scheduleExpirationCleanup(ScheduledExecutorService scheduledExecutorService) {
        scheduledExecutorService.scheduleAtFixedRate(() -> {
            log.info("定时清理过期容器...");
            cleanExpiredContainers();
        }, 0, 20, TimeUnit.SECONDS);
    }

    public ExecuteResponse run(Function<ContainerInfo, ExecuteResponse> function) {
        ContainerInfo containerInfo = null;
        try {
            containerInfo = getContainer();
            if (containerInfo == null) {
                return ExecuteResponse.builder().success(false).msg("不能处理了").build();
            }

            log.info("有数据，拿到了: {}", containerInfo);
            ExecuteResponse executeResponse = function.apply(containerInfo);
            if (!executeResponse.isSuccess()) {
                recordError(containerInfo);
            }
            return executeResponse;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            if (containerInfo != null) {
                ContainerInfo finalContainerInfo = containerInfo;
                dockerDao.executeCmd(containerInfo.getContainerId(), new String[]{"rm", "-rf", "/box"});
                CompletableFuture.runAsync(() -> {
                    try {
                        // 更新时间
                        log.info("操作完了，还回去");
                        String codePathName = finalContainerInfo.getCodePathName();
                        FileUtil.del(codePathName);
                        // 错误超过 3 次就不放回，重新运行一个
                        if (finalContainerInfo.getErrorCount() > 3) {
                            CompletableFuture.runAsync(() -> {
                                dockerDao.cleanContainer(finalContainerInfo.getContainerId());
                                this.createNewPool();
                            });
                            return;
                        }
                        finalContainerInfo.setLastActivityTime(System.currentTimeMillis());
                        containerPool.put(finalContainerInfo);
                        log.info("容器池还剩: {}", containerPool.size());
                    } catch (InterruptedException e) {
                        log.error("无法放入");
                    }
                });
            }
        }
    }
}

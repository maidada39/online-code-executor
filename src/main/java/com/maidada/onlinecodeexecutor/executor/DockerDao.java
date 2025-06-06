package com.maidada.onlinecodeexecutor.executor;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.DockerClientBuilder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * @author wulinxuan
 * @date 2025/6/5 0:05
 */
@Data
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "codesandbox.config")
public class DockerDao {

    private static final DockerClient DOCKER_CLIENT = DockerClientBuilder.getInstance().build();

    private String imageName = "codesandbox:latest";

    private long memory = 50 * 1024 * 1024L;

    private long memorySwap = 0;

    private long cpuCount = 1;

    private long timeout = 3;

    private TimeUnit timeUnit = TimeUnit.SECONDS;

    /**
     * 启动容器
     *
     * @param codeFile 代码文件
     * @return {@link String }
     */
    public ContainerInfo startContainer(String codeFile) {
        CreateContainerCmd containerCmd = DOCKER_CLIENT.createContainerCmd(this.imageName);

        // 基础配置
        HostConfig hostConfig = new HostConfig();
        hostConfig.withMemory(this.memory);
        hostConfig.withMemorySwap(this.memorySwap);
        hostConfig.withCpuCount(this.cpuCount);

        // 更多配置
        CreateContainerResponse execResponse = containerCmd
                .withHostConfig(hostConfig)
                .withNetworkDisabled(true)
                .withAttachStdin(true)
                .withAttachStderr(true)
                .withAttachStdout(true)
                .withTty(true)
                .exec();

        // 启动容器
        String containerId = execResponse.getId();
        DOCKER_CLIENT.startContainerCmd(containerId).exec();

        return ContainerInfo.builder()
                .containerId(containerId)
                .codePathName(codeFile)
                .lastActivityTime(System.currentTimeMillis())
                .build();
    }

    /**
     * 执行命令
     *
     * @param containerId 容器id
     * @param cmd         cmd
     * @return {@link ExecuteResponse }
     */
    public ExecuteResponse executeCmd(String containerId, String[] cmd) {
        // 正常返回信息
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        // 异常返回信息
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        // 结果
        final boolean[] result = {true};
        final boolean[] timeout = {true};
        try (ResultCallback.Adapter<Frame> frameAdapter = new ResultCallback.Adapter<Frame>() {
            @Override
            public void onComplete() {
                timeout[0] = false;
                super.onComplete();
            }

            @Override
            public void onNext(Frame frame) {
                StreamType streamType = frame.getStreamType();
                byte[] payload = frame.getPayload();
                if (StreamType.STDERR.equals(streamType)) {
                    try {
                        result[0] = false;
                        stderr.write(payload);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    try {
                        result[0] = true;
                        stdout.write(payload);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                super.onNext(frame);
            }

        }) {
            ExecCreateCmdResponse execCmdResponse = DOCKER_CLIENT.execCreateCmd(containerId)
                    .withCmd(cmd)
                    .withAttachStderr(true)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .exec();

            String execId = execCmdResponse.getId();
            DOCKER_CLIENT.execStartCmd(execId).exec(frameAdapter).awaitCompletion(3, TimeUnit.SECONDS);

            if (timeout[0]) {
                return ExecuteResponse.builder()
                        .success(false)
                        .errorMsg("执行超时")
                        .build();
            }

            return ExecuteResponse.builder()
                    .success(result[0])
                    .msg(stdout.toString())
                    .errorMsg(stderr.toString())
                    .build();
        } catch (IOException | InterruptedException e) {
            log.info("执行命令异常", e);
            return ExecuteResponse.builder()
                    .success(false)
                    .errorMsg(e.getMessage())
                    .build();
        }
    }

    public void copyFileToContainer(String containerId, String codeFile) {
        // 将代码复制到容器中
        DOCKER_CLIENT.copyArchiveToContainerCmd(containerId)
                .withHostResource(codeFile)
                .withRemotePath("/box")
                .exec();
    }

    /**
     * 清理文件和容器
     *
     * @param containerId 容器 ID
     */
    public void cleanContainer(String containerId) {
        // 关闭并删除容器
        DOCKER_CLIENT.stopContainerCmd(containerId).exec();
        DOCKER_CLIENT.removeContainerCmd(containerId).exec();
    }

}

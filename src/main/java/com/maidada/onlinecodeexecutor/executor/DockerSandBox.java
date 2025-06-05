package com.maidada.onlinecodeexecutor.executor;

import cn.hutool.core.io.FileUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.DockerClientBuilder;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * @author wulinxuan
 * @date 2025/6/5 0:05
 */
@Slf4j
public class DockerSandBox {

    private static final DockerClient DOCKER_CLIENT = DockerClientBuilder.getInstance().build();

    /**
     * 执行代码
     *
     * @param languageCmdEnum 编程语言枚举
     * @param code            代码
     * @return {@link ExecuteResponse}
     */
    public static ExecuteResponse execute(LanguageCmdEnum languageCmdEnum, String code) {

        // 写入文件
        String userDir = System.getProperty("user.dir");
        String language = languageCmdEnum.getLanguage();
        String globalCodePathName = userDir + File.separator + "tempCode" + File.separator + language;
        // 判断全局代码目录是否存在，没有则新建
        File globalCodePath = new File(globalCodePathName);
        if (!globalCodePath.exists()) {
            boolean mkdir = globalCodePath.mkdirs();
            if (!mkdir) {
                log.info("创建全局代码目录失败");
            }
        }

        // 把用户的代码隔离存放
        String userCodeParentPath = globalCodePathName + File.separator + UUID.randomUUID();
        String userCodePath = userCodeParentPath + File.separator + languageCmdEnum.getSaveFileName();
        FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);
        String containerId = createContainer(userCodePath);

        // 编译代码
        String[] compileCmd = languageCmdEnum.getCompileCmd();
        ExecuteResponse executeResponse;

        // 不为空则代表需要编译
        if (compileCmd != null) {
            executeResponse = executeCmd(containerId, compileCmd);

            log.info("编译完成...");
            // 编译错误
            if (!executeResponse.isSuccess()) {
                // 清除文件
                cleanFileAndContainer(userCodeParentPath, containerId);
                return executeResponse;
            }
        }
        executeResponse = executeCmd(containerId, languageCmdEnum.getRunCmd());
        log.info("运行完成...");

        // 清除文件
        cleanFileAndContainer(userCodeParentPath, containerId);
        return executeResponse;
    }


    /**
     * 创建容器
     *
     * @param codeFile 代码文件
     * @return {@link String }
     */
    private static String createContainer(String codeFile) {
        CreateContainerCmd containerCmd = DOCKER_CLIENT.createContainerCmd("codesandbox:latest");

        // 基础配置
        HostConfig hostConfig = new HostConfig();
        hostConfig.withMemory(50 * 1024 * 1024L);
        hostConfig.withMemorySwap(0L);
        hostConfig.withCpuCount(1L);

        // 更多配置
        CreateContainerResponse execResponse = containerCmd.withHostConfig(hostConfig)
                .withNetworkDisabled(true)
                .withAttachStdin(true)
                .withAttachStderr(true)
                .withAttachStdout(true)
                .withTty(true)
                .exec();

        // 启动容器
        String containerId = execResponse.getId();
        DOCKER_CLIENT.startContainerCmd(containerId).exec();

        // 将代码复制到容器中
        DOCKER_CLIENT.copyArchiveToContainerCmd(containerId)
                .withHostResource(codeFile)
                .withRemotePath("/box")
                .exec();

        return containerId;
    }

    /**
     * 执行命令
     *
     * @param containerId 容器id
     * @param cmd         cmd
     * @return {@link ExecuteResponse }
     */
    private static ExecuteResponse executeCmd(String containerId, String[] cmd) {
        // 正常返回信息
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        // 异常返回信息
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        // 结果
        final boolean[] result = {true};
        try (ResultCallback.Adapter<Frame> frameAdapter = new ResultCallback.Adapter<Frame>() {
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
            DOCKER_CLIENT.execStartCmd(execId).exec(frameAdapter).awaitCompletion();

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

    /**
     * 清理文件和容器
     *
     * @param userCodePath 用户代码路径
     * @param containerId  容器 ID
     */
    private static void cleanFileAndContainer(String userCodePath, String containerId) {
        // 清理临时目录
        FileUtil.del(userCodePath);

        // 关闭并删除容器
        DOCKER_CLIENT.stopContainerCmd(containerId).exec();
        DOCKER_CLIENT.removeContainerCmd(containerId).exec();
    }

}

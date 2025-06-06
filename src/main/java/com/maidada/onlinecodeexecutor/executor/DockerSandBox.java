package com.maidada.onlinecodeexecutor.executor;

import cn.hutool.core.io.FileUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * @author wulinxuan
 * @date 2025/6/5 0:05
 */
@Slf4j
@Service
public class DockerSandBox {

    @Resource
    private DockerDao dockerDao;

    @Resource
    private ContainerPoolExecutor containerPoolExecutor;

    /**
     * 执行代码
     *
     * @param languageCmdEnum 编程语言枚举
     * @param code            代码
     * @return {@link ExecuteResponse}
     */
    public ExecuteResponse execute(LanguageCmdEnum languageCmdEnum, String code) {
        return containerPoolExecutor.run(containerInfo -> {
            try {
                String containerId = containerInfo.getContainerId();

                String codePathName = containerInfo.getCodePathName();

                String codeFileName = codePathName + File.separator + languageCmdEnum.getSaveFileName();

                FileUtil.writeString(code, codeFileName, StandardCharsets.UTF_8);

                dockerDao.copyFileToContainer(containerId, codeFileName);

                // 编译代码
                String[] compileCmd = languageCmdEnum.getCompileCmd();
                ExecuteResponse executeResponse;

                // 不为空则代表需要编译
                if (compileCmd != null) {
                    executeResponse = dockerDao.executeCmd(containerId, compileCmd);
                    log.info("compile complete...");
                    // 编译错误
                    if (!executeResponse.isSuccess()) {
                        return executeResponse;
                    }
                }
                String[] runCmd = languageCmdEnum.getRunCmd();
                executeResponse = dockerDao.executeCmd(containerId, runCmd);
                log.info("run complete...");
                return executeResponse;
            } catch (Exception e) {
                return ExecuteResponse.builder()
                        .success(false)
                        .errorMsg(e.getMessage())
                        .build();
            }
        });
    }


}

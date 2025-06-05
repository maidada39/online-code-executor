package com.maidada.onlinecodeexecutor.executor;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * @author wulinxuan
 * @date 2025/6/5 0:50
 */
@RestController
@RequestMapping("code")
public class ExecutorController {

    @Resource
    private DockerSandBox dockerSandBox;

    @PostMapping("exec")
    public ExecuteResponse execute(@RequestBody ExecuteRequest request) {
        LanguageCmdEnum languageCmdEnum = LanguageCmdEnum.getEnumByValue(request.getLanguage());
        if (languageCmdEnum == null) {
            return ExecuteResponse.builder()
                    .success(false)
                    .errorMsg("不支持的语言")
                    .build();
        }
        return dockerSandBox.execute(languageCmdEnum, request.getCode());
    }
}

package com.maidada.onlinecodeexecutor.executor;

import lombok.Data;

/**
 * @author wulinxuan
 * @date 2025/6/4 23:56
 */
@Data
public class ExecuteRequest {

    private String language;

    private String code;
}

package com.maidada.onlinecodeexecutor.executor;

import lombok.Builder;
import lombok.Data;

/**
 * @author wulinxuan
 * @date 2025/6/4 23:58
 */
@Data
@Builder
public class ExecuteResponse {

    private boolean success;

    private String msg;

    private String errorMsg;
}

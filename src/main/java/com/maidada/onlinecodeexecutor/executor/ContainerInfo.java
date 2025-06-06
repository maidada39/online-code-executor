package com.maidada.onlinecodeexecutor.executor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author wulinxuan
 * @date 2025-06-06 11:08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerInfo {

    private String containerId;

    private String codePathName;

    private long lastActivityTime;

    private int errorCount = 0;
}

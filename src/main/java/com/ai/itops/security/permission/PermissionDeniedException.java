package com.ai.itops.security.permission;

/** 统一的业务鉴权失败异常，由全局异常处理器映射为 403。 */
public class PermissionDeniedException extends RuntimeException {

    public static final String CODE = "WORKSPACE_PERMISSION_DENIED";

    public PermissionDeniedException(String message) {
        super(message);
    }
}

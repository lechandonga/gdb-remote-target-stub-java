package com.github.lechandonga.gdbremotetargetstubjava.target;

/**
 * 模拟板子可预期的错误，携带 GDB 远程协议错误回复所需的两位十六进制错误码。
 */
public class BoardException extends RuntimeException {

    public static final int E_BAD_REGISTER = 0x01;
    public static final int E_UNMAPPED_MEMORY = 0x10;
    public static final int E_BP_RESOURCE_FULL = 0x13;
    public static final int E_BP_BAD_ADDRESS = 0x14;
    public static final int E_NOT_RUNNABLE = 0x05;
    public static final int E_BAD_ARGUMENT = 0x22;

    private final int code;

    public BoardException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public String errorReply() {
        return String.format("E%02x", code);
    }
}

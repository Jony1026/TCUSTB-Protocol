package net.tdteam.ustb.common.error;

import org.springframework.http.HttpStatus;

public class ProtocolException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ProtocolException(HttpStatus status, String message) {
        this(status, "PROTOCOL_ERROR", message);
    }

    /**
     * 以固定错误码描述认证失败原因，不携带学校raw response
     * @author itsjony01
     * @date 2026-10-01
     */
    public ProtocolException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}

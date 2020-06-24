package in.xnnyygn.xraft.kvstore.message;

public class Failure {

    /**
     * 错误码
     * 100      通用异常
     * 101      超时
     */
    private final int errorCode;
    private final String message; // 描述

    public Failure(int errorCode, String message) {
        this.errorCode = errorCode;
        this.message = message;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "Failure{" +
                "errorCode=" + errorCode +
                ", message='" + message + '\'' +
                '}';
    }

}

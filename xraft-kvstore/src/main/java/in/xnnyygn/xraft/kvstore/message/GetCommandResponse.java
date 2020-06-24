package in.xnnyygn.xraft.kvstore.message;

/**
 * 返回关键字对一个的值
 */
public class GetCommandResponse {

    // 方便区分 null 和没有找到数据的情况
    private final boolean found;
    // kV 服务不知道实际存储的是数字、字符串还是复杂类型, 所以用更一般化的二进制数组来表示
    private final byte[] value;

    public GetCommandResponse(byte[] value) {
        this(value != null, value);
    }

    public GetCommandResponse(boolean found, byte[] value) {
        this.found = found;
        this.value = value;
    }

    public boolean isFound() {
        return found;
    }

    public byte[] getValue() {
        return value;
    }

    @Override
    public String toString() {
        return "GetCommandResponse{found=" + found + '}';
    }

}

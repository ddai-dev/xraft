package in.xnnyygn.xraft.kvstore.message;

/**
 * get 请求, 传入关键字 key
 */
public class GetCommand  {

    private final String key;

    public GetCommand(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    @Override
    public String toString() {
        return "GetCommand{" +
                "key='" + key + '\'' +
                '}';
    }

}

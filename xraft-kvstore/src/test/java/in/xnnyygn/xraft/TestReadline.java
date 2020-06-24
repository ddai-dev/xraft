package in.xnnyygn.xraft;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.completer.ArgumentCompleter;
import org.jline.reader.impl.completer.NullCompleter;
import org.jline.reader.impl.completer.StringsCompleter;
import org.junit.Test;

import java.util.Arrays;

/**
 * @author ddai
 * @date 2020/6/1 15:14
 */
public class TestReadline {
    @Test
    public void testConsole() {
        final ArgumentCompleter completer = new ArgumentCompleter(new StringsCompleter(Arrays.asList("foo", "bar")), new NullCompleter());

        final LineReader reader = LineReaderBuilder.builder()
                .completer(completer)
                .build();

        final String line = reader.readLine("prompt>");
        System.out.println(line);
    }
}

package craftport.minecraft;

import craftport.base.Log;
import craftport.base.Os;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一个已检测到的 Java 运行时（对应 ModJava.vb 的 JRE 检测结果）。
 */
public record JavaRuntime(String majorVersion, String fullVersion, Path executable,
                          String fileEncoding, String nativeEncoding) {

    public int major() {
        try {
            return Integer.parseInt(majorVersion);
        } catch (Exception e) {
            return 8;
        }
    }

    @Override
    public String toString() {
        return "Java " + majorVersion + " (" + fullVersion + ") - " + executable;
    }
}

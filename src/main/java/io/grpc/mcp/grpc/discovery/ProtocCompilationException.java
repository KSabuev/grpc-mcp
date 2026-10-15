package io.grpc.mcp.grpc.discovery;

import lombok.Getter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * protoc failed to compile a proto file (syntax error, unresolved import, etc.).
 * Carries the raw protoc output and, when parseable, the file:line:column
 * of the first reported error.
 */
@Getter
public class ProtocCompilationException extends RuntimeException {

    private static final Pattern LOCATION = Pattern.compile("^(.+?):\\s*(\\d+):(\\d+):\\s*(.+)$");

    private final int exitCode;
    private final String rawOutput;
    private final String file;
    private final int line;
    private final int column;
    private final String firstError;

    public ProtocCompilationException(int exitCode, String rawOutput) {
        super("protoc failed with exit code " + exitCode + ":\n" + rawOutput);
        this.exitCode = exitCode;
        this.rawOutput = rawOutput == null ? "" : rawOutput;
        String parsedFile = null;
        int parsedLine = 0;
        int parsedColumn = 0;
        String parsedFirstError = null;
        for (String outputLine : this.rawOutput.split("\\R")) {
            Matcher matcher = LOCATION.matcher(outputLine.trim());
            if (matcher.matches()) {
                parsedFile = matcher.group(1);
                parsedLine = Integer.parseInt(matcher.group(2));
                parsedColumn = Integer.parseInt(matcher.group(3));
                parsedFirstError = matcher.group(4).trim();
                break;
            }
        }
        this.file = parsedFile;
        this.line = parsedLine;
        this.column = parsedColumn;
        this.firstError = parsedFirstError;
    }
}
